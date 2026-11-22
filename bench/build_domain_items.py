#!/usr/bin/env python3
"""
Build AJIMEE-Bench style items, one set per field, from the held-out Wikipedia test pairs.

    python3 build_domain_items.py PAGES_DOMAIN_TSV PAIRS_DIR OUT_DIR [PER_FIELD]

PAGES_DOMAIN_TSV has the rows (chunk, page, title, field) of the test pages; PAIRS_DIR holds the files
CHUNK_pairs.tsv (hiragana, kanji, page, source; bunsetsu separated by '|'). From every sentence of a page one run of
consecutive bunsetsu is taken: its reading becomes the input (in katakana), its kanji the expected output, and the text
before it on the page (at most 100 characters) the context. A run must read in hiragana only and hold a kanji, so that
something is to be converted. At most PER_FIELD items (default 500) per field are kept, drawn with a fixed seed.
OUT_DIR gets FIELD.json for each field.
"""
import json
import os
import random
import sys
import zlib

MIN_READING, MAX_READING, CONTEXT = 8, 30, 100


def is_reading(s):
    return all("ぁ" <= c <= "ゖ" or c in "ー、。" for c in s)


def has_kanji(s):
    return any("一" <= c <= "鿿" or c == "々" for c in s)


def item_of(hira, kanji, before, rnd):
    """One run of the sentence as (input, expected, context), or None when no run fits."""
    h, k = hira.split("|"), kanji.split("|")
    if len(h) != len(k):
        return None
    starts = list(range(len(h)))
    rnd.shuffle(starts)
    for s in starts:
        reading, e = "", s
        while e < len(h) and len(reading) < MIN_READING:
            reading += h[e]
            e += 1
        if not MIN_READING <= len(reading) <= MAX_READING or not is_reading(reading):
            continue
        expected = "".join(k[s:e])
        if not has_kanji(expected):
            continue
        context = (before + "".join(k[:s]))[-CONTEXT:]
        kata = "".join(chr(ord(c) + 0x60) if "ぁ" <= c <= "ゖ" else c for c in reading)
        return kata, expected, context
    return None


def main():
    if len(sys.argv) not in (4, 5):
        sys.exit(__doc__)
    pages_tsv, pairs_dir, out = sys.argv[1:4]
    per_field = int(sys.argv[4]) if len(sys.argv) == 5 else 500
    os.makedirs(out, exist_ok=True)

    field_of = {}
    with open(pages_tsv, encoding="utf-8") as f:
        for line in f:
            chunk, page, _title, field = line.rstrip("\n").split("\t")
            field_of[(chunk, page)] = field

    found = {}
    for chunk in sorted({c for c, _ in field_of}):
        path = os.path.join(pairs_dir, chunk + "_pairs.tsv")
        if not os.path.exists(path):
            print("missing", path, file=sys.stderr)
            continue
        before = {}
        with open(path, encoding="utf-8") as f:
            next(f)
            for n, line in enumerate(f):
                col = line.rstrip("\n").split("\t")
                if len(col) < 3 or (chunk, col[2]) not in field_of:
                    continue
                page = col[2]
                rnd = random.Random(zlib.crc32(("%s/%s/%d" % (chunk, page, n)).encode()))
                it = item_of(col[0], col[1], before.get(page, ""), rnd)
                if it:
                    found.setdefault(field_of[(chunk, page)], []).append(it)
                before[page] = (before.get(page, "") + col[1].replace("|", ""))[-CONTEXT:]

    for field, items in sorted(found.items()):
        random.Random(zlib.crc32(field.encode())).shuffle(items)
        rows = [{"index": i, "context_text": c, "input": kata, "expected_output": [e], "original_text": e}
                for i, (kata, e, c) in enumerate(items[:per_field])]
        with open(os.path.join(out, field + ".json"), "w", encoding="utf-8") as f:
            json.dump(rows, f, ensure_ascii=False, indent=1)
        print("%s\t%d of %d" % (field, len(rows), len(items)))


if __name__ == "__main__":
    main()
