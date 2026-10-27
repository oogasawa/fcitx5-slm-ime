#!/usr/bin/env python3
"""
Build training data for the post-conversion correction model.

Approach:
  1. Take natural Japanese sentences (Aozora, Wikipedia, etc.)
  2. Inject homophone errors using JMdict same-reading pairs
  3. Produce (corrupted_sentence, correct_span) pairs

Input format (TSV, 2 columns):
  sentence_text  (any plain Japanese text file, one sentence per line)

Output: data/correction_pairs.tsv
  corrupted_input TAB correct_output

The model learns:
  Input:  surrounding_left [SEP] corrupted_span [SEP] surrounding_right
  Output: correct_span
"""

import os
import re
import json
import random
from collections import defaultdict

DATA_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "data")
JMDICT_PATH = os.path.join(DATA_DIR, "jmdict_pairs.tsv")
AOZORA_PATH = os.path.join(DATA_DIR, "aozora_pairs.tsv")
OUTPUT_PATH = os.path.join(DATA_DIR, "correction_pairs.tsv")

MIN_SENTENCE_LEN = 8
MAX_SENTENCE_LEN = 120
CORRUPTION_RATE = 0.3   # fraction of eligible spans to corrupt per sentence
MAX_SPAN_LEN = 6        # max chars in a single corrupted span


def load_homophone_map(jmdict_path: str) -> dict[str, list[str]]:
    """
    Build kana → [kanji variants] map from JMdict.
    For each reading, we have a list of kanji that share the same pronunciation.
    These are the candidates for homophone confusion errors.
    """
    kana_to_kanji: dict[str, list[str]] = defaultdict(list)
    with open(jmdict_path, encoding="utf-8") as f:
        for line in f:
            parts = line.rstrip("\n").split("\t", 1)
            if len(parts) == 2:
                kana, kanji = parts
                if kana and kanji and kana != kanji:
                    kana_to_kanji[kana].append(kanji)

    # Only keep readings with 2+ different kanji (true homophones)
    return {k: list(set(v)) for k, v in kana_to_kanji.items() if len(set(v)) >= 2}


def build_kanji_to_homophones(homophone_map: dict[str, list[str]]) -> dict[str, list[str]]:
    """
    Invert: kanji → [other kanji with same reading].
    """
    kanji_to_others: dict[str, list[str]] = defaultdict(list)
    for kana, kanji_list in homophone_map.items():
        for kanji in kanji_list:
            others = [k for k in kanji_list if k != kanji]
            kanji_to_others[kanji].extend(others)
    # Deduplicate
    return {k: list(set(v)) for k, v in kanji_to_others.items()}


def corrupt_sentence(
    sentence: str,
    kanji_to_homophones: dict[str, list[str]],
    rng: random.Random,
) -> list[tuple[str, str, str, str]]:
    """
    Generate (left, corrupted_span, right, correct_span) tuples from one sentence.
    Each tuple represents one corrupted span with its surrounding context.
    """
    results = []
    n = len(sentence)

    # Try all single-character substitutions first
    for i, ch in enumerate(sentence):
        if ch not in kanji_to_homophones:
            continue
        if rng.random() > CORRUPTION_RATE:
            continue

        candidates = kanji_to_homophones[ch]
        wrong = rng.choice(candidates)
        if len(wrong) == 1 and wrong != ch:
            left = sentence[:i]
            right = sentence[i + 1:]
            corrupted = wrong
            correct = ch
            results.append((left, corrupted, right, correct))

    # Also try 2-character spans
    for i in range(n - 1):
        span = sentence[i:i + 2]
        if span not in kanji_to_homophones:
            continue
        if rng.random() > CORRUPTION_RATE:
            continue

        candidates = kanji_to_homophones[span]
        wrong_candidates = [c for c in candidates if c != span and len(c) == 2]
        if not wrong_candidates:
            continue
        wrong = rng.choice(wrong_candidates)
        left = sentence[:i]
        right = sentence[i + 2:]
        results.append((left, wrong, right, span))

    return results


def load_sentences(aozora_path: str) -> list[str]:
    """Load kanji sentences from Aozora pairs."""
    sentences = []
    with open(aozora_path, encoding="utf-8") as f:
        for line in f:
            parts = line.rstrip("\n").split("\t", 1)
            if len(parts) == 2:
                kanji = parts[1]
                if MIN_SENTENCE_LEN <= len(kanji) <= MAX_SENTENCE_LEN:
                    sentences.append(kanji)
    return sentences


def main():
    print("Loading homophone map from JMdict...")
    homophone_map = load_homophone_map(JMDICT_PATH)
    kanji_to_homophones = build_kanji_to_homophones(homophone_map)
    print(f"  {len(kanji_to_homophones)} kanji with homophones")

    print("Loading sentences from Aozora...")
    sentences = load_sentences(AOZORA_PATH)
    print(f"  {len(sentences)} sentences")

    rng = random.Random(42)
    all_samples: list[tuple[str, str, str, str]] = []

    for i, sentence in enumerate(sentences):
        samples = corrupt_sentence(sentence, kanji_to_homophones, rng)
        all_samples.extend(samples)
        if (i + 1) % 10000 == 0:
            print(f"  {i + 1}/{len(sentences)} sentences, "
                  f"{len(all_samples)} samples")

    print(f"\nTotal samples: {len(all_samples)}")

    # Deduplicate
    seen = set()
    unique = []
    for s in all_samples:
        key = s
        if key not in seen:
            seen.add(key)
            unique.append(s)
    all_samples = unique
    print(f"After dedup: {len(all_samples)} samples")

    # Shuffle and split 98/1/1
    rng.shuffle(all_samples)
    n = len(all_samples)
    n_valid = max(1000, n // 100)
    n_test = max(1000, n // 100)
    n_train = n - n_valid - n_test

    splits = {
        "correction_train.tsv": all_samples[:n_train],
        "correction_valid.tsv": all_samples[n_train:n_train + n_valid],
        "correction_test.tsv":  all_samples[n_train + n_valid:],
    }

    for fname, data in splits.items():
        path = os.path.join(DATA_DIR, fname)
        with open(path, "w", encoding="utf-8") as f:
            for left, corrupted, right, correct in data:
                f.write(f"{left}\t{corrupted}\t{right}\t{correct}\n")
        print(f"Wrote {len(data)} samples to {fname}")

    print("\nDone.")
    print("Format: left_ctx TAB corrupted_span TAB right_ctx TAB correct_span")


if __name__ == "__main__":
    main()
