#!/usr/bin/env python3
"""
Build the final training/validation/test dataset from JMdict + Aozora pairs.

Input:
  data/jmdict_pairs.tsv   - word-level pairs from JMdict/JMnedict
  data/aozora_pairs.tsv   - sentence-level pairs from Aozora Bunko ruby

Output:
  data/train.tsv
  data/valid.tsv
  data/test.tsv

Split: 98% train / 1% valid / 1% test (shuffle with fixed seed)
"""

import os
import random

DATA_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "data")
SEED = 42
VALID_RATIO = 0.01
TEST_RATIO = 0.01


def load_tsv(path: str) -> list[tuple[str, str]]:
    pairs = []
    if not os.path.exists(path):
        print(f"  Not found, skipping: {path}")
        return pairs
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.rstrip("\n")
            parts = line.split("\t", 1)
            if len(parts) == 2:
                src, tgt = parts
                src = src.strip()
                tgt = tgt.strip()
                if src and tgt and src != tgt:
                    pairs.append((src, tgt))
    return pairs


def deduplicate(pairs: list[tuple[str, str]]) -> list[tuple[str, str]]:
    seen: set[tuple[str, str]] = set()
    result = []
    for p in pairs:
        if p not in seen:
            seen.add(p)
            result.append(p)
    return result


def main():
    print("Loading JMdict pairs...")
    jmdict = load_tsv(os.path.join(DATA_DIR, "jmdict_pairs.tsv"))
    print(f"  JMdict: {len(jmdict)} pairs")

    print("Loading Aozora pairs...")
    aozora = load_tsv(os.path.join(DATA_DIR, "aozora_pairs.tsv"))
    print(f"  Aozora: {len(aozora)} pairs")

    all_pairs = jmdict + aozora
    print(f"Total before dedup: {len(all_pairs)}")

    all_pairs = deduplicate(all_pairs)
    print(f"Total after dedup:  {len(all_pairs)}")

    rng = random.Random(SEED)
    rng.shuffle(all_pairs)

    n = len(all_pairs)
    n_valid = max(1, int(n * VALID_RATIO))
    n_test = max(1, int(n * TEST_RATIO))
    n_train = n - n_valid - n_test

    train = all_pairs[:n_train]
    valid = all_pairs[n_train : n_train + n_valid]
    test = all_pairs[n_train + n_valid :]

    print(f"\nSplit: train={len(train)}, valid={len(valid)}, test={len(test)}")

    for split_name, split_data in [("train", train), ("valid", valid), ("test", test)]:
        path = os.path.join(DATA_DIR, f"{split_name}.tsv")
        with open(path, "w", encoding="utf-8") as f:
            for src, tgt in split_data:
                f.write(f"{src}\t{tgt}\n")
        print(f"Saved {path}")


if __name__ == "__main__":
    main()
