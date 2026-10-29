#!/usr/bin/env python3
"""
Take a sample of the Japanese part of FineWeb-2 and write it as chunk files for the kana-kanji pair workers.

The chunk files have the form that wikipedia/WikiToChunks.java writes, so that the same workers can make the pairs:
    hiragana <TAB> kanji <TAB> page          (hiragana is empty; the workers fill it)
Every row is one sentence. The sentences of one run share a page number; a run is a stretch of neighbouring
sentences of one document, so that the text before a sentence is really the text before it.

The sample is spread over all the files of the data set: from every file, a few row groups (1,000 documents each)
are read at random positions, and only the text column of those row groups is downloaded. From every document one run
of up to --run-length neighbouring sentences is taken. A sentence is used only if it looks like ordinary
Japanese prose (see good_sentence); a run ends at the first sentence that is not.

Usage: make_chunks.py OUT_DIR [--row-groups-per-file 3] [--seed 1] [--run-length 10] [--chunk-sentences 450]
                      [--target-sentences 3300000] [--max-files N]
Besides the chunk files, OUT_DIR/provenance.tsv tells for every run: chunk, page, document id, URL, dump.
"""

import argparse
import hashlib
import os
import random
import re
import sys
import time

import pyarrow.parquet as pq
from huggingface_hub import HfFileSystem

REPO_DIR = "datasets/HuggingFaceFW/fineweb-2/data/jpn_Jpan/train"
SENTENCE = re.compile(r"[^。！？]*[。！？]+[」』）)]*|[^。！？]+$")
URLISH = re.compile(r"https?:|www\.|@|\.com|\.jp/|©|&#|&[a-z]+;")
REPEAT = re.compile(r"(.)\1{4,}")
# Words of the spam that the data set still has. Only obvious ones; this is not a content filter of any other kind.
BLOCKED = ("出会い系", "カジノ", "アダルト", "エロ", "風俗", "無修正", "セフレ", "パチンコ", "消費者金融", "キャッシング",
           "オンラインカジノ", "副業で", "稼げる", "ログイン", "会員登録", "お問い合わせ", "クリックして", "この記事は")
ENDINGS = "。！？」』）)"


def kind_counts(s: str):
    hira = kata = kanji = ascii_alnum = other = 0
    for c in s:
        o = ord(c)
        if 0x3041 <= o <= 0x309F:
            hira += 1
        elif 0x30A0 <= o <= 0x30FF:
            kata += 1
        elif 0x4E00 <= o <= 0x9FFF or 0x3400 <= o <= 0x4DBF or c in "々〆":
            kanji += 1
        elif c.isascii() and c.isalnum():
            ascii_alnum += 1
        else:
            other += 1
    return hira, kata, kanji, ascii_alnum, other


def good_sentence(s: str):
    """Returns None if the sentence is usable, otherwise the reason it is not."""
    n = len(s)
    if n < 8 or n > 100:
        return "length"
    if "|" in s or "\t" in s:
        return "separator"
    if URLISH.search(s):
        return "url"
    if s[-1] not in ENDINGS:
        return "no-ending"
    if any(ord(c) > 0xFFFF for c in s):
        return "outside-bmp"
    if REPEAT.search(s):
        return "repeat"
    hira, kata, kanji, ascii_alnum, other = kind_counts(s)
    if hira < 0.2 * n:
        return "few-hiragana"
    if ascii_alnum > 0.2 * n:
        return "ascii"
    if (hira + kata + kanji) < 0.7 * n:
        return "not-japanese"
    if any(w in s for w in BLOCKED):
        return "blocked"
    return None


def sentences_of(text: str):
    """The sentences of a document in order; a paragraph break ends a sentence."""
    for para in text.split("\n"):
        para = para.strip()
        if not para:
            continue
        for m in SENTENCE.finditer(para):
            s = m.group(0).strip()
            if s:
                yield s


def pick_run(sentences, rng, run_length, reasons):
    """One run of neighbouring usable sentences (at least 3) from the document, or None."""
    runs, current = [], []
    for s in sentences:
        why = good_sentence(s)
        if why is None:
            current.append(s)
        else:
            reasons[why] = reasons.get(why, 0) + 1
            if len(current) >= 3:
                runs.append(current)
            current = []
    if len(current) >= 3:
        runs.append(current)
    if not runs:
        return None
    run = rng.choice(runs)
    if len(run) > run_length:
        start = rng.randrange(0, len(run) - run_length + 1)
        run = run[start:start + run_length]
    return run


