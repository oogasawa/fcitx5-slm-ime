#!/usr/bin/env python3
"""
Build context-aware training data from sentence-level (kana, kanji) pairs.

For each sentence pair, we slide a window over the kanji side and create
samples of the form:

  left_context [SEP] kana_span [SEP] right_context  →  kanji_span

where left_context and right_context are the surrounding kanji text,
and kana_span is the hiragana reading of the kanji_span.

Input:  data/aozora_pairs.tsv  (hiragana TAB kanji, sentence-level)
Output: data/context_pairs.tsv (input TAB target)
        input  = left_ctx \t kana_span \t right_ctx
        target = kanji_span

We also keep the original word-level pairs from jmdict_pairs.tsv
(with empty context) to preserve single-word conversion accuracy.
"""

import os
import re
import random

DATA_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "data")
AOZORA_PATH = os.path.join(DATA_DIR, "aozora_pairs.tsv")
JMDICT_PATH = os.path.join(DATA_DIR, "jmdict_pairs.tsv")
OUTPUT_PATH = os.path.join(DATA_DIR, "context_pairs.tsv")

SEP = "\t"  # use tab as SEP between fields within input

# Minimum and maximum span length in characters
MIN_SPAN = 2
MAX_SPAN = 20


def katakana_to_hiragana(text: str) -> str:
    result = []
    for ch in text:
        cp = ord(ch)
        if 0x30A1 <= cp <= 0x30F6:
            result.append(chr(cp - 0x60))
        else:
            result.append(ch)
    return "".join(result)


def sliding_window_samples(
    kana_sent: str,
    kanji_sent: str,
    min_span: int = MIN_SPAN,
    max_span: int = MAX_SPAN,
) -> list[tuple[str, str, str, str]]:
    """
    Generate (left_ctx, kana_span, right_ctx, kanji_span) tuples.

    We align kana and kanji by assuming character-level correspondence
    only works for fully-kana sentences.  Since our aozora pairs have the
    property that kana_sent is the full reading and kanji_sent is the
    original text, we sample contiguous character spans from the kanji side
    and use the corresponding positions from the kana side.

    Both strings must have the same length after the alignment step.
    For simplicity we only process pairs where len(kana) == len(kanji)
    (i.e. already equal-length aligned pairs).  Mismatched-length pairs
    are skipped — they require a proper alignment which is out of scope here.
    """
    samples = []

    n = len(kanji_sent)
    if n != len(kana_sent):
        return samples  # skip unaligned pairs

    # Slide window of varying sizes
    for span_len in range(min_span, min(max_span, n) + 1):
        for start in range(0, n - span_len + 1):
            end = start + span_len
            left_kanji = kanji_sent[:start]
            kanji_span = kanji_sent[start:end]
            right_kanji = kanji_sent[end:]
            kana_span = kana_sent[start:end]

            # Skip spans that are pure hiragana/katakana (no conversion needed)
            if all(
                0x3040 <= ord(c) <= 0x30FF or c in "ー・、。"
                for c in kanji_span
            ):
                continue

            samples.append((left_kanji, kana_span, right_kanji, kanji_span))

    return samples


def main():
    all_samples: list[tuple[str, str, str, str]] = []

    # 1. Aozora sentence-level pairs → context samples
    print("Processing Aozora pairs...")
    aozora_count = 0
    if os.path.exists(AOZORA_PATH):
        with open(AOZORA_PATH, encoding="utf-8") as f:
            for line in f:
                parts = line.rstrip("\n").split("\t", 1)
                if len(parts) != 2:
                    continue
                kana, kanji = parts
                samples = sliding_window_samples(kana, kanji)
                all_samples.extend(samples)
                aozora_count += 1
                if aozora_count % 10000 == 0:
                    print(f"  {aozora_count} sentences, {len(all_samples)} samples")

    print(f"Aozora: {aozora_count} sentences → {len(all_samples)} context samples")

    # 2. JMdict word-level pairs → no-context samples (empty left/right)
    print("Processing JMdict pairs...")
    jmdict_count = 0
    if os.path.exists(JMDICT_PATH):
        with open(JMDICT_PATH, encoding="utf-8") as f:
            for line in f:
                parts = line.rstrip("\n").split("\t", 1)
                if len(parts) != 2:
                    continue
                kana, kanji = parts
                if not kana or not kanji or kana == kanji:
                    continue
                # Empty context = pure word-level conversion
                all_samples.append(("", kana, "", kanji))
                jmdict_count += 1

    print(f"JMdict: {jmdict_count} word pairs (no context)")

    # Deduplicate
    seen = set()
    unique = []
    for s in all_samples:
        key = (s[0], s[1], s[2], s[3])
        if key not in seen:
            seen.add(key)
            unique.append(s)
    all_samples = unique
    print(f"After dedup: {len(all_samples)} total samples")

    # Shuffle and split 98/1/1
    random.seed(42)
    random.shuffle(all_samples)
    n = len(all_samples)
    n_valid = max(1000, n // 100)
    n_test = max(1000, n // 100)
    n_train = n - n_valid - n_test

    splits = {
        "context_train.tsv": all_samples[:n_train],
        "context_valid.tsv": all_samples[n_train:n_train + n_valid],
        "context_test.tsv":  all_samples[n_train + n_valid:],
    }

    for fname, data in splits.items():
        path = os.path.join(DATA_DIR, fname)
        with open(path, "w", encoding="utf-8") as f:
            for left, kana, right, kanji in data:
                # Format: left_ctx \t kana_span \t right_ctx \t kanji_span
                f.write(f"{left}\t{kana}\t{right}\t{kanji}\n")
        print(f"Wrote {len(data)} samples to {fname}")

    print("\nDone.")
    print("Input format:  left_ctx TAB kana_span TAB right_ctx")
    print("Target format: kanji_span")


if __name__ == "__main__":
    main()
