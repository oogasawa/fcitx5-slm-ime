// Tests of the hints of the dictionary: the sources must be those that prep/add_hints.py made for the training (the
// 354 of data/hints/cases.tsv, the test of the Java conversion server of version 3 too), and the words the model
// could not copy are put in by the rules of substituteHints (the cases of HintSubstitutionTest of version 3).
// Usage: hints-test DATA_DIR
#include "conversion/dictionary-hints.h"
#include "conversion/hint-substitution.h"
#include "conversion/text.h"

#include <algorithm>
#include <cstdio>
#include <fstream>
#include <string>
#include <vector>

using namespace llmime::conversion;

static int failures = 0;

static void check(const std::string &what, const std::u32string &got, const std::u32string &want) {
    if (got != want) {
        std::printf("FAIL %s: got \"%s\", want \"%s\"\n", what.c_str(), toU8(got).c_str(), toU8(want).c_str());
        failures++;
    }
}

static std::vector<Hint> hints(std::initializer_list<const char *> pairs) {
    std::vector<Hint> list;
    for (const char *pair : pairs) {
        std::u32string rw = toU32(pair);
        size_t eq = rw.find(U'=');
        list.push_back({rw.substr(0, eq), rw.substr(eq + 1)});
    }
    return list;
}

static void sourcesOfTheTrainingRows(const std::string &dir) {
    DictionaryHints table(dir + "/table.tsv");
    std::ifstream in(dir + "/cases.tsv");
    std::string line;
    int cases = 0, hinted = 0;
    while (std::getline(in, line)) {
        size_t a = line.find('\t'), b = line.find('\t', a + 1);
        std::u32string context = toU32(line.substr(0, a)), reading = toU32(line.substr(a + 1, b - a - 1));
        std::u32string want = toU32(line.substr(b + 1));
        std::u32string got = hintedSource(context, reading, 100, table.of(reading), 192);
        check("source of " + toU8(reading), got, want);
        cases++;
        hinted += std::count(got.begin(), got.end(), U'\x1f') == 2;
    }
    if (cases != 354 || hinted < 300) {
        std::printf("FAIL %d cases, %d with hints\n", cases, hinted);
        failures++;
    }
}

static void theLongestReadingsFirstAndEachWordOnce() {
    std::string path = "/tmp/hints-test-table.tsv";
    std::ofstream(path) << "きんきゅうじ\t緊急時\trare\nきんきゅうじたい\t緊急事態\trare\nきゅうじたい\t旧字体\trare\n"
                           "きゅうじたい\t緊急事態\trare\nこうたい\t交代劇\trare\nあいうえおか\t二字\trare\n";
    auto found = DictionaryHints(path).of(U"きんきゅうじたいにもこうたいあいうえおか");
    std::u32string got;
    for (const auto &h : found) {
        got += h.reading + U"=" + h.word + U" ";
    }
    check("order of the hints", got, U"きんきゅうじたい=緊急事態 きんきゅうじ=緊急時 きゅうじたい=旧字体 ");
    std::remove(path.c_str());
}

static void theTextBeforeTheCursorIsCut() {
    auto one = hints({"あゆついしょう=阿諛追従"});
    std::u32string reading = U"あゆついしょうがすぎる";
    std::u32string source = hintedSource(std::u32string(100, U'あ'), reading, 100, one, 30);
    check("length of the source", std::u32string(source.size() == 30 ? U"30" : U"other"), U"30");
    check("hints and reading alone too long", hintedSource(U"い", reading, 100, one, 20), U"い\x1f" + reading);
}

static void substitution() {
    check("a word the model could not write",
          substituteHints(U"あゆついしょうがすぎる", U"アユ追唱が|過ぎる", hints({"あゆついしょう=阿諛追従"})),
          U"阿諛追従が|過ぎる");
    check("a word followed by kanji in its bunsetsu",
          substituteHints(U"かんせいこんすいにたいしては", U"完成昏睡に対しては", hints({"かんせいこんすい=肝性昏睡"})),
          U"肝性昏睡に対しては");
    check("a word the model cut in two",
          substituteHints(U"たはつかんのうようまたははいがんのたはつかんてんいをうたがい",
                          U"多発間|農用または|肺癌の|多発寒天医を|疑い",
                          hints({"たはつかんのうよう=多発肝膿瘍", "たはつかんてんい=多発肝転移"})),
          U"多発肝膿瘍または|肺癌の|多発肝転移を|疑い");
    check("a word of the dictionary the model wrote",
          substituteHints(U"りんぱせつしゅだいは", U"リンパ節腫大は",
                          hints({"りんぱせつしゅだい=リンパ節腫大", "りんぱせつしゅだい=ＬＮ腫大", "りんぱせつしゅ=リンパ節腫"})),
          U"リンパ節腫大は");
    check("another word of the dictionary the model wrote",
          substituteHints(U"じゅうでんきが", U"充電器が", hints({"じゅうでんき=充電器", "じゅうでんき=重電機"})),
          U"充電器が");
    check("a particle of the model's text",
          substituteHints(U"えいきょうから", U"影響から", hints({"えいきょうか=影響下"})), U"影響から");
    check("a reading starting inside a bunsetsu",
          substituteHints(U"ちりょうほうしんが", U"治療方針が", hints({"りょうほうし=療法士"})), U"治療方針が");
    check("a longer word that holds the reading",
          substituteHints(U"じんみんいいんぶさんじ。", U"人民委員部|参事。", hints({"じんみんいいん=人民委員"})),
          U"人民委員部|参事。");
    check("after 右", substituteHints(U"うかようむきはいをみとめます。", U"羽化用向き肺を|認めます。",
                                      hints({"かようむきはい=下葉無気肺"})),
          U"右下葉無気肺を|認めます。");
    check("after another kana", substituteHints(U"げかようむきはいを", U"下下葉無気肺を", hints({"かようむきはい=下葉無気肺"})),
          U"下下葉無気肺を");
    check("the honorific kana",
          substituteHints(U"ごりょうしょうください。", U"ご了承ください。", hints({"ごりょうしょう=御了承"})),
          U"ご了承ください。");
    check("no hints", substituteHints(U"きょうはいいてんきですね", U"今日は|いい|天気ですね", {}), U"今日は|いい|天気ですね");
}

int main(int argc, char **argv) {
    if (argc < 2) {
        std::printf("usage: hints-test DATA_DIR\n");
        return 2;
    }
    sourcesOfTheTrainingRows(argv[1]);
    theLongestReadingsFirstAndEachWordOnce();
    theTextBeforeTheCursorIsCut();
    substitution();
    if (failures == 0) {
        std::printf("hints-test: all passed\n");
    }
    return failures == 0 ? 0 : 1;
}
