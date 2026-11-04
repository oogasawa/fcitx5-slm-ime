#!/usr/bin/env python3
"""
Put the words of the dictionary that the reading may hold in front of the source of the rows, as hints the model can
copy: the model learns grammar from the sentences, and the words it sees too seldom come from the dictionary
(prep/hard_words.py).

The source of a row with hints is
    <reading>=<word> <reading>=<word> ... SEP <text before the cursor> SEP <reading>
and a row without hints keeps its form, <text before the cursor> SEP <reading>. The reading of each hint tells the
model which stretch of the typed reading the word stands for. The words are those whose reading is a stretch of the
typed reading at least --shortest kana long and that are at least --fewest-chars characters long, the longest
readings first, at most --most of them; a word may be one the sentence does not use, as in the engine, which looks
the words up the same way. Short words are left out because their readings are found where other words stand next
to each other (強酸 in にほんきょうさんとう, 周章 in りょうしゅうしょう): in the training rows of the released model,
with the table of hard_words.py --keep-compounds, 10 % of the rows with hints had only wrong ones with these limits,
and most rows had only wrong ones with readings of 4 kana. The text before the cursor is cut from the left so that
the source stays within --max-len characters.

With --drop-percent, that share of the training rows that would get hints keeps the form without hints, so that the
model still writes the words it knows when the dictionary has none: trained on rows that all had their hints, the
model wrote fewer of the words it had learned when they came without hints (95.6 % against 96.6 %). The rows are
chosen by a checksum of their source, the same ones every time.

With --fields, a hint of a word whose field the table names (its fourth column: medicine, law...) is written
<field>:<reading>=<word>, the field in a few Japanese characters (医学:かんせいこんすい=肝性昏睡), so that the model can
match the field of the hint with what the text is about, as a reader does: かんせい is 肝性 in a medical text and
完成 or 慣性 elsewhere.

With --history-table and --history-percent, that share of the training and validation rows gets one or two hints
more after those of the dictionary, as the engine gives the user's earlier choices: two bunsetsu next to each other,
the one the user chose and the one before it, written 読み~語 so that the model can tell them from the words of the
dictionary (読み=語), which it is to copy. Half of them are right (two bunsetsu of the text: かんじを|かく~漢字を書く)
and half wrong (the same with a common word in them put in another spelling of its reading that is another word:
かんじを|かく~感じを書く, never 子供 for 子ども). A model trained only on hints that are right four times in five copies
whatever it is given, the user's choice of 感じ into 漢字を書く as well; with these rows it is to judge a hint of this
kind by the text around it. Two bunsetsu carry their own context, which a short reading alone does not: いる of
おかねが|いる is 要る, that of へやに|いる is not.

Usage: prep/add_hints.py hard_words.tsv IN_DIR OUT_DIR [--shortest 6] [--fewest-chars 3] [--most 4] [--max-len 192]
       [--drop-percent 0] [--fields] [--history-table common_words.tsv --history-percent 30] [--rows N]
       [--workers 8]
Reads IN_DIR/{train,valid,test}.tsv and writes the same files to OUT_DIR, with tokenizer.json copied; --rows keeps
every k-th training row so that about N are written.
"""

import argparse
import multiprocessing
import os
import random
import re
import shutil
import zlib

SEP = "\x1f"
DICTIONARY_MARK = "="
HISTORY_MARK = "~"

