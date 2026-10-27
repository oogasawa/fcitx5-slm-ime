#!/usr/bin/env python3
"""
Extract (hiragana, kanji) sentence pairs from Aozora Bunko ruby-annotated texts.

Aozora Bunko ruby format:
  漢字《かんじ》          - basic ruby
  ｜送り仮名《おくりがな》 - with scope marker

Algorithm:
  1. For each sentence, find all ruby annotations
  2. Replace 漢字《よみ》 with a placeholder, record the mapping
  3. Build hiragana version by substituting kanji spans with their readings
  4. Pair: (hiragana_sentence, original_sentence_cleaned)

Input:  data/aozora_raw/*.txt   (downloaded by download_aozora.py)
Output: data/aozora_pairs.tsv   hiragana TAB kanji
"""

import os
import re
import unicodedata

RAW_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "data", "aozora_raw")
OUTPUT_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "data", "aozora_pairs.tsv")

# Ruby pattern: optional scope marker ｜, then kanji span, then 《reading》
RUBY_RE = re.compile(r"[｜]?([^\s《》｜]{1,20})《([^》]+)》")

# Aozora markup to strip: annotation tags like ［＃...］, ruby scope ｜
ANNOTATION_RE = re.compile(r"［＃[^］]*］")
HEADER_SEPARATOR = re.compile(r"[-─━]{40,}")  # ASCII or full-width dashes


def strip_aozora_markup(text: str) -> str:
    """Remove Aozora-specific markup, keeping the plain kanji text."""
    text = ANNOTATION_RE.sub("", text)
    # Remove ruby readings (keep kanji, discard 《よみ》)
    text = RUBY_RE.sub(r"\1", text)
    # Remove scope markers
    text = text.replace("｜", "")
    return text


def katakana_to_hiragana(text: str) -> str:
    result = []
    for ch in text:
        cp = ord(ch)
        if 0x30A1 <= cp <= 0x30F6:
            result.append(chr(cp - 0x60))
        else:
            result.append(ch)
    return "".join(result)


def is_kana_only(text: str) -> bool:
    """Return True if text is entirely hiragana/katakana/spaces/punctuation."""
    for ch in text:
        cp = ord(ch)
        if ch in " 　\t\n":
            continue
        # Allow hiragana, katakana, prolonged sound, middle dot, Japanese punctuation
        if 0x3040 <= cp <= 0x30FF:
            continue
        if cp in (0xFF70, 0x30FB, 0x3001, 0x3002, 0xFF01, 0xFF1F):
            continue
        return False
    return True


def strip_header_footer(text: str) -> str:
    """Remove Aozora Bunko header and footer.
    Format: title/author, then separator, then format guide, then separator, then body text.
    Body text is everything after the LAST separator line.
    """
    lines = text.splitlines()
    sep_indices = [i for i, l in enumerate(lines) if HEADER_SEPARATOR.match(l.strip())]
    if sep_indices:
        # Body starts after the last separator
        lines = lines[sep_indices[-1] + 1 :]
    return "\n".join(lines)


def extract_pairs_from_text(text: str) -> list[tuple[str, str]]:
    """
    Extract (hiragana, kanji) pairs from a single Aozora Bunko text.
    Each pair is one sentence.
    """
    text = strip_header_footer(text)
    pairs = []

    # Split into sentences on 。！？and newlines
    sentences = re.split(r"[。！？\n]+", text)

    for sentence in sentences:
        sentence = sentence.strip()
        if not sentence:
            continue
        # Skip very short or very long sentences
        if len(sentence) < 5 or len(sentence) > 120:
            continue
        # Must contain at least one ruby annotation to be useful
        if "《" not in sentence:
            continue
        # Skip if it's annotation-only
        if sentence.startswith("［＃"):
            continue

        # Build hiragana version by replacing 漢字《よみ》 spans
        hiragana_sentence = sentence
        # Remove annotation markup first
        hiragana_sentence = ANNOTATION_RE.sub("", hiragana_sentence)

        def replace_ruby(m: re.Match) -> str:
            reading = katakana_to_hiragana(m.group(2))
            return reading

        hiragana_sentence = RUBY_RE.sub(replace_ruby, hiragana_sentence)
        hiragana_sentence = hiragana_sentence.replace("｜", "")

        # Build clean kanji version
        kanji_sentence = strip_aozora_markup(sentence)

        # Validate: hiragana side should be mostly kana after conversion
        # (some kanji may remain if they had no ruby — that's OK, skip those)
        # We want sentences where hiragana_sentence is fully kana
        if not is_kana_only(hiragana_sentence):
            continue

        # Skip if both sides are identical (no conversion happened)
        if hiragana_sentence == kanji_sentence:
            continue

        # Skip if kanji side contains placeholders or example markers
        if "※" in kanji_sentence or "（例）" in kanji_sentence:
            continue

        # Skip if kanji side contains annotation brackets
        if "［＃" in kanji_sentence:
            continue

        pairs.append((hiragana_sentence, kanji_sentence))

    return pairs


def main():
    if not os.path.isdir(RAW_DIR):
        print(f"Directory not found: {RAW_DIR}")
        print("Run download_aozora.py first.")
        return

    txt_files = [f for f in os.listdir(RAW_DIR) if f.endswith(".txt")]
    if not txt_files:
        print(f"No .txt files found in {RAW_DIR}")
        return

    print(f"Processing {len(txt_files)} Aozora Bunko files...")

    total_pairs = 0
    with open(OUTPUT_PATH, "w", encoding="utf-8") as out:
        for i, fname in enumerate(sorted(txt_files)):
            path = os.path.join(RAW_DIR, fname)
            try:
                with open(path, encoding="utf-8") as f:
                    text = f.read()
            except Exception:
                continue

            pairs = extract_pairs_from_text(text)
            for hiragana, kanji in pairs:
                out.write(f"{hiragana}\t{kanji}\n")
            total_pairs += len(pairs)

            if (i + 1) % 500 == 0:
                print(f"  {i+1}/{len(txt_files)} files, {total_pairs} pairs so far")

    print(f"\nDone. Total pairs: {total_pairs}")
    print(f"Saved to {OUTPUT_PATH}")


if __name__ == "__main__":
    main()
