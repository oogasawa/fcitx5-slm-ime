#!/usr/bin/env python3
"""
The accuracy by the length of the reading, from the per-sentence output of compare_cut.py: for each system and
each band of lengths (20-29, 30-39, 40-49, 50-59, 60 and more characters of the reading), the mean number of places
to correct per sentence and the share of the sentences with nothing to correct. A place to correct is a run of
characters where the output differs from the correct text (an opcode of difflib other than equal). The numbers of
the chart of the README (docs/accuracy-by-length.svg).

Usage: accuracy_by_length.py PER_SENTENCE.tsv [--fold]
  --fold  fold the forms of symbols first, as compare_systems.py does
"""

import difflib
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from compare_systems import fold  # noqa: E402

BANDS = [(20, 29), (30, 39), (40, 49), (50, 59), (60, 10**9)]


def places(reference: str, output: str) -> int:
    return sum(1 for op, *_ in difflib.SequenceMatcher(None, reference, output, autojunk=False).get_opcodes()
               if op != "equal")


def main():
    path = sys.argv[1]
    folded = "--fold" in sys.argv[2:]
    with open(path, encoding="utf-8") as f:
        header = f.readline().rstrip("\n").split("\t")
        rows = [line.rstrip("\n").split("\t") for line in f]
    names = header[3:]
    stats = {name: [[0, 0, 0] for _ in BANDS] for name in names}  # sentences, places, with none
    for row in rows:
        reading, reference = row[1], row[2].replace("|", "")
        band = next((i for i, (lo, hi) in enumerate(BANDS) if lo <= len(reading) <= hi), None)
        if band is None:
            continue
        for name, output in zip(names, row[3:]):
            output = output.replace("|", "")
            ref = reference
            if folded:
                ref, output = fold(ref), fold(output)
            n = places(ref, output)
            s = stats[name][band]
            s[0] += 1
            s[1] += n
            s[2] += n == 0
    print("band      " + "".join(f"{name:>24}" for name in names))
    for i, (lo, hi) in enumerate(BANDS):
        label = f"{lo}+" if hi > 10**8 else f"{lo}-{hi}"
        cells = []
        for name in names:
            n, p, z = stats[name][i]
            cells.append(f"{p / max(n, 1):.2f} / {z / max(n, 1) * 100:.1f}% ({n})")
        print(f"{label:10}" + "".join(f"{c:>24}" for c in cells))


if __name__ == "__main__":
    main()