class ChunkWriter:
    def __init__(self, out_dir, chunk_sentences):
        self.out_dir, self.limit = out_dir, chunk_sentences
        self.index, self.count, self.page, self.out = 0, 0, 0, None
        self.total_sentences, self.total_runs = 0, 0
        self.prov = open(os.path.join(out_dir, "provenance.tsv"), "w", encoding="utf-8")
        self.prov.write("chunk\tpage\tdocument_id\turl\tdump\n")

    def _open(self):
        if self.out:
            self.out.close()
        self.name = "fw_%07d" % self.index
        self.index += 1
        self.out = open(os.path.join(self.out_dir, self.name + ".tsv"), "w", encoding="utf-8")
        self.out.write("hiragana\tkanji\tpage\n")
        self.count, self.page = 0, 0

    def write_run(self, run, doc_id, url, dump):
        if self.out is None or self.count >= self.limit:
            self._open()
        self.page += 1
        for s in run:
            self.out.write("\t%s\t%d\n" % (s, self.page))
        self.count += len(run)
        self.total_sentences += len(run)
        self.total_runs += 1
        self.prov.write("%s\t%d\t%s\t%s\t%s\n" % (self.name, self.page, doc_id, url, dump))

    def close(self):
        if self.out:
            self.out.close()
        self.prov.close()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("out_dir")
    ap.add_argument("--row-groups-per-file", type=int, default=3)
    ap.add_argument("--seed", type=int, default=1)
    ap.add_argument("--run-length", type=int, default=10)
    ap.add_argument("--chunk-sentences", type=int, default=450)
    ap.add_argument("--target-sentences", type=int, default=3300000)
    ap.add_argument("--max-files", type=int, default=0)
    args = ap.parse_args()
    os.makedirs(args.out_dir, exist_ok=True)

    fs = HfFileSystem()
    files = sorted(p for p in fs.ls(REPO_DIR, detail=False) if p.endswith(".parquet"))
    if args.max_files:
        files = files[:args.max_files]
    writer = ChunkWriter(args.out_dir, args.chunk_sentences)
    seen = set()
    reasons, docs_seen, docs_used, dup_runs = {}, 0, 0, 0
    started = time.time()
    for fi, path in enumerate(files):
        rng = random.Random(args.seed * 1000003 + fi)
        try:
            with fs.open(path, "rb") as f:
                pf = pq.ParquetFile(f)
                groups = rng.sample(range(pf.metadata.num_row_groups),
                                    min(args.row_groups_per_file, pf.metadata.num_row_groups))
                for g in groups:
                    t = pf.read_row_group(g, columns=["text", "id", "url", "dump"])
                    texts, ids, urls, dumps = (t.column(c).to_pylist() for c in ("text", "id", "url", "dump"))
                    order = list(range(len(texts)))
                    rng.shuffle(order)
                    for i in order:
                        docs_seen += 1
                        run = pick_run(list(sentences_of(texts[i])), rng, args.run_length, reasons)
                        if run is None:
                            continue
                        hashes = [hashlib.md5(s.encode("utf-8")).digest()[:8] for s in run]
                        if sum(h in seen for h in hashes) > 0:
                            dup_runs += 1
                            continue
                        seen.update(hashes)
                        docs_used += 1
                        writer.write_run(run, ids[i], urls[i].replace("\t", " "), dumps[i])
        except Exception as e:  # a file that cannot be read is skipped; the next file goes on
            print("skip %s: %s" % (path.split("/")[-1], e), file=sys.stderr, flush=True)
        print("file %d/%d %s: sentences=%d runs=%d chunks=%d elapsed=%.0fs" % (
            fi + 1, len(files), path.split("/")[-1], writer.total_sentences, writer.total_runs, writer.index,
            time.time() - started), flush=True)
        if writer.total_sentences >= args.target_sentences:
            break
    writer.close()
    print("documents seen=%d, runs taken=%d, runs dropped as repeats=%d" % (docs_seen, docs_used, dup_runs))
    print("sentences=%d chunks=%d" % (writer.total_sentences, writer.index))
    print("sentences dropped, by reason:", dict(sorted(reasons.items(), key=lambda kv: -kv[1])))


if __name__ == "__main__":
    main()
