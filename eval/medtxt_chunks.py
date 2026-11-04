#!/usr/bin/env python3
"""
Write the Japanese case reports (MedTxt-CR) and radiology reports (MedTxt-RR) of the Real-MedNLP Test Collection
(NAIST Social Computing Lab, CC BY 4.0, https://sociocom.naist.jp/download/real-mednlp-test-collection/) as chunk
files for the pair workers (pairs/), to test the conversion on medical text with a medical dictionary.

The chunk files have the form of wikipedia/WikiToChunks.java: hiragana TAB text TAB page, the hiragana empty, one
sentence a row, the sentences of one report sharing a page number. Where the corpus marks a word with the right
form of an OCR error (correction="血痰" on 血疾), the right form is taken. Commas and periods are written 、 and 。, as
an input method writes them, and the text is NFKC-normalized (full-width digits and letters to ASCII), as the test
rows of the training data are.

Usage: eval/medtxt_chunks.py "TEST_COLLECTION_DIR" OUT_DIR [--sentences 450]
"""

import argparse
import os
import re
import unicodedata
import xml.etree.ElementTree as ET

FILES = ["MedTxt-CR/MedTxt-CR-JA-training-pub.xml", "MedTxt-RR/MedTxt-RR-JA-training-pub.xml",
         "MedTxt-RR/MedTxt-RR-JA-test-pub.xml"]
SENTENCE = re.compile(r"[^。！？]*[。！？]+[」』）)]*|[^。！？]+$")


def text_of(element: ET.Element) -> str:
    """The text of an element, with the corrected form of the words the corpus corrects."""
    parts = [element.text or ""]
    for child in element:
        parts.append(child.get("correction") or text_of(child))
        parts.append(child.tail or "")
    return "".join(parts)


def normalize(text: str) -> str:
    text = text.replace("，", "、").replace("．", "。")
    return unicodedata.normalize("NFKC", text).replace(",", "、")


def sentences(report: str):
    for line in report.splitlines():
        for s in SENTENCE.findall(normalize(line).strip()):
            s = s.strip()
            if len(s) >= 5:
                yield s


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("collection", help="the directory 'Test collection' of the zip file")
    parser.add_argument("out_dir")
    parser.add_argument("--sentences", type=int, default=450, help="sentences a chunk")
    args = parser.parse_args()
    os.makedirs(args.out_dir, exist_ok=True)
    rows = []
    page = 0
    for name in FILES:
        root = ET.parse(os.path.join(args.collection, name)).getroot()
        for article in root.iter("article"):
            page += 1
            rows += [f"\t{s}\t{page}\n" for s in sentences(text_of(article))]
    for n, start in enumerate(range(0, len(rows), args.sentences)):
        with open(os.path.join(args.out_dir, f"med_{n:07d}.tsv"), "w", encoding="utf-8") as f:
            f.writelines(rows[start:start + args.sentences])
    print(f"{page} reports, {len(rows)} sentences, {(len(rows) + args.sentences - 1) // args.sentences} chunks")


if __name__ == "__main__":
    main()