# The fields of JMdict (and "medicine" for the Manbyo dictionary) in a few Japanese characters
FIELD_NAMES = {
    "computing": "情報", "medicine": "医学", "Buddhism": "仏教", "mathematics": "数学", "law": "法律",
    "anatomy": "解剖", "food, cooking": "料理", "linguistics": "言語", "chemistry": "化学", "physics": "物理",
    "astronomy": "天文", "grammar": "文法", "biology": "生物", "geology": "地質", "sumo": "相撲", "music": "音楽",
    "botany": "植物", "finance": "金融", "mineralogy": "鉱物", "military": "軍事", "Shinto": "神道",
    "baseball": "野球", "martial arts": "武術", "philosophy": "哲学", "psychiatry": "精神医学", "business": "商業",
    "economics": "経済", "sports": "運動", "architecture": "建築", "physiology": "生理", "Christianity": "キリスト教",
    "geometry": "幾何", "pharmacology": "薬理", "genetics": "遺伝", "zoology": "動物", "shogi": "将棋",
    "meteorology": "気象", "printing": "印刷", "hanafuda": "花札", "go (game)": "囲碁", "art, aesthetics": "美術",
    "psychology": "心理", "electricity, elec. eng.": "電気", "Japanese mythology": "神話", "telecommunications": "通信",
    "mahjong": "麻雀", "card games": "カード", "aviation": "航空", "agriculture": "農業", "noh": "能",
    "electronics": "電子", "logic": "論理", "horse racing": "競馬", "engineering": "工学", "dentistry": "歯科",
    "biochemistry": "生化学", "photography": "写真", "archeology": "考古", "video games": "ゲーム", "railway": "鉄道",
    "fishing": "釣り", "statistics": "統計", "Internet": "ネット", "surgery": "外科", "Chinese mythology": "神話",
    "embryology": "発生", "entomology": "昆虫", "trademark": "商標", "geography": "地理", "film": "映画",
    "veterinary terms": "獣医", "kabuki": "歌舞伎", "crystallography": "結晶", "skiing": "スキー", "politics": "政治",
    "ecology": "生態", "psychoanalysis": "精神分析", "mining": "鉱業", "stock market": "株式", "ornithology": "鳥類",
    "clothing": "衣服", "civil engineering": "土木", "mechanical engineering": "機械", "Greek mythology": "神話",
    "gardening, horticulture": "園芸", "professional wrestling": "プロレス", "golf": "ゴルフ", "audiovisual": "映像",
}

_words: dict[str, list[str]] = {}
_fields: dict[str, str] = {}
_lengths: list[int] = []
_most = 4
_max_len = 192
_drop_percent = 0
_history: dict[str, list[tuple[str, str]]] = {}
_spellings: dict[str, list[tuple[str, str]]] = {}
_history_percent = 0
HISTORY_LONGEST = 16  # the longest reading of a hint like the user's choices, two bunsetsu
SPELLING_LONGEST = 6
KANJI = re.compile(r"[\u3400-\u9fff\uf900-\ufaff々〆ヶ]")


def load_hard_words(path: str, fewest_chars: int = 1) -> dict[str, list[str]]:
    """The words of the table by reading, those of at least fewest_chars characters."""
    words: dict[str, list[str]] = {}
    with open(path, encoding="utf-8") as f:
        for line in f:
            reading, word = line.rstrip("\n").split("\t")[:2]
            if len(word) >= fewest_chars:
                words.setdefault(reading, []).append(word)
    return words


def load_fields(path: str) -> dict[str, str]:
    """The field of each word of the table that has one, in Japanese (FIELD_NAMES)."""
    fields: dict[str, str] = {}
    with open(path, encoding="utf-8") as f:
        for line in f:
            columns = line.rstrip("\n").split("\t")
            if len(columns) > 3 and columns[3] in FIELD_NAMES:
                fields[columns[1]] = FIELD_NAMES[columns[3]]
    return fields


def hints(reading: str, words: dict[str, list[str]], lengths: list[int], most: int) -> list[tuple[str, str]]:
    """The (reading, word) of the words whose reading is a stretch of the reading, the longest readings first."""
    found: list[tuple[str, str]] = []
    for n in lengths:
        for i in range(len(reading) - n + 1):
            for word in words.get(reading[i:i + n], ()):
                if all(word != w for _, w in found):
                    found.append((reading[i:i + n], word))
                    if len(found) == most:
                        return found
    return found


def load_history_table(path: str) -> dict[str, list[tuple[str, str]]]:
    """reading TAB word TAB entry lines (prep/common_words.py): the (word, entry) of each reading."""
    words: dict[str, list[tuple[str, str]]] = {}
    with open(path, encoding="utf-8") as f:
        for line in f:
            columns = line.rstrip("\n").split("\t")
            if len(columns) >= 3:
                words.setdefault(columns[0], []).append((columns[1], columns[2]))
    return words


KANA = "\u3041-\u3096\u30a1-\u30faー"
MARKS = "\\s、。「」『』（）()・，．,.!?！？"
PIECES = re.compile(f"([{KANA}])|([^{KANA}{MARKS}]+)|(.)")


