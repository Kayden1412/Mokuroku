#!/usr/bin/env python3
"""Build assets/dict.bin: a precompiled flat-array trie + packed JMdict/JitenRank binary.

Inputs:
  data/jmdict.txt     (or assets/jmdict.txt if not moved yet)
  data/jiten_rank.txt (or assets/jiten_rank.txt if not moved yet)

Output:
  assets/dict.bin

The trie is the exact same flat-array structure used by Tokenizer.java's inner
Trie class (char[] ch, int[] firstChild, int[] nextSibling, byte[] flags,
int[] rank), but serialized to a binary file instead of built from text at
runtime. This eliminates ~4 MB of text parsing and ~30 MB of HashMap allocation
on every cold start.

Binary layout (big-endian):
  magic:          "MKD2" (4 bytes)

  -- Section 1: Trie --
  nodeCount:      int32 (node 0 is unused sentinel)
  rootSize:       int32 (always 65536: one slot per UTF-16 code unit)
  root:           int32[rootSize]  (root[c] = first trie node for char c, or -1)
  ch:             uint16[nodeCount]
  firstChild:     int32[nodeCount]
  nextSibling:    int32[nodeCount]
  flags:          byte[nodeCount]  (bit0=word, bit1=stem)
  rank:           int32[nodeCount]

  -- Section 2: JMDict entry table --
  formCount:      int32
  formNodeId:     int32[formCount]
  formToEntry:    uint16[formCount]
  entryCount:     int32
  entryConds:     int32[entryCount]
  entryContent:   byte[entryCount]  (1=content, 0=not)
  entryOffsets:   int32[entryCount + 1]
  blobLen:        int32
  entryBlob:      byte[blobLen]  (UTF-8: word\0reading\0pos\0glosses per entry)
"""

import os
import struct
import zlib

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

NON_CONTENT_POS = {
    "particle",
    "conjunction",
    "interjection (kandoushi)",
    "copula",
    "pronoun",
    "prefix",
    "suffix",
    "numeric",
}

IRREGULAR_STEMS = ("来", "き", "こ", "く", "し", "さ", "せ", "す", "よ", "い")

FLAG_WORD = 1
FLAG_STEM = 2


def pick_input(name: str) -> str:
    p1 = os.path.join(ROOT, "data", name)
    if os.path.isfile(p1):
        return p1
    return os.path.join(ROOT, "assets", name)


def utf16_units(text: str) -> list[int]:
    """Java char units. Python iterates code points, Java iterates UTF-16 units.

    Without this, supplementary-plane characters (e.g. 𠮟る) overflow the
    65536-slot root table and the trie no longer matches Tokenizer.java.
    """
    raw = text.encode("utf-16-be", errors="surrogatepass")
    return [raw[i] << 8 | raw[i + 1] for i in range(0, len(raw), 2)]


def pos_conditions(pos_list: list[str]) -> int:
    # Must match DeinflectorRules constants exactly.
    V1, V5, VK, VS, VZ, ADJ_I, V = 3, 28, 32, 64, 128, 256, 255
    c = 0
    for p in pos_list:
        if p.startswith("Ichidan verb - zuru verb"):
            c |= VZ
        elif p.startswith("Ichidan verb"):
            c |= V1
        elif p.startswith("Godan verb"):
            c |= V5
        elif p.startswith("Kuru verb"):
            c |= VK
        elif p.startswith("suru verb") or p.startswith("su verb") or "aux. verb suru" in p or p == "copula":
            c |= VS
        elif p.startswith("adjective (keiyoushi)"):
            c |= ADJ_I
        elif "verb" in p:
            c |= V
    return c


def has_content_pos(pos_list: list[str]) -> bool:
    if not pos_list:
        return True
    for p in pos_list:
        if p in NON_CONTENT_POS or p.startswith("auxiliary") or p.startswith(" "):
            continue
        return True
    return False


# ---- Flat-array trie builder (mirrors Tokenizer.java's Trie exactly) ----

