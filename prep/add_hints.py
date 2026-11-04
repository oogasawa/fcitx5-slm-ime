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

Usage: prep/add_hints.py hard_words.tsv IN_DIR OUT_DIR [--shortest 6] [--fewest-chars 3] [--most 4] [--max-len 192]
       [--drop-percent 0] [--fields] [--rows N] [--workers 8]
Reads IN_DIR/{train,valid,test}.tsv and writes the same files to OUT_DIR, with tokenizer.json copied; --rows keeps
every k-th training row so that about N are written.
"""

import argparse
import multiprocessing
import os
import shutil
import zlib

SEP = "\x1f"

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


def with_hints(source: str) -> str:
    context, _, reading = source.rpartition(SEP)
    found = hints(reading, _words, _lengths, _most)
    if not found:
        return source
    head = " ".join((f"{_fields[w]}:" if w in _fields else "") + f"{r}={w}" for r, w in found) + SEP
    room = _max_len - len(head) - 1 - len(reading)
    if room < 0:
        return source
    return head + context[max(0, len(context) - room):] + SEP + reading


def convert_line(line: str) -> str:
    source, target = line.rstrip("\n").split("\t", 1)
    return with_hints(source) + "\t" + target + "\n"


def convert_training_line(line: str) -> str:
    source = line.split("\t", 1)[0]
    if zlib.crc32(source.encode("utf-8")) % 100 < _drop_percent:
        return line if line.endswith("\n") else line + "\n"
    return convert_line(line)


def init(words: dict[str, list[str]], shortest: int, most: int, max_len: int, drop_percent: int = 0,
         fields: dict[str, str] | None = None) -> None:
    global _words, _lengths, _most, _max_len, _drop_percent, _fields
    _words, _most, _max_len, _drop_percent, _fields = words, most, max_len, drop_percent, fields or {}
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
    parser.add_argument("--rows", type=int, default=0, help="about how many training rows to write (0: all)")
    parser.add_argument("--workers", type=int, default=8)
    args = parser.parse_args()

    words = load_hard_words(args.hard_words, args.fewest_chars)
    os.makedirs(args.out_dir, exist_ok=True)
    shutil.copy(os.path.join(args.in_dir, "tokenizer.json"), args.out_dir)
    with multiprocessing.Pool(args.workers, init, (words, args.shortest, args.most, args.max_len, args.drop_percent,
                                                      load_fields(args.hard_words) if args.fields else None)) as pool:
        for name in ("train", "valid", "test"):
            with open(os.path.join(args.in_dir, f"{name}.tsv"), encoding="utf-8") as f:
                lines = f.readlines()
            if name == "train" and args.rows:
                lines = lines[::max(1, len(lines) // args.rows)]
            out = pool.map(convert_training_line if name == "train" else convert_line, lines, chunksize=2000)
            with open(os.path.join(args.out_dir, f"{name}.tsv"), "w", encoding="utf-8") as f:
                f.writelines(out)
            hinted = sum(1 for line in out if line.split("\t", 1)[0].count(SEP) == 2)
            print(f"{name}: {len(out)} rows, {hinted} with hints")


if __name__ == "__main__":
    main()
