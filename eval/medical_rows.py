#!/usr/bin/env python3
"""
Make test rows from the pairs of the medical reports (eval/medtxt_chunks.py, then the pair workers of pairs/), in
the form of the test rows of the training data: <text before the cursor> SEP <reading> TAB <text with |>.

Every sentence is cut at bunsetsu boundaries into stretches of about --reading-chars kana, as the user types a few
bunsetsu and converts them; the text before the cursor of a stretch is the text of the report before it, the last
--context-chars characters. The katakana of the readings is written in hiragana, as the user types it. A sentence
whose reading and text have different numbers of bunsetsu is left out.

Usage: eval/medical_rows.py PAIRS_DIR OUT.tsv [--reading-chars 40] [--context-chars 100]
"""

import argparse
import glob
import os

SEP = "\x1f"


def to_hiragana(text: str) -> str:
    return "".join(chr(ord(c) - 0x60) if 0x30A1 <= ord(c) <= 0x30F6 else c for c in text)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("pairs_dir")
    parser.add_argument("out")
    parser.add_argument("--reading-chars", type=int, default=40)
    parser.add_argument("--context-chars", type=int, default=100)
    args = parser.parse_args()
    rows = []
    left_out = 0
    for path in sorted(glob.glob(os.path.join(args.pairs_dir, "*_pairs.tsv"))):
        before, page = "", None
        with open(path, encoding="utf-8") as f:
            next(f)
            for line in f:
                reading, text, p = line.rstrip("\n").split("\t")[:3]
                if p != page:
                    before, page = "", p
                readings, texts = reading.split("|"), text.split("|")
                if len(readings) != len(texts):
                    left_out += 1
                    before += text.replace("|", "")
                    continue
                start = 0
                while start < len(readings):
                    end = start + 1
                    while end < len(readings) and len("".join(readings[start:end + 1])) <= args.reading_chars:
                        end += 1
                    piece = "".join(readings[start:end])
                    rows.append(f"{before[-args.context_chars:]}{SEP}{to_hiragana(piece)}\t{'|'.join(texts[start:end])}\n")
                    before += "".join(texts[start:end])
                    start = end
    with open(args.out, "w", encoding="utf-8") as f:
        f.writelines(rows)
    print(f"{len(rows)} rows; {left_out} sentences left out")


if __name__ == "__main__":
    main()
