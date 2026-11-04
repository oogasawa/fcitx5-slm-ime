#!/usr/bin/env python3
"""
Write the sentences of books in Markdown (OCR output, one file of a few pages each, as in doc_Base011) as chunk files
for the pair workers (pairs/), to test the conversion on the text of a field: the text of the book, its readings
written by the language model, and the words of a dictionary of that field as hints.

The chunk files have the form of wikipedia/WikiToChunks.java: hiragana TAB text TAB page, the hiragana empty, one
sentence a row. The page is the number of the file, so that the text before a sentence is the text of the same
pages. Front matter, headings, tables, figure references and the lines of a table of contents (ending in a page
number) are left out, and so are sentences of fewer than 10 characters. The text is NFKC-normalized, commas and
periods are written 、 and 。, as the test rows of the training data are, and the Markdown escapes and the spaces that
OCR puts between Japanese characters are taken out.

The books are not ours to give away: the chunks and the rows made from them stay where the tests run, and go into
no repository and no published data set.

Usage: eval/book_chunks.py OUT_DIR PREFIX BOOK_DIR... [--sentences 450]
"""

import argparse
import glob
import os
import re
import unicodedata

SENTENCE = re.compile(r"[^。！？]*[。！？]+[」』）)]*")
TOC_LINE = re.compile(r"\d+\s*$")
SKIP_LINE = re.compile(r"^\s*(#|\||!\[|<|---|図\s*\d|表\s*\d)")
MARKDOWN_ESCAPE = re.compile(r"\\([\\()\[\]{}~*_.!#+\-])")
# OCR puts spaces between Japanese characters where the lines of the page were laid out
SPACE_IN_JAPANESE = re.compile(r"(?<=[^\x00-\x7f])\s+(?=[^\x00-\x7f])")


def normalize(text: str) -> str:
    text = MARKDOWN_ESCAPE.sub(r"\1", text.replace("，", "、").replace("．", "。"))
    text = unicodedata.normalize("NFKC", text).replace(",", "、")
    return SPACE_IN_JAPANESE.sub("", text)


def sentences(markdown: str):
    body = markdown.split("---", 2)[2] if markdown.startswith("---") else markdown
    paragraph = []
    for line in body.splitlines() + [""]:
        if line.strip() and not SKIP_LINE.match(line) and not TOC_LINE.search(line):
            paragraph.append(normalize(line.strip()))
            continue
        for s in SENTENCE.findall("".join(paragraph)):
            if len(s.strip()) >= 10:
                yield s.strip()
        paragraph = []


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("out_dir")
    parser.add_argument("prefix", help="the start of the names of the chunk files")
    parser.add_argument("books", nargs="+")
    parser.add_argument("--sentences", type=int, default=450)
    args = parser.parse_args()
    os.makedirs(args.out_dir, exist_ok=True)
    rows, page = [], 0
    for book in args.books:
        for path in sorted(glob.glob(os.path.join(book, "**", "*.md"), recursive=True)):
            page += 1
            with open(path, encoding="utf-8") as f:
                rows += [f"\t{s}\t{page}\n" for s in sentences(f.read())]
    for n, start in enumerate(range(0, len(rows), args.sentences)):
        with open(os.path.join(args.out_dir, f"{args.prefix}_{n:07d}.tsv"), "w", encoding="utf-8") as f:
            f.writelines(rows[start:start + args.sentences])
    print(f"{page} files, {len(rows)} sentences, {(len(rows) + args.sentences - 1) // args.sentences} chunks")


if __name__ == "__main__":
    main()
