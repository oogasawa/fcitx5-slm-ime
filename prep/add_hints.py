#!/usr/bin/env python3
"""
Put the words of the dictionary that the reading may hold in front of the source of the rows, as hints the model can
copy: the model learns grammar from the sentences, and the words it sees too seldom come from the dictionary
(prep/hard_words.py).

The source of a row with hints is
    <reading>=<word> <reading>=<word> ... SEP <text before the cursor> SEP <reading>
and a row without hints keeps its form, <text before the cursor> SEP <reading>. The reading of each hint tells the
model which stretch of the typed reading the word stands for. The words are those whose reading is a stretch of the
typed reading at least --shortest kana long and that are at least --fewest-chars characters long, the longest
readings first, at most --most of them; a word may be one the sentence does not use, as in the engine, which looks
the words up the same way. Short words are left out because their readings are found where other words stand next
to each other (強酸 in にほんきょうさんとう, 周章 in りょうしゅうしょう): in the training rows of the released model,
with the table of hard_words.py --keep-compounds, 10 % of the rows with hints had only wrong ones with these limits,
and most rows had only wrong ones with readings of 4 kana. The text before the cursor is cut from the left so that
the source stays within --max-len characters.

Usage: prep/add_hints.py hard_words.tsv IN_DIR OUT_DIR [--shortest 6] [--fewest-chars 3] [--most 4] [--max-len 192]
       [--rows N] [--workers 8]
Reads IN_DIR/{train,valid,test}.tsv and writes the same files to OUT_DIR, with tokenizer.json copied; --rows keeps
every k-th training row so that about N are written.
"""

import argparse
import multiprocessing
import os
import shutil

SEP = "\x1f"

_words: dict[str, list[str]] = {}
_lengths: list[int] = []
_most = 4
_max_len = 192


def load_hard_words(path: str, fewest_chars: int = 1) -> dict[str, list[str]]:
    """The words of the table by reading, those of at least fewest_chars characters."""
    words: dict[str, list[str]] = {}
    with open(path, encoding="utf-8") as f:
        for line in f:
            reading, word = line.rstrip("\n").split("\t")[:2]
            if len(word) >= fewest_chars:
                words.setdefault(reading, []).append(word)
    return words


def hints(reading: str, words: dict[str, list[str]], lengths: list[int], most: int) -> list[tuple[str, str]]:
    """The (reading, word) of the words whose reading is a stretch of the reading, the longest readings first."""
    found: list[tuple[str, str]] = []
    for n in lengths:
        for i in range(len(reading) - n + 1):
            for word in words.get(reading[i:i + n], ()):
                if all(word != w for _, w in found):
                    found.append((reading[i:i + n], word))
                    if len(found) == most:
                        return found
    return found


def with_hints(source: str) -> str:
    context, _, reading = source.rpartition(SEP)
    found = hints(reading, _words, _lengths, _most)
    if not found:
        return source
    head = " ".join(f"{r}={w}" for r, w in found) + SEP
    room = _max_len - len(head) - 1 - len(reading)
    if room < 0:
        return source
    return head + context[max(0, len(context) - room):] + SEP + reading


def convert_line(line: str) -> str:
    source, target = line.rstrip("\n").split("\t", 1)
    return with_hints(source) + "\t" + target + "\n"


def init(words: dict[str, list[str]], shortest: int, most: int, max_len: int) -> None:
    global _words, _lengths, _most, _max_len
    _words, _most, _max_len = words, most, max_len
    _lengths = sorted({len(r) for r in words if len(r) >= shortest}, reverse=True)


def main():
    parser = argparse.ArgumentParser(description="Put dictionary hints in front of the source of the rows")
    parser.add_argument("hard_words")
    parser.add_argument("in_dir")
    parser.add_argument("out_dir")
    parser.add_argument("--shortest", type=int, default=6, help="the shortest reading of a hint, in kana")
    parser.add_argument("--fewest-chars", type=int, default=3, help="the shortest word of a hint, in characters")
    parser.add_argument("--most", type=int, default=4, help="the most hints of a row")
    parser.add_argument("--max-len", type=int, default=192, help="the longest source")
    parser.add_argument("--rows", type=int, default=0, help="about how many training rows to write (0: all)")
    parser.add_argument("--workers", type=int, default=8)
    args = parser.parse_args()

    words = load_hard_words(args.hard_words, args.fewest_chars)
    os.makedirs(args.out_dir, exist_ok=True)
    shutil.copy(os.path.join(args.in_dir, "tokenizer.json"), args.out_dir)
    with multiprocessing.Pool(args.workers, init, (words, args.shortest, args.most, args.max_len)) as pool:
        for name in ("train", "valid", "test"):
            with open(os.path.join(args.in_dir, f"{name}.tsv"), encoding="utf-8") as f:
                lines = f.readlines()
            if name == "train" and args.rows:
                lines = lines[::max(1, len(lines) // args.rows)]
            out = pool.map(convert_line, lines, chunksize=2000)
            with open(os.path.join(args.out_dir, f"{name}.tsv"), "w", encoding="utf-8") as f:
                f.writelines(out)
            hinted = sum(1 for line in out if line.split("\t", 1)[0].count(SEP) == 2)
            print(f"{name}: {len(out)} rows, {hinted} with hints")


if __name__ == "__main__":
    main()
