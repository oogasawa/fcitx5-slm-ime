// Tests of the conversion without the model: the reading of every bunsetsu, the user's choices, the choices put into
// the model's answer, the characters at the end of a reading put back, and the candidates of Anthy (skipped when
// Anthy cannot be started). They are the tests of the Java conversion server of version 1, moved to C++.
#include "conversion/candidate-source.h"
#include "conversion/converter.h"
#include "conversion/history-substitution.h"
#include "conversion/reading-aligner.h"
#include "conversion/text.h"
#include "conversion/user-history.h"

#include <cstdio>
#include <fstream>
#include <string>
#include <unistd.h>
#include <vector>

using namespace llmime::conversion;

static int failures = 0;

static std::string joined(const std::vector<std::u32string> &parts) {
    std::string s;
    for (size_t i = 0; i < parts.size(); i++) {
        s += (i ? "|" : "") + toU8(parts[i]);
    }
    return s;
}

static void check(const char *what, const std::string &got, const std::string &want) {
    if (got != want) {
        std::printf("FAIL %s: got \"%s\", want \"%s\"\n", what, got.c_str(), want.c_str());
        failures++;
    }
}

static void check(const char *what, bool ok) {
    if (!ok) {
        std::printf("FAIL %s\n", what);
        failures++;
    }
}

static std::vector<std::u32string> split(const std::string &text) {
    std::vector<std::u32string> parts;
    std::u32string all = toU32(text), part;
    for (char32_t c : all) {
        if (c == U'|') {
            parts.push_back(part);
            part.clear();
        } else {
            part.push_back(c);
        }
    }
    parts.push_back(part);
    return parts;
}

static void readingAligner() {
    check("kana of the output fix the boundaries",
          joined(alignReadings(U"きかいがくしゅうのあるごりずむ", {U"機械学習", U"の", U"アルゴリズム"})),
          "きかいがくしゅう|の|あるごりずむ");
    check("particles after nouns stay with their bunsetsu",
          joined(alignReadings(U"げんだいのふぉんとでも、", {U"現代の", U"フォントでも、"})), "げんだいの|ふぉんとでも、");
    check("digits and brackets match themselves",
          joined(alignReadings(U"2022ねん（れいわ4ねん）8がつ", {U"2022年（令和4年）", U"8月"})),
          "2022ねん（れいわ4ねん）|8がつ");
    check("okurigana separates kanji runs", joined(alignReadings(U"おこなわれたしけん", {U"行われた", U"試験"})),
          "おこなわれた|しけん");
    check("a single bunsetsu takes the whole reading", joined(alignReadings(U"きかいがくしゅう", {U"機械学習"})),
          "きかいがくしゅう");
    auto pieces = alignReadings(U"ええとこれはまったくちがうよみのもじれつ", {U"全然", U"別の", U"文章", U"です", U"ね"});
    std::u32string all;
    for (auto &p : pieces) all += p;
    check("the readings always join back to the typed text", all == U"ええとこれはまったくちがうよみのもじれつ");
    check("the readings are as many as the bunsetsu", pieces.size() == 5);
    check("when nothing fits the reading is cut in proportion",
          joined(alignReadings(U"あいうえおかきくけこ", {U"ABCDE", U"FGHIJ"})), "あいうえお|かきくけこ");
}

static void userHistory() {
    UserHistory h;
    check("nothing learned yet", !h.preferred(U"ばい").has_value());
    h.learn(U"ばい", U"倍");
    check("a learned choice is returned for the same reading", h.preferred(U"ばい").value_or(U"") == U"倍");
    check("not for a longer reading", !h.preferred(U"ばいは").has_value());

    UserHistory often;
    often.learn(U"こうせい", U"構成");
    often.learn(U"こうせい", U"公正");
    check("the latest wins a tie", often.preferred(U"こうせい").value_or(U"") == U"公正");
    often.learn(U"こうせい", U"構成");
    check("the choice made most often wins", often.preferred(U"こうせい").value_or(U"") == U"構成");

    UserHistory stretches;
    stretches.learn(U"でぷろい", U"デプロイ");
    stretches.learn(U"ぷろいで", U"プロイで");
    stretches.learn(U"されたかな", U"されたかな");
    auto matches = stretches.matchesIn(U"でぷろいされたかな", 3);
    check("the stretches are found longest first without overlap",
          matches.size() == 2 && matches[0].start == 0 && matches[0].end == 4 && matches[0].chosen == U"デプロイ" &&
              matches[1].start == 4 && matches[1].end == 9 && matches[1].chosen == U"されたかな");

    UserHistory shortOnes;
    shortOnes.learn(U"め", U"目");
    shortOnes.learn(U"ばい", U"倍");
    check("a reading shorter than the limit is not found", shortOnes.matchesIn(U"はじめばいばい", 3).empty());

    char path[] = "/tmp/conversion-test-XXXXXX";
    int fd = mkstemp(path);
    close(fd);
    {
        UserHistory first{std::string(path)};
        first.learn(U"ばい", U"倍");
    }
    UserHistory second{std::string(path)};
    check("the history is read back after a restart", second.preferred(U"ばい").value_or(U"") == U"倍");
    std::ifstream in(path);
    std::string line, all;
    while (std::getline(in, line)) all += line + "\n";
    check("the history file", all, "ばい\t倍\n");
    unlink(path);

    UserHistory bad;
    bad.learn(U"", U"倍");
    bad.learn(U"ばい", U"");
    bad.learn(U"ば\tい", U"倍");
    bad.learn(U"ばい", U"倍\n");
    check("empty text and text with tabs or newlines is not learned", bad.size() == 0);
}

