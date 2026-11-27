#!/usr/bin/env python3
"""Build assets/jmnedict.bloom from EDRDG's JMnedict.

Usage: python3 scripts/gen_jmnedict_bloom.py /path/to/JMnedict.xml.gz

Only person-like names are included (surnames, given names, full names and
characters) and only forms that contain kanji: those are the tokens a novel
tokenizer turns into bogus vocabulary (安達の, 山田, ...). Place names and
company names are left out because the common ones are already JMdict words.

The result is a Bloom filter rather than a full word list: it detects whether a
token is probably a name without adding tens of thousands of strings to the
app's heap. 1 MiB of bits with 3 hashes is about 0.15% false positives for the
~340k forms.

Source: https://www.edrdg.org/pub/Nihongo/JMnedict.xml.gz
License: Creative Commons Attribution-ShareAlike 4.0 (EDRDG).
"""

import gzip
import os
import struct
import sys
import xml.etree.ElementTree as ET

OUT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                   "assets", "jmnedict.bloom")

BITS = 1 << 23
HASHES = 3
MASK = BITS - 1

PERSON_TYPES = {
    "family or surname",
    "female given name or forename",
    "given name or forename, gender not specified",
    "full name of a particular person",
    "male given name or forename",
    "character",
    "fiction",
    "unclassified name",
}


def is_kanji(c: int) -> bool:
    return 0x4E00 <= c <= 0x9FFF or 0x3400 <= c <= 0x4DBF


def has_kanji(form: str) -> bool:
    return 2 <= len(form) <= 20 and any(is_kanji(ord(ch)) for ch in form)


def h1(s: str) -> int:
    h = 0
    for ch in s:
        h = (31 * h + ord(ch)) & 0xFFFFFFFF
    return h


def h2(s: str) -> int:
    h = 0
    for ch in s:
        h = (131 * h + ord(ch)) & 0xFFFFFFFF
    return h | 1


def main() -> int:
    if len(sys.argv) < 2:
        print("usage: gen_jmnedict_bloom.py <JMnedict.xml.gz>", file=sys.stderr)
        return 2

    names = set()
    with gzip.open(sys.argv[1], "rb") as f:
        for event, el in ET.iterparse(f, events=("end",)):
            if el.tag != "entry":
                continue
            types = {t.text for t in el.findall(".//name_type")}
            if types & PERSON_TYPES:
                for e in el.findall("k_ele/keb"):
                    if e.text and has_kanji(e.text):
                        names.add(e.text)
            el.clear()

    bits = bytearray(BITS // 8)
    for name in names:
        a = h1(name)
        b = h2(name)
        for i in range(HASHES):
            bit = (a + i * b) & MASK
            bits[bit >> 3] |= 1 << (bit & 7)

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "wb") as out:
        out.write(b"MNBL")
        out.write(struct.pack(">II", BITS, HASHES))
        out.write(bits)

    size = os.path.getsize(OUT)
    print(f"wrote {len(names)} name forms to {OUT} ({size / 1024 / 1024:.2f} MB)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
