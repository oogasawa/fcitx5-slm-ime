#!/usr/bin/env python3
"""
Download JMdict and JMnedict, extract (reading, kanji) training pairs.

JMdict format (XML):
  <entry>
    <k_ele><keb>日本語</keb></k_ele>      <- kanji form
    <r_ele><reb>にほんご</reb></r_ele>    <- reading (hiragana/katakana)
    ...
  </entry>

Output: data/jmdict_pairs.tsv
  Format: <hiragana>\t<kanji>
  Example: にほんご\t日本語
"""

import gzip
import os
import re
import urllib.request

JMDICT_URL = "http://ftp.edrdg.org/pub/Nihongo/JMdict_e.gz"
JMNEDICT_URL = "http://ftp.edrdg.org/pub/Nihongo/JMnedict.xml.gz"
OUTPUT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "data")


def download_gz(url: str) -> bytes:
    print(f"Downloading {url} ...")
    with urllib.request.urlopen(url, timeout=60) as resp:
        compressed = resp.read()
    return gzip.decompress(compressed)


def is_hiragana_or_katakana(text: str) -> bool:
    """Return True if text contains only kana (hiragana/katakana/prolonged sound mark)."""
    for ch in text:
        cp = ord(ch)
        # hiragana: U+3041-U+3096, katakana: U+30A1-U+30F6, prolonged: U+30FC
        # small kana, voiced marks etc. included
        if not (0x3040 <= cp <= 0x30FF or cp == 0xFF70):
            return False
    return True


def katakana_to_hiragana(text: str) -> str:
    """Convert katakana to hiragana."""
    result = []
    for ch in text:
        cp = ord(ch)
        if 0x30A1 <= cp <= 0x30F6:
            result.append(chr(cp - 0x60))
        else:
            result.append(ch)
    return "".join(result)


def contains_kanji(text: str) -> bool:
    """Return True if text contains at least one kanji character."""
    for ch in text:
        cp = ord(ch)
        if 0x4E00 <= cp <= 0x9FFF or 0x3400 <= cp <= 0x4DBF:
            return True
    return False


def extract_pairs_from_xml(xml_bytes: bytes) -> list[tuple[str, str]]:
    """
    Extract (hiragana, kanji) pairs from JMdict/JMnedict XML using regex.
    Avoids XML parser entirely to sidestep DOCTYPE entity reference issues.
    """
    text = xml_bytes.decode("utf-8")
    pairs = []

    # Extract each <entry>...</entry> block
    entry_re = re.compile(r"<entry>(.*?)</entry>", re.DOTALL)
    keb_re = re.compile(r"<keb>([^<]+)</keb>")
    reb_re = re.compile(r"<reb>([^<]+)</reb>")

    for m in entry_re.finditer(text):
        block = m.group(1)
        kanji_forms = keb_re.findall(block)
        reading_forms = reb_re.findall(block)

        if not kanji_forms or not reading_forms:
            continue

        for kanji in kanji_forms:
            kanji = kanji.strip()
            if not contains_kanji(kanji):
                continue
            for reading in reading_forms:
                reading = reading.strip()
                if not is_hiragana_or_katakana(reading):
                    continue
                hiragana = katakana_to_hiragana(reading)
                pairs.append((hiragana, kanji))

    return pairs


# Keep old names as aliases for clarity at call sites
def extract_jmdict_pairs(xml_bytes: bytes) -> list[tuple[str, str]]:
    return extract_pairs_from_xml(xml_bytes)


def extract_jmnedict_pairs(xml_bytes: bytes) -> list[tuple[str, str]]:
    return extract_pairs_from_xml(xml_bytes)


def main():
    out_path = os.path.join(OUTPUT_DIR, "jmdict_pairs.tsv")

    if os.path.exists(out_path):
        print(f"{out_path} already exists, skipping download.")
        return

    all_pairs: list[tuple[str, str]] = []

    # JMdict (common words)
    try:
        xml_bytes = download_gz(JMDICT_URL)
        pairs = extract_jmdict_pairs(xml_bytes)
        print(f"JMdict: {len(pairs)} pairs extracted")
        all_pairs.extend(pairs)
    except Exception as e:
        print(f"JMdict download failed: {e}")

    # JMnedict (proper nouns: place names, person names, etc.)
    try:
        xml_bytes = download_gz(JMNEDICT_URL)
        pairs = extract_jmnedict_pairs(xml_bytes)
        print(f"JMnedict: {len(pairs)} pairs extracted")
        all_pairs.extend(pairs)
    except Exception as e:
        print(f"JMnedict download failed: {e}")

    # Deduplicate
    seen: set[tuple[str, str]] = set()
    unique_pairs = []
    for pair in all_pairs:
        if pair not in seen:
            seen.add(pair)
            unique_pairs.append(pair)

    print(f"Total unique pairs: {len(unique_pairs)}")

    with open(out_path, "w", encoding="utf-8") as f:
        for hiragana, kanji in unique_pairs:
            f.write(f"{hiragana}\t{kanji}\n")

    print(f"Saved to {out_path}")


if __name__ == "__main__":
    main()
