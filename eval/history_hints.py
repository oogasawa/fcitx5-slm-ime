#!/usr/bin/env python3
"""
Whether the model judges hints like the user's earlier choices by the text: it should write a right one and leave a
wrong one (かんじをかく~感じを書く for 漢字を書く), as prep/add_hints.py --history-table teaches it.

  history_hints.py make HARD_WORDS COMMON_WORDS TEST OUT_DIR [--most 3000]
      Writes OUT_DIR/hinted.tsv, test rows with one or two such hints after the hints of the dictionary (all the rows
      that get one, up to --most), OUT_DIR/plain.tsv, the same rows with the hints of the dictionary only, and
      OUT_DIR/hints.tsv, for each row those hints (reading~words~right or wrong).
  history_hints.py score OUT_DIR PLAIN_DUMP HINTED_DUMP
      PLAIN_DUMP and HINTED_DUMP are eval/greedy_check.py run on plain.tsv and hinted.tsv. For the right hints: how
      often the word is written with the hint and without it; for the wrong ones: how often the model writes the word
      it was given wrongly, and how often the conversion of the row is spoilt where it was right without the hint.
"""

import argparse
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "prep"))
import add_hints  # noqa: E402

SEP = "\x1f"


def make(args):
    words = add_hints.load_hard_words(args.hard_words, 3)
    add_hints.init(words, 6, 4, 192, 0, None, add_hints.load_history_table(args.common_words), 100)
    os.makedirs(args.out_dir, exist_ok=True)
    kept = 0
    with open(args.test, encoding="utf-8") as f, \
            open(os.path.join(args.out_dir, "hinted.tsv"), "w", encoding="utf-8") as hinted, \
            open(os.path.join(args.out_dir, "plain.tsv"), "w", encoding="utf-8") as plain, \
            open(os.path.join(args.out_dir, "hints.tsv"), "w", encoding="utf-8") as table:
        for line in f:
            source, target = line.rstrip("\n").split("\t", 1)
            if source.count(SEP) == 2:
                source = source.split(SEP, 1)[1]
            text = target.replace("|", "")
            with_short = add_hints.with_hints(source, target)
            given = [h.split(add_hints.HISTORY_MARK) for h in with_short.split(SEP)[0].split(" ")
                     if with_short.count(SEP) == 2 and add_hints.HISTORY_MARK in h]
            if not given:
                continue
            hinted.write(f"{with_short}\t{target}\n")
            plain.write(f"{add_hints.with_hints(source)}\t{target}\n")
            table.write("\t".join(f"{r}~{w}~{'right' if w in text else 'wrong'}" for r, w in given) + "\n")
            kept += 1
            if kept == args.most:
                break
    print(f"{kept} rows with hints like the user's choices")


def score(args):
    rows = [line.rstrip("\n").split("\t") for line in open(os.path.join(args.out_dir, "hints.tsv"), encoding="utf-8")]
    targets = [line.rstrip("\n").split("\t", 1)[1].replace("|", "")
               for line in open(os.path.join(args.out_dir, "hinted.tsv"), encoding="utf-8")]
    plain = [line.rstrip("\n").split("\t", 1)[1].replace("|", "") for line in open(args.plain, encoding="utf-8")]
    hinted = [line.rstrip("\n").split("\t", 1)[1].replace("|", "") for line in open(args.hinted, encoding="utf-8")]
    c = {"right": [0, 0, 0], "wrong": [0, 0, 0]}
    spoilt = rows_right = 0
    for hints, target, p, h in zip(rows, targets, plain, hinted):
        for hint in filter(None, hints):
            r, w, kind = hint.split("~")
            k = c[kind]
            k[0] += 1
            k[1] += w in p
            k[2] += w in h
        if p == target:
            rows_right += 1
            spoilt += h != target
    r, w = c["right"], c["wrong"]
    print(f"right hints {r[0]}: word written without the hint {r[1] / max(r[0], 1) * 100:.1f}%, "
          f"with it {r[2] / max(r[0], 1) * 100:.1f}%")
    print(f"wrong hints {w[0]}: their word written without the hint {w[1] / max(w[0], 1) * 100:.1f}%, "
          f"with it {w[2] / max(w[0], 1) * 100:.1f}%")
    print(f"rows right without the hints {rows_right}, spoilt by them {spoilt} "
          f"({spoilt / max(rows_right, 1) * 100:.1f}%)")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    m = sub.add_parser("make")
    for name in ("hard_words", "common_words", "test", "out_dir"):
        m.add_argument(name)
    m.add_argument("--most", type=int, default=3000)
    s = sub.add_parser("score")
    for name in ("out_dir", "plain", "hinted"):
        s.add_argument(name)
    args = parser.parse_args()
    make(args) if args.command == "make" else score(args)


if __name__ == "__main__":
    main()
