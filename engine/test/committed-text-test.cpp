// Tests of the committed text the engine keeps (src/committed-text.h). No fcitx5 is needed.
#include "committed-text.h"

#include <cstdio>
#include <string>

using namespace llmime;

static int failures = 0;

static void check(const char *what, const std::string &got, const std::string &want) {
    if (got != want) {
        std::printf("FAIL %s: got \"%s\", want \"%s\"\n", what, got.c_str(), want.c_str());
        failures++;
    }
}

static void check(const char *what, bool got, bool want) {
    if (got != want) {
        std::printf("FAIL %s: got %d, want %d\n", what, got, want);
        failures++;
    }
}

int main() {
    // the last characters are counted in characters, so a character of three bytes is never cut
    check("shorter", lastChars("今日は", 5), "今日は");
    check("cut", lastChars("今日はいい天気", 4), "いい天気");
    check("mixed", lastChars("abc漢字", 3), "c漢字");
    check("zero", lastChars("漢字", 0), "");

    // the committed text keeps the last characters only
    check("append", appendCommitted("今日は", "いい天気です。", 5), "天気です。");
    check("append short", appendCommitted("", "猫", 500), "猫");

    // the last commit can be taken back only when the text ends with it
    check("ends", endsWithText("今日はいい天気", "いい天気"), true);
    check("not end", endsWithText("今日はいい天気", "今日は"), false);
    check("empty", endsWithText("今日は", ""), false);
    check("longer", endsWithText("天気", "いい天気"), false);

    check("without", withoutEnd("今日はいい天気", "いい天気"), "今日は");
    check("without other", withoutEnd("今日はいい天気", "猫"), "今日はいい天気");

    if (failures == 0) {
        std::printf("committed-text-test: all passed\n");
    }
    return failures == 0 ? 0 : 1;
}
