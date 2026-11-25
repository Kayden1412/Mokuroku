#!/usr/bin/env python3
"""Build a compact embedded JMdict subset (common-word entries) for the app."""
import gzip
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "JMdict_e.gz"
OUT = ROOT / "jepubvocab" / "assets" / "jmdict.txt"
PRIORITY = {"ichi1", "news1", "spec1", "gai1"}

OUT.parent.mkdir(parents=True, exist_ok=True)
count = 0
with gzip.open(SRC, "rb") as src, OUT.open("w", encoding="utf-8", newline="\n") as out:
    for _, el in ET.iterparse(src, events=("end",)):
        if el.tag != "entry":
            continue
        pri = {p.text for p in el.findall("./ke_pri") + el.findall("./r_ele/re_pri") if p.text}
        if pri & PRIORITY:
            forms = [k.text for k in el.findall("./k_ele/keb") if k.text]
            readings = [r.text for r in el.findall("./r_ele/reb") if r.text]
            if not readings:
                el.clear()
                continue
            pos = []
            glosses = []
            for sense in el.findall("./sense"):
                for p in sense.findall("./pos"):
                    if p.text and p.text not in pos:
                        pos.append(p.text)
                for g in sense.findall("./gloss"):
                    text = " ".join("".join(g.itertext()).split())
                    if text and text not in glosses:
                        glosses.append(text)
                    if len(glosses) >= 3:
                        break
                if len(glosses) >= 3:
                    break
            fields = [
                ",".join(forms + readings),
                readings[0],
                ",".join(pos),
                "\x1f".join(glosses),
            ]
            out.write("\t".join(fields) + "\n")
            count += 1
        el.clear()

print("entries=%d bytes=%d" % (count, OUT.stat().st_size))
