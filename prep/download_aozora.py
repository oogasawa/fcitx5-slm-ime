#!/usr/bin/env python3
"""
Download Aozora Bunko texts with ruby (furigana) annotations.

Aozora Bunko uses the format:
  漢字《かんじ》  - basic ruby
  ｜漢字《かんじ》 - with explicit scope marker

This script:
1. Downloads the catalog CSV from aozora.gr.jp
2. Downloads text files (which contain ruby annotations)
3. Saves raw .txt files for later ruby extraction
"""

import csv
import io
import os
import re
import time
import urllib.request
import zipfile

CATALOG_URL = "https://www.aozora.gr.jp/index_pages/list_person_all_extended_utf8.zip"
OUTPUT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "data", "aozora_raw")
MAX_WORKS = 5000  # Limit for initial download; None = all (~17,000 works)
SLEEP_SEC = 0.5   # Be polite to the server


def download_catalog() -> list[dict]:
    """Download and parse the Aozora Bunko catalog CSV."""
    print("Downloading catalog...")
    with urllib.request.urlopen(CATALOG_URL) as resp:
        data = resp.read()
    with zipfile.ZipFile(io.BytesIO(data)) as zf:
        csv_name = [n for n in zf.namelist() if n.endswith(".csv")][0]
        with zf.open(csv_name) as f:
            content = f.read().decode("utf-8-sig")
    reader = csv.DictReader(io.StringIO(content))
    rows = list(reader)
    print(f"Catalog: {len(rows)} entries")
    return rows


def get_text_url(row: dict) -> str | None:
    """Extract the UTF-8 text file URL from a catalog row."""
    # Prefer UTF-8 text zip over Shift-JIS
    url = row.get("テキストファイルURL") or row.get("XHTML/HTMLファイルURL")
    if not url:
        return None
    url = url.strip()
    if not url:
        return None
    return url


def download_text(url: str) -> str | None:
    """Download and extract text from a zip URL. Returns raw text or None."""
    try:
        with urllib.request.urlopen(url, timeout=15) as resp:
            data = resp.read()
        if url.endswith(".zip"):
            with zipfile.ZipFile(io.BytesIO(data)) as zf:
                # Find .txt file inside the zip
                txt_names = [n for n in zf.namelist() if n.endswith(".txt")]
                if not txt_names:
                    return None
                with zf.open(txt_names[0]) as f:
                    raw = f.read()
            # Try UTF-8 first, then Shift-JIS (common in Aozora)
            for enc in ("utf-8", "shift_jis", "cp932"):
                try:
                    return raw.decode(enc)
                except UnicodeDecodeError:
                    continue
        return None
    except Exception as e:
        print(f"  Error: {e}")
        return None


def main():
    os.makedirs(OUTPUT_DIR, exist_ok=True)
    rows = download_catalog()

    if MAX_WORKS:
        rows = rows[:MAX_WORKS]

    downloaded = 0
    skipped = 0
    for i, row in enumerate(rows):
        work_id = row.get("作品ID", str(i))
        out_path = os.path.join(OUTPUT_DIR, f"{work_id}.txt")
        if os.path.exists(out_path):
            skipped += 1
            continue

        url = get_text_url(row)
        if not url:
            continue

        title = row.get("作品名", "?")
        author = row.get("姓") or ""
        print(f"[{i+1}/{len(rows)}] {author}「{title}」 ...")

        text = download_text(url)
        if text:
            with open(out_path, "w", encoding="utf-8") as f:
                f.write(text)
            downloaded += 1
        time.sleep(SLEEP_SEC)

    print(f"\nDone. Downloaded: {downloaded}, Skipped (already exist): {skipped}")


if __name__ == "__main__":
    main()
