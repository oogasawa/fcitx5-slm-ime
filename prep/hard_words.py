#!/usr/bin/env python3
"""
Make the table of hard words from JMdict: the words a small model can not learn from the sentences it reads,
because they are seen too seldom, and which the engine looks up in a dictionary instead.

A word is hard when it is
  - a four-character idiom (yojijukugo, marked so in JMdict), or
  - written in kanji only, has a reading of 4 kana or more, and is not marked as a common word in JMdict (no
    ke_pri or re_pri in its entry).
Spellings and readings that JMdict marks as search-only, irregular, out-dated, rare or ateji are left out. Other
than the idioms, these words are left out too, because a hint that is nearly always wrong teaches the model to pass
hints by, and the model writes the words of the last kind without help:
  - those read as a common word is (遊猟 as 有料, 大宗 as 体操),
  - those that JMdict says are usually written in kana, or that are read in katakana or with a long vowel mark
    (剣橋 for ケンブリッジ, 蘇丹 for スーダン),
  - those made of common words, prefixes and suffixes, with their readings one after another (宗教都市 from 宗教 and
    都市, 情報化 from 情報 and the suffix 化): their readings are found where the words stand next to each other in
    other phrases (しゅうきょうとして, じょうほうから).

Usage: prep/hard_words.py JMdict_e[.gz] > hard_words.tsv
Output: reading TAB word TAB kind (yoji or rare) TAB field, sorted by reading. The field is the first of the fields of
the entry in JMdict (medicine, law, anatomy, computing...), or empty: prep/add_hints.py tells the model the field of
a hint, so that it can match it with what the text is about.
"""

import argparse
import functools
import gzip
import re
import sys
import xml.etree.ElementTree as ET

# The texts of the JMdict entities, as the XML parser expands them
LEFT_OUT_SPELLINGS = {"search-only kanji form", "word containing irregular kanji usage",
                      "word containing out-dated kanji or kanji usage", "ateji (phonetic) reading",
                      "rarely used kanji form"}
LEFT_OUT_READINGS = {"search-only kana form", "irregular kana usage", "out-dated or obsolete kana usage"}
YOJIJUKUGO = "yojijukugo"
USUALLY_KANA = "word usually written using kana alone"
AFFIXES = {"suffix", "prefix", "noun, used as a suffix", "noun, used as a prefix"}

KANJI_ONLY = re.compile(r"[㐀-䶿一-鿿豈-﫿々]+")
KANA_ONLY = re.compile(r"[ぁ-ゖァ-ヶー]+")


def to_hiragana(kana: str) -> str:
    return "".join(chr(ord(c) - 0x60) if 0x30A1 <= ord(c) <= 0x30F6 else c for c in kana)


def is_common(entry: ET.Element) -> bool:
    return entry.find("k_ele/ke_pri") is not None or entry.find("r_ele/re_pri") is not None


def pairs(entry: ET.Element, spellings: list[str]):
    """The (reading in hiragana, spelling, reading as written) of an entry, as JMdict allows them."""
    for r in entry.findall("r_ele"):
        reading = r.findtext("reb")
        if (r.find("re_nokanji") is not None or LEFT_OUT_READINGS & {i.text for i in r.findall("re_inf")}
                or not KANA_ONLY.fullmatch(reading)):
            continue
        restricted = {x.text for x in r.findall("re_restr")}
        for word in spellings:
            if not restricted or word in restricted:
                yield to_hiragana(reading), word, reading


def spellings_of(entry: ET.Element) -> list[str]:
    return [k.findtext("keb") for k in entry.findall("k_ele")
            if not LEFT_OUT_SPELLINGS & {i.text for i in k.findall("ke_inf")}]


def common_words(entry: ET.Element) -> set[tuple[str, str]]:
    """The (reading, spelling) of a common word (one with ke_pri or re_pri), or of a prefix or a suffix."""
    if not is_common(entry) and not AFFIXES & {p.text for p in entry.iter("pos")}:
        return set()
    return {(r, w) for r, w, _ in pairs(entry, [k.findtext("keb") for k in entry.findall("k_ele")])}


VOICED = dict(zip("がぎぐげござじずぜぞだぢづでどばびぶべぼぱぴぷぺぽ", "かきくけこさしすせそたちつてとはひふへほはひふへほ"))


def unvoiced(reading: str) -> str:
    """The reading with its first kana unvoiced, as the word is read alone."""
    return VOICED.get(reading[0], reading[0]) + reading[1:]


def made_of_common_words(reading: str, word: str, common: set[tuple[str, str]]) -> bool:
    """Whether the word is two or more parts of common (common words, prefixes, suffixes), with their readings one
    after another."""
    @functools.cache
    def made(i: int, j: int) -> bool:
        # whether word[i:] read reading[j:] is one or more common words; a word after the first may begin voiced
        # (相撲部屋 すもう + べや, from へや)
        return any(((reading[j:m], word[i:k]) in common or j > 0 and (unvoiced(reading[j:m]), word[i:k]) in common)
                   and (k == len(word) and m == len(reading) or made(k, m))
                   for k in range(i + 1, len(word) + 1) for m in range(j + 1, len(reading) + 1)
                   if not (i == 0 and k == len(word)))
    return made(0, 0)


def hard_words(entry: ET.Element):
    """The (reading, word, kind, field) of the words of one entry that may be hard; main() leaves out more of them."""
    misc = {m.text for m in entry.iter("misc")}
    yoji = YOJIJUKUGO in misc
    if not yoji and (is_common(entry) or USUALLY_KANA in misc):
        return
    field = next((f.text for f in entry.iter("field")), "")
    for reading, word, written in pairs(entry, spellings_of(entry)):
        if yoji:
            yield reading, word, "yoji", field
        elif len(reading) >= 4 and KANJI_ONLY.fullmatch(word) and written == reading and "ー" not in reading:
            yield reading, word, "rare", field


def main():
    parser = argparse.ArgumentParser(description="Make the table of hard words from JMdict")
    parser.add_argument("jmdict", help="JMdict_e or JMdict_e.gz")
    parser.add_argument("--keep-compounds", action="store_true",
                        help="keep the words made of common words (for the training rows: they teach the model to copy)")
    args = parser.parse_args()
    path = args.jmdict
    words = set()
    common: set[tuple[str, str]] = set()
    with (gzip.open(path) if path.endswith(".gz") else open(path, "rb")) as f:
        for _, element in ET.iterparse(f):
            if element.tag == "entry":
                words.update(hard_words(element))
                common |= common_words(element)
                element.clear()
    common_readings = {r for r, _ in common}
    words = {(r, w, k, f) for r, w, k, f in words
             if k == "yoji" or (r not in common_readings
                                and (args.keep_compounds or not made_of_common_words(r, w, common)))}
    # A word in two entries of different fields is one word: the first field in the order of their names
    fields: dict[tuple[str, str, str], str] = {}
    for r, w, k, f in sorted(words):
        if not fields.get((r, w, k)):
            fields[(r, w, k)] = f
    words = {(r, w, k, f) for (r, w, k), f in fields.items()}
    out = sys.stdout
    for reading, word, kind, field in sorted(words):
        out.write(f"{reading}\t{word}\t{kind}\t{field}\n")
    kinds = [k for _, _, k, _ in words]
    print(f"{len(words)} hard words: {kinds.count('yoji')} yoji, {kinds.count('rare')} rare", file=sys.stderr)


if __name__ == "__main__":
    main()
