#!/usr/bin/env python3
"""
Copy a FineWeb chunk sample (make_chunks.py) without the pages of some documents.

    python3 drop_documents.py SAMPLE_DIR SCORES_TSV OUT_DIR

SCORES_TSV is the output of corpus-workbench topic-filter run over the --dump-docs file of the sample; the documents
whose decision is "exclude" are dropped. Every page of a chunk is one run of one document (provenance.tsv says which),
so a dropped document takes its pages away; the other pages keep their numbers. OUT_DIR gets the chunk files (a chunk
left with no page is not written), provenance.tsv of the pages kept, and dropped.tsv of the pages dropped.
"""
import os
import sys


def main():
    if len(sys.argv) != 4:
        sys.exit(__doc__)
    sample, scores, out = sys.argv[1:]
    os.makedirs(out, exist_ok=True)

    excluded = set()
    with open(scores, encoding="utf-8") as f:
        next(f)
        for line in f:
            col = line.rstrip("\n").split("\t")
            if col[0] == "exclude":
                excluded.add(col[1])

    drop = set()
    with open(os.path.join(sample, "provenance.tsv"), encoding="utf-8") as prov, \
            open(os.path.join(out, "provenance.tsv"), "w", encoding="utf-8") as kept, \
            open(os.path.join(out, "dropped.tsv"), "w", encoding="utf-8") as dropped:
        header = next(prov)
        kept.write(header)
        dropped.write(header)
        for line in prov:
            col = line.split("\t")
            if col[2] in excluded:
                drop.add((col[0], col[1]))
                dropped.write(line)
            else:
                kept.write(line)

    chunks = sorted(n for n in os.listdir(sample) if n.startswith("fw_") and n.endswith(".tsv"))
    written, rows_kept, rows_dropped = 0, 0, 0
    for name in chunks:
        chunk = name[:-4]
        with open(os.path.join(sample, name), encoding="utf-8") as f:
            header = next(f)
            rows = [line for line in f]
        keep = [r for r in rows if (chunk, r.rstrip("\n").split("\t")[2]) not in drop]
        rows_kept += len(keep)
        rows_dropped += len(rows) - len(keep)
        if keep:
            with open(os.path.join(out, name), "w", encoding="utf-8") as f:
                f.write(header)
                f.writelines(keep)
            written += 1
    print("documents excluded: %d; pages dropped: %d; rows kept %d, dropped %d; chunks written %d of %d"
          % (len(excluded), len(drop), rows_kept, rows_dropped, written, len(chunks)))


if __name__ == "__main__":
    main()