def align(reading: str, texts: list[str]) -> list[str] | None:
    """The reading of each bunsetsu: kana and marks stand for themselves (katakana for hiragana), a run of kanji,
    digits or letters for some kana. None where the reading cannot be cut so."""
    groups = []
    for text in texts:
        pattern = ""
        for kana, run, mark in PIECES.findall(text):
            if kana:
                pattern += re.escape(chr(ord(kana) - 0x60) if "\u30a1" <= kana <= "\u30f6" else kana)
            elif run:
                pattern += ".+?"
            else:
                pattern += re.escape(mark)
        if not pattern:
            return None
        groups.append(f"({pattern})")
    m = re.fullmatch("".join(groups), reading)
    return list(m.groups()) if m else None


def wrong_spelling(texts: list[str], readings: list[str], rng: random.Random) -> str | None:
    """The text of the bunsetsu with one common word in another spelling of its reading that is another word
    (感じを書く for 漢字を書く), or None where there is none. The word is one of two characters or more that no other
    kanji stand next to and whose reading the bunsetsu have (not 人 of 成人病 for 任, nor 上 of 上生菓子 read かみ
    for 加味), or a bunsetsu written in kana as a whole (いる of 部屋に|いる for 要る)."""
    text, reading = "".join(texts), "".join(readings)
    choices = []
    for i in range(len(text)):
        for n in range(2, min(SPELLING_LONGEST, len(text) - i) + 1):
            here = text[i:i + n]
            if not KANJI.search(here) or (i > 0 and KANJI.match(text[i - 1])) or \
                    (i + n < len(text) and KANJI.match(text[i + n])):
                continue
            for r, entry in _spellings.get(here, ()):
                if r in reading:
                    choices += [text[:i] + w + text[i + n:] for w, e in _history.get(r, ()) if e != entry]
    for k, t in enumerate(texts):
        bare = t.rstrip("、。")
        if bare and not KANJI.search(bare):
            start = sum(len(x) for x in texts[:k])
            choices += [text[:start] + w + text[start + len(bare):] for w, _ in _history.get(bare, ())]
    choices = [c for c in choices if c != text]
    return rng.choice(choices) if choices else None


def _sure_border(left: str, right: str) -> bool:
    """Whether align cut the reading right between two bunsetsu: not where a run of kanji (or digits) ends the one and
    begins the other, as in 高い|」「これ以上|体重を, where it gives これい to the one and じょうたいじゅうを to the other."""
    return not (left and right and PIECES.fullmatch(left[-1]).group(2) and PIECES.fullmatch(right[0]).group(2))


def history_hints(reading: str, target: str, rng: random.Random, taken: list[tuple[str, str, str]]
                  ) -> list[tuple[str, str, str]]:
    """One or two hints like the user's earlier choices, two bunsetsu of the target, right or wrong by even chance
    (see the module text)."""
    texts = target.split("|")
    readings = align(reading, texts)
    if readings is None or len(texts) < 2:
        return []
    pairs = [i for i in range(len(texts) - 1)
             if KANJI.search(texts[i] + texts[i + 1]) and len(readings[i] + readings[i + 1]) <= HISTORY_LONGEST
             and not set(texts[i] + texts[i + 1]) & {DICTIONARY_MARK, HISTORY_MARK, " ", SEP}
             and (i == 0 or _sure_border(texts[i - 1], texts[i]))
             and (i + 2 == len(texts) or _sure_border(texts[i + 1], texts[i + 2]))]
    rng.shuffle(pairs)
    found: list[tuple[str, str, str]] = []
    for _ in range(rng.randint(1, 2)):
        # A wrong one can be made for about half of the pairs (one with a common word of another spelling in it): it
        # is asked for more often, so that about half of the hints are wrong
        right = rng.random() < 0.3
        for i in pairs:
            word = texts[i] + texts[i + 1] if right else wrong_spelling(texts[i:i + 2], readings[i:i + 2], rng)
            if word is not None and all(word != w for _, w, _ in taken + found):
                found.append((readings[i] + readings[i + 1], word, HISTORY_MARK))
                pairs.remove(i)
                break
    return found