static std::string firstTexts(const Substituted &s) { return joined(s.texts); }

static void substitution() {
    UserHistory h;
    h.learn(U"でぷろい", U"デプロイ");
    auto one = substituteHistory(U"でぷろいされましたか", split("でプロイされましたか"), h);
    check("a choice for part of a bunsetsu is put into the model's answer", firstTexts(one), "デプロイされましたか");
    check("the model's own answer stays as the original", joined(one.originals), "でプロイされましたか");
    check("its reading", joined(one.readings), "でぷろいされましたか");

    auto across = substituteHistory(U"でぷろいされたかな", split("で|プロイされたかな"), h);
    check("a choice across a bunsetsu border joins the bunsetsu", firstTexts(across), "デプロイされたかな");
    check("the joined reading", joined(across.readings), "でぷろいされたかな");

    auto kept = substituteHistory(U"あたらしいもでるがでぷろいされた", split("新しいモデル|がでプロイ|された"), h);
    check("the bunsetsu next to the choice are kept", firstTexts(kept), "新しいモデル|がデプロイ|された");
    check("their readings", joined(kept.readings), "あたらしいもでる|がでぷろい|された");

    UserHistory me;
    me.learn(U"め", U"目");
    check("a short reading is not looked for inside a longer one",
          firstTexts(substituteHistory(U"はじめまして", split("初めまして"), me)), "初めまして");

    UserHistory kikai;
    kikai.learn(U"きかいが", U"機会が");
    check("a choice whose end lies inside a run of kanji is left out",
          firstTexts(substituteHistory(U"きかいがくしゅう", split("機械学習"), kikai)), "機械学習");
}

static void coveredEnds() {
    check("the left-out end is copied back", toU8(covered(U"せいどがよ", U"精度が")), "精度がよ");
    check("the left-out kana after a particle", toU8(covered(U"きかいがくしゅうのあ", U"機械学習の")), "機械学習のあ");
    check("after katakana", toU8(covered(U"もでるをで", U"モデルを")), "モデルをで");
    check("two kana", toU8(covered(U"をでぷろいしました", U"をでプロイしまし")), "をでプロイしました");
    check("an answer that follows the whole reading is left alone", toU8(covered(U"せいどが", U"精度が")), "精度が");
    check("倍は", toU8(covered(U"ばいは", U"倍は")), "倍は");
    check("katakana", toU8(covered(U"あるごりずむ", U"アルゴリズム")), "アルゴリズム");
    check("kanji only", toU8(covered(U"しゃかいほしょう", U"社会保障")), "社会保障");
    check("an answer with letters is not checked", toU8(covered(U"えーあい", U"AI")), "AI");
    check("digits", toU8(covered(U"さんてんごばい", U"3.5倍")), "3.5倍");
}

static void anthy() {
    std::unique_ptr<AnthyCandidates> source;
    try {
        source = std::make_unique<AnthyCandidates>();
    } catch (const std::exception &e) {
        std::printf("skipped the Anthy tests: %s\n", e.what());
        return;
    }
    auto kyou = source->candidates(U"きょうは", 8);
    check("a reading of several words is offered as one bunsetsu", !kyou.empty() && kyou[0] == U"今日は");
    bool hasReading = false;
    for (auto &c : kyou) hasReading = hasReading || c == U"きょうは";
    check("the reading itself is among the candidates", hasReading);
    auto tenki = source->candidates(U"いいてんきですね", 8);
    check("the first candidate is Anthy's own conversion of several words",
          !tenki.empty() && tenki[0].find(U"天気") != std::u32string::npos);
    check("the limit is kept", source->candidates(U"きょうは", 3).size() <= 3);
    bool allLong = true;
    for (auto &c : source->candidates(U"きかいがくしゅうのあるごりずむ", 5)) allLong = allLong && c.size() >= 5;
    check("a long reading stays one bunsetsu", allLong);
    check("an empty reading gives nothing", source->candidates(U"", 5).empty());
}

int main() {
    readingAligner();
    userHistory();
    substitution();
    coveredEnds();
    anthy();
    if (failures == 0) {
        std::printf("conversion-test: all passed\n");
    }
    return failures == 0 ? 0 : 1;
}
