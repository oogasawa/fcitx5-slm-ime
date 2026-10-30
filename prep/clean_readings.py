#!/usr/bin/env python3
"""
Clean the readings that the language model wrote into the pair chunks (the set G).

Every bunsetsu (reading, kanji text) of a row is checked with bunsetsu_check.py of Anthy-dicts: its reading must be
made of the readings of its characters (KANJIDIC2) or of its words (JMdict, JMnedict). A bunsetsu that fails gets the
reading of Sudachi (SudachiDict full) for the same stretch of the sentence, if that reading passes the check;
Sudachi reads the whole sentence, so the reading of a word depends on its neighbours. A row with a bunsetsu that
neither reading explains is kept, with a fifth column "skip": build_dataset_g.py takes it as text before the cursor
for the next rows but makes no training row of it.

Input:  a directory of <chunk>_pairs.tsv (hiragana|with|bars TAB kanji|with|bars TAB page TAB source)
Output: <out-dir>/<chunk>_pairs.tsv, the same rows with the readings replaced, and <out-dir>/changes.tsv with every
        replaced reading (chunk, row, bunsetsu, old reading, new reading, kanji); the counts are printed.

Usage: clean_readings.py PAIRS_DIR OUT_DIR --kanjidic kanjidic2.xml.gz --jmdict jmdict_pairs.tsv \\
           --checker ~/works/Anthy-dicts/tools [--workers 12]
Needs sudachipy and sudachidict_full.
"""

import argparse
import os
import sys
from multiprocessing import Pool

KANJI = ("一", "鿿")

_state = {}


def has_kanji(text):
    return any("一" <= c <= "鿿" or "㐀" <= c <= "䶿" or c == "々" or c >= "\U00020000"
               for c in text)


def init(checker_dir, kanjidic, jmdict):
    sys.path.insert(0, checker_dir)
    import bunsetsu_check
    from sudachipy import Dictionary, SplitMode
    _state["check"] = bunsetsu_check
    _state["readings"] = bunsetsu_check.load_readings(kanjidic)
    _state["words"] = bunsetsu_check.load_words(jmdict)
    _state["tokenizer"] = Dictionary(dict="full").create(mode=SplitMode.C)
    _state["mode"] = SplitMode.C


def readable(text, reading):
    return _state["check"].readable(text, reading, _state["readings"], _state["words"])


def sudachi_readings(texts):
    """The reading of every bunsetsu by Sudachi, from the whole sentence; None where a word crosses a border of the
    bunsetsu or has no reading. Words without kanji are written as they stand."""
    sentence = "".join(texts)
    starts, at = [], 0
    for t in texts:
        starts.append(at)
        at += len(t)
    ends = starts[1:] + [len(sentence)]
    pieces = [[] for _ in texts]
    broken = [False] * len(texts)
    bunsetsu = 0
    for m in _state["tokenizer"].tokenize(sentence, _state["mode"]):
        begin, end, surface = m.begin(), m.end(), m.surface()
        while bunsetsu < len(texts) and begin >= ends[bunsetsu]:
            bunsetsu += 1
        if bunsetsu >= len(texts):
            break
        if end > ends[bunsetsu]:
            # a word over the border: neither bunsetsu gets a reading
            broken[bunsetsu] = True
            last = bunsetsu
            while last < len(texts) and end > ends[last]:
                last += 1
                if last < len(texts):
                    broken[last] = True
            continue
        if has_kanji(surface):
            reading = _state["check"].hiragana(m.reading_form())
            if has_kanji(reading) or not reading:
                broken[bunsetsu] = True
                continue
            pieces[bunsetsu].append(reading)
        else:
            pieces[bunsetsu].append(surface)
    return [None if broken[i] else "".join(pieces[i]) for i in range(len(texts))]


def clean_file(job):
    path, out_path, name = job
    counts = {"rows": 0, "bunsetsu": 0, "ng": 0, "fixed": 0, "rows_changed": 0, "rows_skipped": 0}
    changes = []
    with open(path, encoding="utf-8") as f, open(out_path, "w", encoding="utf-8") as out:
        for row, line in enumerate(f):
            columns = line.rstrip("\n").split("\t")
            if len(columns) < 2 or columns[0] == "hiragana":
                out.write(line)
                continue
            counts["rows"] += 1
            readings, texts = columns[0].split("|"), columns[1].split("|")
            if len(readings) != len(texts):
                # no bunsetsu to compare: the whole sentence
                readings, texts = ["".join(readings)], ["".join(texts)]
                whole = True
            else:
                whole = False
            bad = [i for i, (r, t) in enumerate(zip(readings, texts)) if not readable(t, r)]
            counts["bunsetsu"] += len(texts)
            counts["ng"] += len(bad)
            skip = False
            if bad:
                other = sudachi_readings(texts)
                for i in bad:
                    if other[i] is not None and other[i] != readings[i] and readable(texts[i], other[i]):
                        changes.append((name, row, i, readings[i], other[i], texts[i]))
                        readings[i] = other[i]
                        counts["fixed"] += 1
                    else:
                        skip = True
                if whole and not skip:
                    skip = True  # a new reading of the whole sentence has no bunsetsu borders to train on
                if any(i for i in bad) or bad:
                    counts["rows_changed"] += 1
            if not whole:
                columns[0] = "|".join(readings)
            while len(columns) < 4:
                columns.append("")
            columns = columns[:4]
            if skip:
                columns.append("skip")
                counts["rows_skipped"] += 1
            out.write("\t".join(columns) + "\n")
    return counts, changes


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("pairs_dir")
    parser.add_argument("out_dir")
    parser.add_argument("--kanjidic", required=True)
    parser.add_argument("--jmdict", required=True)
    parser.add_argument("--checker", required=True, help="the tools directory of Anthy-dicts")
    parser.add_argument("--workers", type=int, default=8)
    parser.add_argument("--limit", type=int, default=0, help="only the first N chunks (to try)")
    args = parser.parse_args()
    os.makedirs(args.out_dir, exist_ok=True)
    names = sorted(n for n in os.listdir(args.pairs_dir) if n.endswith("_pairs.tsv"))
    if args.limit:
        names = names[:args.limit]
    jobs = [(os.path.join(args.pairs_dir, n), os.path.join(args.out_dir, n), n) for n in names]
    total = {}
    with Pool(args.workers, initializer=init, initargs=(args.checker, args.kanjidic, args.jmdict)) as pool, \
            open(os.path.join(args.out_dir, "changes.tsv"), "w", encoding="utf-8") as log:
        for done, (counts, changes) in enumerate(pool.imap_unordered(clean_file, jobs), 1):
            for k, v in counts.items():
                total[k] = total.get(k, 0) + v
            for c in changes:
                log.write("\t".join(str(x) for x in c) + "\n")
            if done % 200 == 0:
                print(f"{done} of {len(jobs)} chunks", flush=True)
    print(" ".join(f"{k}={v}" for k, v in total.items()))
    if total.get("bunsetsu"):
        print(f"ng {100 * total['ng'] / total['bunsetsu']:.2f} % of the bunsetsu; "
              f"fixed {100 * total['fixed'] / max(1, total['ng']):.1f} % of them; "
              f"rows skipped {100 * total['rows_skipped'] / total['rows']:.2f} %")


if __name__ == "__main__":
    main()
