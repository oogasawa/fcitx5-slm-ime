// Tests of the readings written in other kinds of characters (src/char-kinds.h). No fcitx5 is needed.
#include "char-kinds.h"

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

int main() {
    check("katakana", hiraganaToKatakana("きょうはゔぁいおりん。"), "キョウハヴァイオリン。");
    check("hiragana", katakanaToHiragana("キョウハ漢字ー"), "きょうは漢字ー");
    check("half katakana", toHalfKatakana("がっこうのパソコン、ヴ"), "ｶﾞｯｺｳﾉﾊﾟｿｺﾝ､ｳﾞ");
    check("half katakana keeps others", toHalfKatakana("漢字abc"), "漢字abc");
    check("wide latin", toWideLatin("Abc 1!"), "Ａｂｃ　１！");

    check("romaji", kanaToRomaji("かんじ"), "kanji");
    check("romaji sokuon", kanaToRomaji("きって"), "kitte");
    check("romaji n before vowel", kanaToRomaji("きんえん"), "kinnen");
    check("romaji n before y", kanaToRomaji("こんやく"), "konnyaku");
    check("romaji shi tsu fu", kanaToRomaji("しつふ"), "shitsufu");
    check("romaji youon", kanaToRomaji("きょうしゅう"), "kyoushuu");
    check("romaji long vowel", kanaToRomaji("ぱーてぃー"), "pa-thi-");
    check("romaji katakana", kanaToRomaji("テスト"), "tesuto");
    check("romaji keeps others", kanaToRomaji("漢か"), "漢ka");
    // typing the letters again gives the same kana
    for (const char *kana : {"かんじ", "きんえん", "こんやく", "がっこう", "ちゅうごく", "でぃすく", "ふぁいる"}) {
        check(kana, romajiToHiragana(kanaToRomaji(kana)), kana);
    }

    check("lower to upper", nextLatinForm("test", "test"), "TEST");
    check("upper to capital", nextLatinForm("test", "TEST"), "Test");
    check("capital to lower", nextLatinForm("test", "Test"), "test");
    check("other to lower", nextLatinForm("test", "てすと"), "test");

    if (failures == 0) {
        std::printf("char-kinds-test: all passed\n");
    }
    return failures == 0 ? 0 : 1;
}
