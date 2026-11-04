#!/usr/bin/env python3
"""
Make the table of the common words of JMdict (those with ke_pri or re_pri): reading TAB word TAB number of the JMdict
entry, the spellings in kanji that JMdict does not mark as search-only, irregular, out-dated, rare or ateji.
prep/add_hints.py takes the hints like the user's earlier choices from it (--history-table), right and wrong ones:
words the user may well have chosen, and that may well not fit the text (感じ in 漢字を書く), not names or rare words
that no one would type. The number of the entry tells the spellings of one word (子ども and 子供) from other words of
the same reading: another spelling of the same word is no wrong hint.

Usage: prep/common_words.py JMdict_e[.gz] > common_words.tsv
"""

import gzip
import os
import sys
import xml.etree.ElementTree as ET

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from hard_words import KANJI_ONLY, is_common, pairs, spellings_of  # noqa: E402


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    path = sys.argv[1]
    words = set()
    with (gzip.open(path) if path.endswith(".gz") else open(path, "rb")) as f:
        for _, element in ET.iterparse(f):
            if element.tag == "entry":
                if is_common(element):
                    seq = element.findtext("ent_seq")
                    words.update((r, w, seq) for r, w, _ in pairs(element, spellings_of(element))
                                 if any(KANJI_ONLY.fullmatch(c) for c in w))
                element.clear()
    for reading, word, seq in sorted(words):
        print(f"{reading}\t{word}\t{seq}")
    print(f"{len(words)} common words", file=sys.stderr)


if __name__ == "__main__":
    main()
