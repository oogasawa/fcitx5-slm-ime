#!/usr/bin/env python3
"""
Make the test files that compare the systems on the rows of a context data set (build_dataset_g.py --context-chars).

Usage: make_cut_tests.py <test.tsv of the context data set> <out-dir> [every=3]

Every <every>-th row of the input is kept, so that Mozc and Anthy, which are slow, run on a few thousand rows.
Three files are written, all with the same rows and the same targets:
  with_context.tsv      source = <text before the cursor> SEP <reading>       (the context model, as the editor sends it)
  empty_context.tsv     source = SEP <reading>                                (the context model, with nothing before the cursor)
  reading_only.tsv      source = <reading>                                    (a model trained on whole sentences without context)
"""

import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
from model.tokenizer import SEP_CHAR


def main() -> None:
    test_path, out_dir = sys.argv[1], sys.argv[2]
    every = int(sys.argv[3]) if len(sys.argv) > 3 else 3
    os.makedirs(out_dir, exist_ok=True)
    outputs = {name: open(os.path.join(out_dir, name + ".tsv"), "w", encoding="utf-8")
               for name in ("with_context", "empty_context", "reading_only")}
    kept = 0
    try:
        with open(test_path, encoding="utf-8") as f:
            for index, line in enumerate(f):
                if index % every != 0:
                    continue
                source, target = line.rstrip("\n").split("\t", 1)
                if SEP_CHAR not in source:
                    continue
                reading = source.split(SEP_CHAR, 1)[1]
                outputs["with_context"].write(source + "\t" + target + "\n")
                outputs["empty_context"].write(SEP_CHAR + reading + "\t" + target + "\n")
                outputs["reading_only"].write(reading + "\t" + target + "\n")
                kept += 1
    finally:
        for f in outputs.values():
            f.close()
    print(f"{kept} rows in each of 3 files under {out_dir}")


if __name__ == "__main__":
    main()
