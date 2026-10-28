// Tests of the cursor of the reading (src/romaji.h). No fcitx5 is needed.
#include "romaji.h"

#include <cstdio>
#include <string>

using namespace llmime;

static int failures = 0;

static void check(const char *what, long got, long want) {
    if (got != want) {
        std::printf("FAIL %s: got %ld, want %ld\n", what, got, want);
        failures++;
    }
}

int main() {
    // kaki shows かき; the borders are 0, 2 and 4
    check("kaki left from end", movedByOneKana("kaki", 4, -1), 2);
    check("kaki left from 2", movedByOneKana("kaki", 2, -1), 0);
    check("kaki left from 0", movedByOneKana("kaki", 0, -1), 0);
    check("kaki right from 0", movedByOneKana("kaki", 0, +1), 2);
    check("kaki right from 2", movedByOneKana("kaki", 2, +1), 4);
    check("kaki right from end", movedByOneKana("kaki", 4, +1), 4);

    // a letter that waits for its vowel: "kak" shows かk
    check("kak left from end", movedByOneKana("kak", 3, -1), 2);
    check("kak right from 2", movedByOneKana("kak", 2, +1), 3);

    // sha is one kana unit (しゃ); nn is ん
    check("sha right", movedByOneKana("sha", 0, +1), 3);
    check("nn right", movedByOneKana("nn", 0, +1), 2);

    // a character that is not romaji stays as it is
    check("a1b right 0", movedByOneKana("a1b", 0, +1), 1);
    check("a1b right 1", movedByOneKana("a1b", 1, +1), 2);
    check("a1b right 2", movedByOneKana("a1b", 2, +1), 3);

    // where the cursor is shown, in bytes of the shown text (one hiragana is three bytes)
    check("shown cursor at 0", shownCursorBytes("kaki", 0), 0);
    check("shown cursor at 2", shownCursorBytes("kaki", 2), 3);
    check("shown cursor at 4", shownCursorBytes("kaki", 4), 6);
    check("shown cursor inside ka", shownCursorBytes("kaki", 1), 0);
    check("shown cursor inside ki", shownCursorBytes("kaki", 3), 3);
    check("shown cursor of pending k at end", shownCursorBytes("kak", 3), 4);

    if (failures == 0) {
        std::printf("romaji-test: all passed\n");
    }
    return failures == 0 ? 0 : 1;
}
