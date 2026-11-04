#!/usr/bin/env python3
"""
Make the table of the medical terms of the Manbyo dictionary (MANBYO_*.csv of NAIST Social Computing Lab, CC BY 4.0,
https://sociocom.naist.jp/manbyou-dic/) in the form of prep/hard_words.py, to give them to the model as hints.

Only the terms of the reliability levels S and A are kept (about 30,000 of 380,000; the others are mostly forms
found by machine), with a reading in hiragana and two or more characters. Their field is medicine.

Usage: prep/manbyo_words.py MANBYO_20210602.csv > manbyo_words.tsv
Output: reading TAB word TAB medical TAB medicine, sorted by reading. The table of the model of version 4 is
manbyo_words.tsv and then the table of hard_words.py --keep-compounds, one after the other.
"""

import csv
import re
import sys

LEVELS = {"S", "A"}
HIRAGANA = re.compile(r"[ぁ-ゖー]+")


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    words = set()
    with open(sys.argv[1], encoding="utf-8") as f:
        for row in csv.DictReader(f):
            reading, word = row["出現形よみ"], row["出現形"]
            if row["信頼度レベル"] in LEVELS and HIRAGANA.fullmatch(reading) and len(word) >= 2:
                words.add((reading, word))
    for reading, word in sorted(words):
        print(f"{reading}\t{word}\tmedical\tmedicine")
    print(f"{len(words)} medical terms", file=sys.stderr)


if __name__ == "__main__":
    main()
