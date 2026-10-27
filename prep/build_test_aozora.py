#!/usr/bin/env python3
"""
Build a balanced test set from Aozora Bunko ruby-annotated texts.

Extracts (hiragana, kanji) pairs where the hiragana reading is fully kana,
then samples pairs by hiragana character length:

  short:  8-12 chars
  medium: 18-22 chars
  long:   28-32 chars

Target: N_PER_BUCKET pairs per length bucket (default 500).

Aozora Bunko was NOT used in training, so all files in aozora_raw/
are available as uncontaminated test data.

Output: data/test_aozora.tsv  (hiragana TAB kanji)
"""

import os
import random
import re
import sys

RAW_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "data", "aozora_raw")
OUTPUT_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "data", "test_aozora.tsv")

N_PER_BUCKET = 500
BUCKETS = {
    "short":  (8,  12),
    "medium": (18, 22),
    "long":   (28, 32),
}
RANDOM_SEED = 42

# Reuse extraction helpers from extract_aozora_ruby.py
RUBY_RE = re.compile(r"[｜]?([^\s《》｜]{1,20})《([^》]+)》")
ANNOTATION_RE = re.compile(r"［＃[^］]*］")
HEADER_SEPARATOR = re.compile(r"[-─━]{40,}")


def katakana_to_hiragana(text: str) -> str:
    return "".join(
        chr(ord(ch) - 0x60) if 0x30A1 <= ord(ch) <= 0x30F6 else ch
        for ch in text
    )


def is_kana_only(text: str) -> bool:
    for ch in text:
        cp = ord(ch)
        if ch in " 　\t\n":
            continue
        if 0x3040 <= cp <= 0x30FF:
            continue
        if cp in (0xFF70, 0x30FB, 0x3001, 0x3002, 0xFF01, 0xFF1F):
            continue
        return False
    return True


def strip_header_footer(text: str) -> str:
    lines = text.splitlines()
    sep_indices = [i for i, l in enumerate(lines) if HEADER_SEPARATOR.match(l.strip())]
    if sep_indices:
        lines = lines[sep_indices[-1] + 1:]
    return "\n".join(lines)


def strip_aozora_markup(text: str) -> str:
    text = ANNOTATION_RE.sub("", text)
    text = RUBY_RE.sub(r"\1", text)
    text = text.replace("｜", "")
    return text


def extract_pairs_from_text(text: str) -> list[tuple[str, str]]:
    """Extract (hiragana, kanji) pairs from one Aozora Bunko text."""
    text = strip_header_footer(text)
    pairs = []

    sentences = re.split(r"[。！？\n]+", text)
    for sentence in sentences:
        sentence = sentence.strip()
        if not sentence or len(sentence) < 5 or len(sentence) > 120:
            continue
        if "《" not in sentence:
            continue
        if sentence.startswith("［＃"):
            continue

        hiragana = ANNOTATION_RE.sub("", sentence)
        hiragana = RUBY_RE.sub(lambda m: katakana_to_hiragana(m.group(2)), hiragana)
        hiragana = hiragana.replace("｜", "")

        kanji = strip_aozora_markup(sentence)

        if not is_kana_only(hiragana):
            continue
        if hiragana == kanji:
            continue
        if "※" in kanji or "（例）" in kanji or "［＃" in kanji:
            continue
        # Sanity: kanji output should not be longer than hiragana input.
        # If kanji > hiragana, the ruby annotation didn't cover the full sentence —
        # non-annotated context appears in the reference but not the input.
        if len(kanji) > len(hiragana):
            continue

        pairs.append((hiragana, kanji))

    return pairs


def bucket_of(hiragana: str) -> str | None:
    """Return the length bucket name, or None if outside all buckets."""
    n = len(hiragana)
    for name, (lo, hi) in BUCKETS.items():
        if lo <= n <= hi:
            return name
    return None


def main() -> None:
    if not os.path.isdir(RAW_DIR):
        print(f"ERROR: {RAW_DIR} not found. Run download_aozora.py first.")
        sys.exit(1)

    txt_files = sorted(f for f in os.listdir(RAW_DIR) if f.endswith(".txt"))
    print(f"Scanning {len(txt_files)} Aozora Bunko files...")

    # Collect all pairs, keyed by bucket
    buckets: dict[str, list[tuple[str, str]]] = {name: [] for name in BUCKETS}
    total_seen = 0

    for i, fname in enumerate(txt_files):
        path = os.path.join(RAW_DIR, fname)
        try:
            with open(path, encoding="utf-8") as f:
                text = f.read()
        except Exception:
            continue

        for pair in extract_pairs_from_text(text):
            total_seen += 1
            name = bucket_of(pair[0])
            if name:
                buckets[name].append(pair)

        if (i + 1) % 500 == 0:
            counts = {k: len(v) for k, v in buckets.items()}
            print(f"  {i+1}/{len(txt_files)} files | pairs seen={total_seen} | {counts}")

    print(f"\nTotal pairs extracted: {total_seen}")
    for name, pairs in buckets.items():
        lo, hi = BUCKETS[name]
        print(f"  {name:6s} ({lo}-{hi} chars): {len(pairs)} pairs")

    # Sample from each bucket
    rng = random.Random(RANDOM_SEED)
    selected: list[tuple[str, str, str]] = []  # (bucket, hiragana, kanji)
    for name, pairs in buckets.items():
        n = min(N_PER_BUCKET, len(pairs))
        sampled = rng.sample(pairs, n)
        for hiragana, kanji in sampled:
            selected.append((name, hiragana, kanji))
        print(f"  Sampled {n} from {name}")

    # Shuffle the final test set
    rng.shuffle(selected)

    with open(OUTPUT_PATH, "w", encoding="utf-8") as f:
        for _bucket, hiragana, kanji in selected:
            f.write(f"{hiragana}\t{kanji}\n")

    print(f"\nTest set saved: {OUTPUT_PATH}  ({len(selected)} pairs)")
    print(f"Bucket breakdown:")
    for name in BUCKETS:
        count = sum(1 for b, _, _ in selected if b == name)
        print(f"  {name}: {count}")


if __name__ == "__main__":
    main()
