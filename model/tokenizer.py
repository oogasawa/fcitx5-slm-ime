"""
Character-level tokenizer for kana-kanji conversion.

Vocabulary is built from the training corpus characters.
Special tokens: [PAD]=0, [BOS]=1, [EOS]=2, [UNK]=3
"""

import json
import os
from collections import Counter

PAD_ID = 0
BOS_ID = 1
EOS_ID = 2
UNK_ID = 3
SEP_ID = 4
SPECIAL_TOKENS = ["[PAD]", "[BOS]", "[EOS]", "[UNK]", "[SEP]"]

# In a text this character stands for the [SEP] token. The source of the context-aware model is
#   <text before the cursor> SEP_CHAR <reading>
SEP_CHAR = "\x1f"


class CharTokenizer:
    def __init__(self):
        self.char2id: dict[str, int] = {}
        self.id2char: dict[int, str] = {}

    def build_vocab(self, texts: list[str], min_freq: int = 1) -> None:
        """Build vocabulary from a list of texts."""
        counter: Counter = Counter()
        for text in texts:
            for ch in text:
                if ch != SEP_CHAR:
                    counter[ch] += 1

        self.char2id = {tok: i for i, tok in enumerate(SPECIAL_TOKENS)}
        for ch, freq in sorted(counter.items()):
            if freq >= min_freq and ch not in self.char2id:
                self.char2id[ch] = len(self.char2id)

        self.id2char = {i: ch for ch, i in self.char2id.items()}

    def encode(self, text: str, add_bos: bool = False, add_eos: bool = True) -> list[int]:
        ids = []
        if add_bos:
            ids.append(BOS_ID)
        for ch in text:
            ids.append(SEP_ID if ch == SEP_CHAR else self.char2id.get(ch, UNK_ID))
        if add_eos:
            ids.append(EOS_ID)
        return ids

    def decode(self, ids: list[int], skip_special: bool = True) -> str:
        chars = []
        for i in ids:
            ch = self.id2char.get(i, "")
            if skip_special and i in (PAD_ID, BOS_ID, EOS_ID, UNK_ID):
                if i == EOS_ID:
                    break
                continue
            chars.append(ch)
        return "".join(chars)

    def save(self, path: str) -> None:
        with open(path, "w", encoding="utf-8") as f:
            json.dump(self.char2id, f, ensure_ascii=False, indent=2)

    @classmethod
    def load(cls, path: str) -> "CharTokenizer":
        tok = cls()
        with open(path, encoding="utf-8") as f:
            tok.char2id = json.load(f)
        tok.id2char = {i: ch for ch, i in tok.char2id.items()}
        return tok

    @property
    def vocab_size(self) -> int:
        return len(self.char2id)
