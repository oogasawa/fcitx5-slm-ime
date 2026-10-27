#!/usr/bin/env python3
"""
Anthy batch evaluator.
Reads kana-kanji pairs from a TSV file (kana<TAB>expected_kanji),
converts kana using libanthyinput, and computes CER.
"""

import ctypes
import ctypes.util
import sys
import argparse


# ---------------------------------------------------------------------------
# Anthy C API via ctypes
# ---------------------------------------------------------------------------

class AnthyConvStat(ctypes.Structure):
    _fields_ = [("nr_segment", ctypes.c_int)]


class AnthySegmentStat(ctypes.Structure):
    _fields_ = [
        ("nr_candidate", ctypes.c_int),
        ("seg_len", ctypes.c_int),
    ]


def _load_anthy():
    lib = ctypes.CDLL("libanthyinput.so.0")

    lib.anthy_init.restype = ctypes.c_int
    lib.anthy_init.argtypes = []

    lib.anthy_create_context.restype = ctypes.c_void_p
    lib.anthy_create_context.argtypes = []

    lib.anthy_release_context.restype = None
    lib.anthy_release_context.argtypes = [ctypes.c_void_p]

    lib.anthy_context_set_encoding.restype = ctypes.c_int
    lib.anthy_context_set_encoding.argtypes = [ctypes.c_void_p, ctypes.c_int]

    lib.anthy_set_string.restype = ctypes.c_int
    lib.anthy_set_string.argtypes = [ctypes.c_void_p, ctypes.c_char_p]

    lib.anthy_get_stat.restype = ctypes.c_int
    lib.anthy_get_stat.argtypes = [ctypes.c_void_p, ctypes.POINTER(AnthyConvStat)]

    lib.anthy_get_segment_stat.restype = ctypes.c_int
    lib.anthy_get_segment_stat.argtypes = [
        ctypes.c_void_p, ctypes.c_int, ctypes.POINTER(AnthySegmentStat)
    ]

    lib.anthy_get_segment.restype = ctypes.c_int
    lib.anthy_get_segment.argtypes = [
        ctypes.c_void_p, ctypes.c_int, ctypes.c_int,
        ctypes.c_char_p, ctypes.c_int
    ]

    return lib


ANTHY_UTF8_ENCODING = 2

_lib = _load_anthy()
_lib.anthy_init()
_ctx = _lib.anthy_create_context()
_lib.anthy_context_set_encoding(_ctx, ANTHY_UTF8_ENCODING)


def anthy_convert(kana: str) -> str:
    """Convert kana string to kanji using Anthy (first candidate per segment)."""
    _lib.anthy_set_string(_ctx, kana.encode("utf-8"))

    stat = AnthyConvStat()
    _lib.anthy_get_stat(_ctx, ctypes.byref(stat))

    result = []
    buf = ctypes.create_string_buffer(1024)
    for i in range(stat.nr_segment):
        _lib.anthy_get_segment(_ctx, i, 0, buf, 1024)
        result.append(buf.value.decode("utf-8"))

    return "".join(result)


# ---------------------------------------------------------------------------
# CER computation
# ---------------------------------------------------------------------------

def cer(hypothesis: str, reference: str) -> float:
    """Character Error Rate using edit distance."""
    h = list(hypothesis)
    r = list(reference)
    m, n = len(h), len(r)
    if n == 0:
        return 0.0 if m == 0 else 1.0

    dp = list(range(n + 1))
    for i in range(1, m + 1):
        prev = dp[:]
        dp[0] = i
        for j in range(1, n + 1):
            if h[i - 1] == r[j - 1]:
                dp[j] = prev[j - 1]
            else:
                dp[j] = 1 + min(prev[j], dp[j - 1], prev[j - 1])

    return dp[n] / n


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(description="Evaluate Anthy on a TSV test set")
    parser.add_argument("tsv", help="Test TSV file: kana<TAB>expected_kanji")
    parser.add_argument("--limit", type=int, default=None, help="Limit number of samples")
    parser.add_argument("--output", default=None,
                        help="Write per-pair TSV to this file (kana TAB ref TAB hyp TAB cer)")
    args = parser.parse_args()

    total_cer = 0.0
    exact = 0
    n = 0
    rows = []

    with open(args.tsv, encoding="utf-8") as f:
        for line in f:
            line = line.rstrip("\n")
            parts = line.split("\t", 1)
            if len(parts) != 2:
                continue
            kana, expected = parts[0].strip(), parts[1].strip()
            if not kana or not expected:
                continue

            hypothesis = anthy_convert(kana)
            c = cer(hypothesis, expected)
            total_cer += c
            if hypothesis == expected:
                exact += 1
            rows.append((kana, expected, hypothesis, c))
            n += 1

            if args.limit and n >= args.limit:
                break

    if n == 0:
        print("No samples found.", file=sys.stderr)
        sys.exit(1)

    print(f"Samples:     {n}")
    print(f"CER:         {total_cer / n * 100:.2f}%")
    print(f"Exact Match: {exact / n * 100:.2f}%")

    if args.output:
        with open(args.output, "w", encoding="utf-8") as f:
            f.write("kana\tref\thyp\tcer\n")
            for kana, ref, hyp, c in rows:
                f.write(f"{kana}\t{ref}\t{hyp}\t{c:.6f}\n")
        print(f"Per-pair results saved: {args.output}")


if __name__ == "__main__":
    main()