class Trie:
    """Flat-array trie with linked-list children, identical to Tokenizer.java."""

    def __init__(self, capacity: int = 230000):
        self.root = [-1] * 65536
        self.ch = [0] * capacity
        self.first = [-1] * capacity
        self.nxt = [-1] * capacity
        self.flags = [0] * capacity
        self.rank = [0] * capacity
        self.size = 1  # node 0 is unused sentinel
        # JMdict entry_id per node (-1 = no entry)
        self.entry_id = [-1] * capacity

    def _alloc(self, c: int) -> int:
        nid = self.size
        self.size += 1
        if nid >= len(self.ch):
            grow = max(nid + 1, len(self.ch) + len(self.ch) // 2)
            self.ch.extend([0] * (grow - len(self.ch)))
            self.first.extend([-1] * (grow - len(self.first)))
            self.nxt.extend([-1] * (grow - len(self.nxt)))
            self.flags.extend([0] * (grow - len(self.flags)))
            self.rank.extend([0] * (grow - len(self.rank)))
            self.entry_id.extend([-1] * (grow - len(self.entry_id)))
        self.ch[nid] = c
        self.first[nid] = -1
        self.nxt[nid] = -1
        self.flags[nid] = 0
        self.rank[nid] = 0
        self.entry_id[nid] = -1
        return nid

    def insert(self, word, word_rank: int, is_word: bool, is_stem: bool,
               eid: int = -1) -> int:
        """Insert a word into the trie. Returns the terminal node id."""
        units = utf16_units(word) if isinstance(word, str) else word
        if not units:
            return -1
        c0 = units[0]
        node = self.root[c0]
        if node < 0:
            node = self._alloc(c0)
            self.root[c0] = node
        for i in range(1, len(units)):
            c = units[i]
            child = self.first[node]
            while child >= 0 and self.ch[child] != c:
                child = self.nxt[child]
            if child < 0:
                child = self._alloc(c)
                self.nxt[child] = self.first[node]
                self.first[node] = child
            node = child
        if is_word:
            self.flags[node] |= FLAG_WORD
            if self.rank[node] == 0 or word_rank < self.rank[node]:
                self.rank[node] = word_rank
        if is_stem:
            self.flags[node] |= FLAG_STEM
        if eid >= 0 and self.entry_id[node] < 0:
            self.entry_id[node] = eid
        return node


def main() -> int:
    jmdict_path = pick_input("jmdict.txt")
    jiten_path = pick_input("jiten_rank.txt")
    out_path = os.path.join(ROOT, "assets", "dict.bin")

    trie = Trie()
    entries_blob = bytearray()
    entry_offsets = [0]
    entry_conds: list[int] = []
    entry_content: list[int] = []
    seen_forms: set[str] = set()

    # --- Load JMDict ---
    form_node_ids: list[int] = []
    form_entry_ids: list[int] = []

    with open(jmdict_path, encoding="utf-8") as f:
        for line in f:
            line = line.rstrip("\n")
            fields = line.split("\t")
            if len(fields) < 4:
                continue
            forms = [x for x in fields[0].split(",") if x]
            if not forms:
                continue
            reading = fields[1]
            pos_str = fields[2]
            pos_list = pos_str.split(",") if pos_str else []
            glosses_str = fields[3]

            eid = len(entry_conds)
            cond = pos_conditions(pos_list)
            entry_conds.append(cond)
            entry_content.append(1 if has_content_pos(pos_list) else 0)

            payload = f"{forms[0]}\0{reading}\0{pos_str}\0{glosses_str}".encode("utf-8")
            entries_blob.extend(payload)
            entry_offsets.append(len(entries_blob))

            for form in forms:
                if form in seen_forms:
                    continue
                seen_forms.add(form)
                nid = trie.insert(form, 0, True, False, eid)
                form_node_ids.append(nid)
                form_entry_ids.append(eid)
                if cond != 0:
                    stem = form[:-1] if len(form) >= 2 else form
                    trie.insert(stem, 0, False, True)

    # --- Load JitenRank ---
    with open(jiten_path, encoding="utf-8") as f:
        for line in f:
            tab = line.find("\t")
            if tab <= 0:
                continue
            w = line[:tab]
            r = int(line[tab + 1:].strip())
            trie.insert(w, r, True, False)

    # --- Irregular stems ---
    for s in IRREGULAR_STEMS:
        trie.insert(s, 0, False, True)

    # Keep form table sorted by node id so DictBin.entryFor() can binary-search.
    form_pairs = sorted(zip(form_node_ids, form_entry_ids))
    form_node_ids = [p[0] for p in form_pairs]
    form_entry_ids = [p[1] for p in form_pairs]

    # --- Write binary ---
    node_count = trie.size
    entry_count = len(entry_conds)
    form_count = len(form_node_ids)
    assert entry_count < 65536, f"Too many entries: {entry_count}"

    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    with open(out_path, "wb") as out:
        # Magic
        out.write(b"MKD2")

        # -- Section 1: Trie --
        out.write(struct.pack(">II", node_count, 65536))
        # root[65536]
        out.write(struct.pack(f">65536i", *trie.root))
        # ch[nodeCount] as uint16
        out.write(struct.pack(f">{node_count}H", *trie.ch[:node_count]))
        # firstChild[nodeCount]
        out.write(struct.pack(f">{node_count}i", *trie.first[:node_count]))
        # nextSibling[nodeCount]
        out.write(struct.pack(f">{node_count}i", *trie.nxt[:node_count]))
        # flags[nodeCount]
        out.write(bytes(trie.flags[:node_count]))
        # rank[nodeCount]
        out.write(struct.pack(f">{node_count}i", *trie.rank[:node_count]))

        # -- Section 2: JMDict entry table --
        out.write(struct.pack(">I", form_count))
        out.write(struct.pack(f">{form_count}i", *form_node_ids))
        out.write(struct.pack(f">{form_count}H", *form_entry_ids))
        out.write(struct.pack(">I", entry_count))
        out.write(struct.pack(f">{entry_count}i", *entry_conds))
        out.write(bytes(entry_content))
        out.write(struct.pack(f">{entry_count + 1}I", *entry_offsets))
        out.write(struct.pack(">I", len(entries_blob)))
        out.write(entries_blob)

    raw_bytes = os.path.getsize(out_path)
    with open(out_path, "rb") as f:
        gz_bytes = len(zlib.compress(f.read(), level=9))

    # --- Verification ---
    test_words = ["猫", "食べる", "引っ越す", "人々", "ありがとう", "私", "走る", "美しい"]
    errors = 0
    for w in test_words:
        units = utf16_units(w)
        c0 = units[0]
        node = trie.root[c0]
        if node < 0:
            print(f"  VERIFY FAIL: {w} - root miss on {w[0]}")
            errors += 1
            continue
        ok = True
        for i in range(1, len(units)):
            c = units[i]
            child = trie.first[node]
            while child >= 0 and trie.ch[child] != c:
                child = trie.nxt[child]
            if child < 0:
                print(f"  VERIFY FAIL: {w} - miss at char {i} '{w[i]}'")
                ok = False
                errors += 1
                break
            node = child
        if ok:
            f = trie.flags[node]
            r = trie.rank[node]
            eid = trie.entry_id[node]
            print(f"  VERIFY OK: {w} -> node={node} flags={f} rank={r} entry={eid}")

    print(
        f"\ndict.bin: nodes={node_count} forms={form_count} entries={entry_count} "
        f"raw={raw_bytes} ({raw_bytes / 1024 / 1024:.2f} MB) "
        f"deflated={gz_bytes} ({gz_bytes / 1024 / 1024:.2f} MB)"
    )
    if errors > 0:
        print(f"WARNING: {errors} verification failures!")
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
