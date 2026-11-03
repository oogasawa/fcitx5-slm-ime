#!/usr/bin/env python3
"""
Whether the conversion writes the hard words of the dictionary (prep/hard_words.py), with and without hints.

  hint_words.py make HARD_WORDS TRAIN TEST OUT_DIR [--most 3000] [--shortest 6] [--fewest-chars 3] [--hints 4]
      Writes OUT_DIR/plain.tsv, the test rows whose correct text holds a hard word that may be a hint, and
      OUT_DIR/hinted.tsv, the same rows with the hints of prep/add_hints.py (--shortest, --fewest-chars and --hints
      are its --shortest, --fewest-chars and --most). Every row with a word that the training rows never hold is kept,
      and other rows up to --most in all. OUT_DIR/unseen.txt lists those words.
  hint_words.py score HARD_WORDS OUT_DIR DUMP... [--shortest 6] [--fewest-chars 3]
      For each dump of eval/greedy_check.py run on plain.tsv or hinted.tsv (all rows): the share of the hard words
      written, for the words the training rows hold and for those they never hold.
"""

import argparse
import os
import random
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "prep"))
import add_hints  # noqa: E402

LONGEST_WORD = 12


def hard_in(text: str, words: set[str]) -> set[str]:
    text = text.replace("|", "")
    return {text[i:i + n] for n in range(2, LONGEST_WORD + 1) for i in range(len(text) - n + 1)
            if text[i:i + n] in words}


def load_words(path: str, shortest: int, fewest_chars: int) -> set[str]:
    """The words of the table that may be hints."""
    return {w for r, ws in add_hints.load_hard_words(path, fewest_chars).items() if len(r) >= shortest for w in ws}


def make(args):
    words = load_words(args.hard_words, args.shortest, args.fewest_chars)
    seen: set[str] = set()
    with open(args.train, encoding="utf-8") as f:
        for line in f:
            seen |= hard_in(line.rstrip("\n").split("\t", 1)[1], words)
    rows = []
    with open(args.test, encoding="utf-8") as f:
        for line in f:
            source, target = line.rstrip("\n").split("\t", 1)
            found = hard_in(target, words)
            if found and len(source) <= add_hints._max_len:
                rows.append((source, target, bool(found - seen)))
    unseen_rows = [r for r in rows if r[2]]
    others = [r for r in rows if not r[2]]
    random.Random(1).shuffle(others)
    keep = unseen_rows + others[:max(0, args.most - len(unseen_rows))]
    by_reading = add_hints.load_hard_words(args.hard_words, args.fewest_chars)
    add_hints.init(by_reading, args.shortest, args.hints, add_hints._max_len)
    os.makedirs(args.out_dir, exist_ok=True)
    with open(os.path.join(args.out_dir, "plain.tsv"), "w", encoding="utf-8") as plain, \
            open(os.path.join(args.out_dir, "hinted.tsv"), "w", encoding="utf-8") as hinted:
        for source, target, _ in keep:
            plain.write(f"{source}\t{target}\n")
            hinted.write(f"{add_hints.with_hints(source)}\t{target}\n")
    unseen = sorted({w for s, t, u in unseen_rows for w in hard_in(t, words) - seen})
    with open(os.path.join(args.out_dir, "unseen.txt"), "w", encoding="utf-8") as f:
        f.writelines(w + "\n" for w in unseen)
    print(f"{len(rows)} test rows hold hard words; kept {len(keep)}, {len(unseen_rows)} of them with "
          f"{len(unseen)} words the training rows never hold")


def score(args):
    words = load_words(args.hard_words, args.shortest, args.fewest_chars)
    with open(os.path.join(args.out_dir, "unseen.txt"), encoding="utf-8") as f:
        unseen = {line.rstrip("\n") for line in f}
    with open(os.path.join(args.out_dir, "plain.tsv"), encoding="utf-8") as f:
        targets = [line.rstrip("\n").split("\t", 1)[1] for line in f]
    for dump in args.dumps:
        with open(dump, encoding="utf-8") as f:
            outputs = [line.rstrip("\n").split("\t", 1)[1].replace("|", "") for line in f]
        if len(outputs) != len(targets):
            sys.exit(f"{dump}: {len(outputs)} rows, not {len(targets)}")
        counts = {True: [0, 0], False: [0, 0]}
        for target, output in zip(targets, outputs):
            for word in hard_in(target, words):
                c = counts[word in unseen]
                c[0] += word in output
                c[1] += 1
        print(f"{dump}: words the training rows hold {counts[False][0]}/{counts[False][1]} "
              f"({counts[False][0] / max(1, counts[False][1]) * 100:.1f}%), never hold {counts[True][0]}/"
              f"{counts[True][1]} ({counts[True][0] / max(1, counts[True][1]) * 100:.1f}%)")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    m = sub.add_parser("make")
    m.add_argument("hard_words")
    m.add_argument("train")
    m.add_argument("test")
    m.add_argument("out_dir")
    m.add_argument("--most", type=int, default=3000)
    m.add_argument("--shortest", type=int, default=6)
    m.add_argument("--fewest-chars", type=int, default=3)
    m.add_argument("--hints", type=int, default=4)
    s = sub.add_parser("score")
    s.add_argument("hard_words")
    s.add_argument("out_dir")
    s.add_argument("dumps", nargs="+")
    s.add_argument("--shortest", type=int, default=6)
    s.add_argument("--fewest-chars", type=int, default=3)
    args = parser.parse_args()
    make(args) if args.command == "make" else score(args)


if __name__ == "__main__":
    main()
