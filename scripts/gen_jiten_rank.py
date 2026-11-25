#!/usr/bin/env python3
"""Build assets/jiten_rank.txt from Jiten's global frequency CSV.

Usage: python3 scripts/gen_jiten_rank.py /path/to/frequency_list_global.csv [max_rows]

The source is https://jiten.moe/frequency-dictionaries (Global, CSV), licensed
CC BY-SA 4.0. Only the first max_rows unique dictionary words are kept so the
APK stays small; rank is the exact Jiten rank from the CSV.
"""

import csv
import os
import sys

MAX_ROWS = 100000
OUT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                   "assets", "jiten_rank.txt")


def main() -> int:
    if len(sys.argv) < 2:
        print("usage: gen_jiten_rank.py <jiten-global.csv> [max_rows]", file=sys.stderr)
        return 2
    limit = int(sys.argv[2]) if len(sys.argv) > 2 else MAX_ROWS

    ranks: dict[str, int] = {}
    with open(sys.argv[1], encoding="utf-8-sig", newline="") as f:
        reader = csv.DictReader(f)
        for row in reader:
            word = (row.get("Word") or "").strip()
            if not word:
                continue
            rank = int(row.get("Rank") or 0)
            if rank <= 0:
                continue
            old = ranks.get(word)
            if old is None or rank < old:
                ranks[word] = rank
            if len(ranks) >= limit:
                break

    ordered = sorted(ranks.items(), key=lambda item: (item[1], item[0]))
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8", newline="\n") as out:
        for word, rank in ordered:
            out.write(f"{word}\t{rank}\n")

    size = os.path.getsize(OUT)
    print(f"wrote {len(ordered)} entries to {OUT} ({size / 1024 / 1024:.2f} MB)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
