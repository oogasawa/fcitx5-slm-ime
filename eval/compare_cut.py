#!/usr/bin/env python3
"""
Compare models, Mozc and Anthy on the rows of a context data set: a reading that starts inside a sentence,
with the text before the cursor.

Usage: compare_cut.py <with_context.tsv> <per-sentence-output.tsv> <name>=<hypotheses.tsv>[:reading] ...

  with_context.tsv    source = <text before the cursor> SEP <reading> TAB target   (made by make_cut_tests.py)
  <name>=<file>       the output of one model: source TAB output (made by greedy_check.py with a dump file).
                      The rows are found by the whole source; with ':reading' they are found by the reading
                      alone (for a model that was given the reading only).

Mozc and Anthy convert the reading only, in the same way as compare_systems.py (runs of kana are converted,
other runs are passed through); neither can use the text before the cursor. The character error rate and the
exact match are printed for every system, on the raw text and after the symbol folding of compare_systems.py.
"""

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
import eval_anthy
import eval_mozc
from compare_systems import KANA_RUN, convert_by_runs, summarise
from model.tokenizer import SEP_CHAR


def load_hypotheses(spec: str):
    """Returns (name, key kind, table). The key kind is 'source' (the whole source), 'reading' (the reading
    alone) or 'empty' (SEP and the reading: the source with nothing before the cursor)."""
    name, rest = spec.split("=", 1)
    kind = "source"
    for suffix in ("reading", "empty"):
        if rest.endswith(":" + suffix):
            kind = suffix
            rest = rest[: -len(suffix) - 1]
    table = {}
    with open(rest, encoding="utf-8") as f:
        for line in f:
            source, output = line.rstrip("\n").split("\t", 1)
            table[source] = output.replace("|", "")
    return name, kind, table


def main() -> None:
    test_path, out_path = sys.argv[1], sys.argv[2]
    models = [load_hypotheses(spec) for spec in sys.argv[3:]]

    rows = []
    with open(test_path, encoding="utf-8") as f:
        for line in f:
            source, target = line.rstrip("\n").split("\t", 1)
            context, reading = source.split(SEP_CHAR, 1)
            rows.append((source, context, reading, target.replace("|", "")))

    mozc = eval_mozc.MozcClient(eval_mozc._find_socket())

    def mozc_kana(text: str) -> str:
        session = mozc.create_session()
        try:
            return mozc.convert_kana(session, text)
        finally:
            mozc.delete_session(session)

    names = [name for name, _, _ in models] + ["Mozc", "Anthy"]
    results = {name: [] for name in names}
    detail = []
    skipped = 0
    for index, (source, context, reading, reference) in enumerate(rows, 1):
        pure = KANA_RUN.fullmatch(reading) is not None
        outputs = {}
        for name, kind, table in models:
            key = {"source": source, "reading": reading, "empty": SEP_CHAR + reading}[kind]
            if key in table:
                outputs[name] = table[key]
        if len(outputs) < len(models):
            skipped += 1
            continue
        outputs["Mozc"] = convert_by_runs(reading, mozc_kana)
        outputs["Anthy"] = convert_by_runs(reading, eval_anthy.anthy_convert)
        for name in names:
            results[name].append((pure, reference, outputs[name]))
        detail.append([context, reading, reference] + [outputs[name] for name in names])
        if index % 500 == 0:
            print(f"  {index}/{len(rows)}", file=sys.stderr, flush=True)

    print(f"{len(rows) - skipped} rows compared; {skipped} rows left out because a model gave no output "
          f"(its input was longer than the length the model was trained for)")
    for normalise in (False, True):
        print("symbols folded" if normalise else "raw text")
        for name in names:
            print("  " + summarise(name, results[name], normalise))
    with open(out_path, "w", encoding="utf-8") as f:
        f.write("\t".join(["context", "reading", "reference"] + names) + "\n")
        for row in detail:
            f.write("\t".join(row) + "\n")


if __name__ == "__main__":
    main()
