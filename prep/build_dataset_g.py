#!/usr/bin/env python3
"""
Build train/valid/test files from the bunsetsu-segmented pair chunks (the set G).

Input:  a directory of <chunk>_pairs.tsv, each row  hiragana|with|bars <TAB> kanji|with|bars <TAB> page <TAB> source
Output: <out-dir>/train.tsv, valid.tsv, test.tsv, each row  source <TAB> target
          source = the reading with every '|' removed (what the user types)
          target = the kanji text with its '|' kept (what the model must output)

With --context-chars N the rows look like what the editor sends while the user types from left to right:
          source = <last N characters of the text before the cursor> SEP <reading to convert>
          target = <conversion of that reading>
The text before the cursor is the kanji text (without '|') of the sentences before in the same chunk, and of the
bunsetsu of the same sentence before the cut. The reading to convert starts at a bunsetsu boundary of the sentence
chosen at random and ends at a later one chosen at random, so that it is a few bunsetsu of the sentence, or all of
what is left. SEP is the character the tokenizer reads as the [SEP] token. In --empty-percent of the rows the text
before the cursor is left empty (the source then starts with SEP), so that the model also converts well where the
editor does not tell what is before the cursor. A sentence whose reading and kanji have different numbers of
bunsetsu is used whole.

The split is by chunk, not by row: the sentences of one chunk come from neighbouring articles, so rows of one
chunk in different splits would leak into the validation and test sets. A chunk goes to valid or test by a hash
of its file name, so the same chunk always lands in the same split when more chunks are added later.
"""

import argparse
import os
import random
import sys
import zlib

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
from model.tokenizer import SEP_CHAR


def split_of(chunk_name: str, valid_percent: int, test_percent: int) -> str:
    bucket = zlib.crc32(chunk_name.encode("utf-8")) % 100
    if bucket < valid_percent:
        return "valid"
    if bucket < valid_percent + test_percent:
        return "test"
    return "train"


def rows_of(path: str):
    """Yield (reading with '|', kanji with '|') in the order of the file."""
    with open(path, encoding="utf-8") as f:
        for line in f:
            columns = line.rstrip("\n").split("\t")
            if len(columns) < 2 or columns[0] == "hiragana":
                continue
            reading, kanji = columns[0].strip(), columns[1].strip()
            if not reading or not kanji:
                continue
            yield reading, kanji


def keeps_context_empty(chunk_name: str, index: int, empty_percent: int) -> bool:
    """Decided by a hash, so that a rebuild gives the same data."""
    return zlib.crc32(f"{chunk_name}:{index}".encode("utf-8")) % 100 < empty_percent


def cut_of(chunk_name: str, index: int, readings: list, kanjis: list):
    """Choose the bunsetsu [start, end) to convert; the same choice for the same chunk and row."""
    count = len(kanjis)
    if len(readings) != count:
        return 0, count
    rng = random.Random(zlib.crc32(f"cut:{chunk_name}:{index}".encode("utf-8")))
    start = rng.randrange(count)
    end = rng.randint(start + 1, count)
    return start, end


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("pairs_dir")
    parser.add_argument("out_dir")
    parser.add_argument("--valid-percent", type=int, default=1)
    parser.add_argument("--test-percent", type=int, default=1)
    parser.add_argument("--context-chars", type=int, default=0,
                        help="characters of the text before the cursor put before the reading; 0 for none "
                             "(then every row is a whole sentence)")
    parser.add_argument("--empty-percent", type=int, default=20,
                        help="share of the rows whose text before the cursor is left empty (with --context-chars)")
    args = parser.parse_args()
    os.makedirs(args.out_dir, exist_ok=True)

    chunks = {"train": 0, "valid": 0, "test": 0}
    rows = {"train": 0, "valid": 0, "test": 0}
    with_context = 0
    cut_in_sentence = 0
    outputs = {name: open(os.path.join(args.out_dir, name + ".tsv"), "w", encoding="utf-8") for name in chunks}
    try:
        for name in sorted(os.listdir(args.pairs_dir)):
            if not name.endswith("_pairs.tsv"):
                continue
            split = split_of(name, args.valid_percent, args.test_percent)
            chunks[split] += 1
            before = ""
            for index, (reading, kanji) in enumerate(rows_of(os.path.join(args.pairs_dir, name))):
                if args.context_chars <= 0:
                    outputs[split].write(reading.replace("|", "") + "\t" + kanji + "\n")
                    rows[split] += 1
                    continue
                readings, kanjis = reading.split("|"), kanji.split("|")
                start, end = cut_of(name, index, readings, kanjis)
                context = "" if keeps_context_empty(name, index, args.empty_percent) \
                    else (before + "".join(kanjis[:start]))[-args.context_chars:]
                source = context + SEP_CHAR + "".join(readings[start:end]) if len(readings) == len(kanjis) \
                    else context + SEP_CHAR + reading.replace("|", "")
                target = "|".join(kanjis[start:end])
                with_context += 1 if context else 0
                cut_in_sentence += 1 if start > 0 else 0
                before = (before + kanji.replace("|", ""))[-4 * args.context_chars:]
                outputs[split].write(source + "\t" + target + "\n")
                rows[split] += 1
    finally:
        for f in outputs.values():
            f.close()

    total = sum(rows.values())
    for split in ("train", "valid", "test"):
        print(f"{split}: {chunks[split]} chunks, {rows[split]} rows")
    if args.context_chars > 0:
        print(f"rows with a non-empty text before the cursor: {with_context} of {total}")
        print(f"rows that start inside the sentence: {cut_in_sentence} of {total}")


if __name__ == "__main__":
    main()
