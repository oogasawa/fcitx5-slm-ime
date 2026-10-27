#!/usr/bin/env python3
"""
Compare the trained model, Mozc and Anthy on exactly the same sentences.

Usage: compare_systems.py <test.tsv> <model-hypotheses.tsv> [per-sentence-output.tsv]

  test.tsv                 source TAB target   (target may contain '|', which is removed before comparing)
  model-hypotheses.tsv     source TAB output of the model (made by greedy_check.py with a dump file)

Mozc and Anthy are run here. They take typed keys, so a letter or a digit would be read as romaji or
as a number key. Each source is therefore split into runs: a run of kana is converted by the system,
and every other run (digits, Latin letters, symbols) is put into the output as it is, as a user would
do by switching to direct input. The model receives the whole source.

Per system the script prints, for all sentences and for the two groups (source of kana only / source
with other characters): the character error rate (total edit distance / total reference characters),
and the share of sentences that match exactly. Both are given on the raw text and after folding the
forms of symbols in reference and output (see fold): full-width and half-width forms, the quotation
marks 「」 and 『』, and the comma and full stop variants.
"""

import os
import re
import sys
import unicodedata

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import eval_anthy
import eval_mozc

KANA_RUN = re.compile(r"[ぁ-ゖー、。・「」『』（）！？…゛゜]+")


def runs(source: str):
    """Yield (is_kana, text) runs of the source."""
    position = 0
    for match in KANA_RUN.finditer(source):
        if match.start() > position:
            yield False, source[position:match.start()]
        yield True, match.group()
        position = match.end()
    if position < len(source):
        yield False, source[position:]


def convert_by_runs(source: str, convert_kana) -> str:
    return "".join(convert_kana(text) if is_kana else text for is_kana, text in runs(source))


FOLD_TABLE = str.maketrans({"『": "「", "』": "」", "，": "、", ",": "、", "．": "。", ".": "。", "〜": "~", "～": "~"})


def fold(text: str) -> str:
    """Equalise the forms of symbols that an IME chooses by its own settings: NFKC (full/half width),
    the two kinds of Japanese quotation marks, and the comma and full stop variants."""
    return unicodedata.normalize("NFKC", text).translate(FOLD_TABLE)


def edit_distance(a: str, b: str) -> int:
    previous = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        current = [i]
        for j, cb in enumerate(b, 1):
            current.append(min(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + (ca != cb)))
        previous = current
    return previous[-1]


def summarise(name: str, rows, normalise: bool) -> str:
    """rows: (is_pure_kana, reference, output)."""
    def prep(text):
        return fold(text) if normalise else text
    parts = []
    for label, keep in (("all", lambda r: True), ("kana only", lambda r: r[0]), ("with other chars", lambda r: not r[0])):
        chosen = [r for r in rows if keep(r)]
        errors = sum(edit_distance(prep(o), prep(ref)) for _, ref, o in chosen)
        chars = sum(len(prep(ref)) for _, ref, _ in chosen)
        exact = sum(prep(o) == prep(ref) for _, ref, o in chosen)
        parts.append(f"{label}: n={len(chosen)} CER {errors / chars * 100:.2f}% exact {exact / len(chosen) * 100:.1f}%")
    return f"{name:6} " + " | ".join(parts)


def main() -> None:
    test_path, model_path = sys.argv[1], sys.argv[2]
    out_path = sys.argv[3] if len(sys.argv) > 3 else None

    sentences = []
    with open(test_path, encoding="utf-8") as f:
        for line in f:
            source, target = line.rstrip("\n").split("\t", 1)
            sentences.append((source, target.replace("|", "")))
    model_output = {}
    with open(model_path, encoding="utf-8") as f:
        for line in f:
            source, output = line.rstrip("\n").split("\t", 1)
            model_output[source] = output.replace("|", "")

    mozc = eval_mozc.MozcClient(eval_mozc._find_socket())

    def mozc_kana(text: str) -> str:
        session = mozc.create_session()
        try:
            return mozc.convert_kana(session, text)
        finally:
            mozc.delete_session(session)

    results = {"model": [], "Mozc": [], "Anthy": []}
    detail = []
    for index, (source, reference) in enumerate(sentences, 1):
        pure = KANA_RUN.fullmatch(source) is not None
        outputs = {
            "model": model_output.get(source, ""),
            "Mozc": convert_by_runs(source, mozc_kana),
            "Anthy": convert_by_runs(source, eval_anthy.anthy_convert),
        }
        for name, output in outputs.items():
            results[name].append((pure, reference, output))
        detail.append((source, reference, outputs["model"], outputs["Mozc"], outputs["Anthy"]))
        if index % 500 == 0:
            print(f"  {index}/{len(sentences)}", file=sys.stderr, flush=True)

    for normalise in (False, True):
        print("symbols folded" if normalise else "raw text")
        for name in ("model", "Mozc", "Anthy"):
            print("  " + summarise(name, results[name], normalise))
    if out_path:
        with open(out_path, "w", encoding="utf-8") as f:
            f.write("source\treference\tmodel\tMozc\tAnthy\n")
            for row in detail:
                f.write("\t".join(row) + "\n")


if __name__ == "__main__":
    main()