def with_hints(source: str, text: str = "") -> str:
    """The source with its hints; with the text of the row, the hints like the user's choices too, for some rows."""
    context, _, reading = source.rpartition(SEP)
    found = [(r, w, DICTIONARY_MARK) for r, w in hints(reading, _words, _lengths, _most)]
    if text and _history:
        key = zlib.crc32(source.encode("utf-8"))
        if key % 100 < _history_percent:
            found = found + history_hints(reading, text, random.Random(key), found)
    if not found:
        return source
    head = " ".join((f"{_fields[w]}:" if w in _fields else "") + f"{r}{mark}{w}" for r, w, mark in found) + SEP
    room = _max_len - len(head) - 1 - len(reading)
    if room < 0:
        return source
    return head + context[max(0, len(context) - room):] + SEP + reading


def convert_line(line: str) -> str:
    source, target = line.rstrip("\n").split("\t", 1)
    return with_hints(source) + "\t" + target + "\n"


def convert_training_line(line: str) -> str:
    source, target = line.rstrip("\n").split("\t", 1)
    if zlib.crc32(source.encode("utf-8")) % 100 < _drop_percent:
        return source + "\t" + target + "\n"
    return with_hints(source, target) + "\t" + target + "\n"


def init(words: dict[str, list[str]], shortest: int, most: int, max_len: int, drop_percent: int = 0,
         fields: dict[str, str] | None = None, history: dict[str, list[str]] | None = None,
         history_percent: int = 0) -> None:
    global _words, _lengths, _most, _max_len, _drop_percent, _fields, _history, _spellings, _history_percent
    _words, _most, _max_len, _drop_percent, _fields = words, most, max_len, drop_percent, fields or {}
    _history, _history_percent = history or {}, history_percent
    _spellings = {}
    for reading, spellings in _history.items():
        for word, entry in spellings:
            _spellings.setdefault(word, []).append((reading, entry))
    _lengths = sorted({len(r) for r in words if len(r) >= shortest}, reverse=True)


def main():
    parser = argparse.ArgumentParser(description="Put dictionary hints in front of the source of the rows")
    parser.add_argument("hard_words")
    parser.add_argument("in_dir")
    parser.add_argument("out_dir")
    parser.add_argument("--shortest", type=int, default=6, help="the shortest reading of a hint, in kana")
    parser.add_argument("--fewest-chars", type=int, default=3, help="the shortest word of a hint, in characters")
    parser.add_argument("--most", type=int, default=4, help="the most hints of a row")
    parser.add_argument("--max-len", type=int, default=192, help="the longest source")
    parser.add_argument("--drop-percent", type=int, default=0,
                        help="the share of the training rows, in percent, left without hints")
    parser.add_argument("--fields", action="store_true", help="write the field of a hint in front of it")
    parser.add_argument("--history-table", default="", help="reading TAB word TAB entry, the common words (common_words.tsv)")
    parser.add_argument("--history-percent", type=int, default=0,
                        help="the share of the training and validation rows, in percent, with hints like the user's choices")
    parser.add_argument("--rows", type=int, default=0, help="about how many training rows to write (0: all)")
    parser.add_argument("--workers", type=int, default=8)
    args = parser.parse_args()

    words = load_hard_words(args.hard_words, args.fewest_chars)
    os.makedirs(args.out_dir, exist_ok=True)
    shutil.copy(os.path.join(args.in_dir, "tokenizer.json"), args.out_dir)
    history = load_history_table(args.history_table) if args.history_table else None
    with multiprocessing.Pool(args.workers, init, (words, args.shortest, args.most, args.max_len, args.drop_percent,
                                                      load_fields(args.hard_words) if args.fields else None,
                                                      history, args.history_percent)) as pool:
        for name in ("train", "valid", "test"):
            with open(os.path.join(args.in_dir, f"{name}.tsv"), encoding="utf-8") as f:
                lines = f.readlines()
            if name == "train" and args.rows:
                lines = lines[::max(1, len(lines) // args.rows)]
            out = pool.map(convert_line if name == "test" else convert_training_line, lines, chunksize=2000)
            with open(os.path.join(args.out_dir, f"{name}.tsv"), "w", encoding="utf-8") as f:
                f.writelines(out)
            hinted = sum(1 for line in out if line.split("\t", 1)[0].count(SEP) == 2)
            print(f"{name}: {len(out)} rows, {hinted} with hints")


if __name__ == "__main__":
    main()
