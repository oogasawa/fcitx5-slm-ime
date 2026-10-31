// Tests of the table that slm-ime-keys shows: every operation once, in its group, with the keys of each profile and
// the column of the profile in effect marked; the texts are escaped.
#include "key-table.h"

#include <cstdio>
#include <string>

using namespace llmime;

static int failures = 0;

static void check(const char *what, bool ok) {
    if (!ok) {
        std::printf("FAIL %s\n", what);
        failures++;
    }
}

static size_t count(const std::string &s, const std::string &part) {
    size_t n = 0;
    for (size_t at = s.find(part); at != std::string::npos; at = s.find(part, at + 1)) n++;
    return n;
}

int main() {
    KeyTableText text;
    text.title = "T<itle>";
    text.operation = "Op";
    text.inEffect = "Now";
    text.current = " *";
    text.typing = "G-typing";
    text.converting = "G-converting";
    text.both = "G-both";
    text.translate = [](const char *s) { return "[" + std::string(s) + "]"; };
    std::vector<std::vector<std::string>> inEffect(actionInfos().size(), std::vector<std::string>{"X"});
    std::string html = keyTableHtml(text, inEffect, KeyProfile::MSIME, [](const std::string &k) { return "<" + k + ">"; });

    check("the title is escaped", html.find("T&lt;itle&gt;") != std::string::npos && html.find("T<itle>") == std::string::npos);
    for (const auto &info : actionInfos()) {
        std::string row = "<tr><td>[" + std::string(info.label) + "]</td>";
        if (count(html, row) != 1) {
            std::printf("FAIL the operation %s is not in the table once\n", info.name);
            failures++;
        }
    }
    check("the three groups in order", html.find("G-typing") < html.find("G-converting") &&
                                           html.find("G-converting") < html.find("G-both"));
    check("Convert is in the group while typing", actionGroup(Action::Convert) == 0 && actionGroup(Action::CaretLast) == 0);
    check("the bunsetsu operations are in the group while converting",
          actionGroup(Action::BackToReading) == 1 && actionGroup(Action::ShrinkSegment) == 1);
    check("F6 to F10 and the take-back are in both", actionGroup(Action::ToHiragana) == 2 && actionGroup(Action::TakeBack) == 2);
    check("the profile in effect is marked once in the header", count(html, "<th class=\"current\">[MS-IME] *</th>") == 1);
    check("its column is marked in every row", count(html, "<td class=\"current\">") == actionInfos().size());
    check("the keys are escaped", html.find("&lt;space&gt;") != std::string::npos && html.find("<space>") == std::string::npos);
    // the row of Convert: the keys in effect, then Default, MS-IME (marked) and ATOK
    check("the keys of each profile in the row of Convert",
          html.find("<tr><td>[Convert]</td><td><kbd>&lt;X&gt;</kbd></td><td><kbd>&lt;space&gt;</kbd></td>"
                    "<td class=\"current\"><kbd>&lt;space&gt;</kbd> <kbd>&lt;Shift+space&gt;</kbd> "
                    "<kbd>&lt;Henkan&gt;</kbd></td><td><kbd>&lt;space&gt;</kbd> <kbd>&lt;Henkan&gt;</kbd></td></tr>") !=
              std::string::npos);
    std::string custom = keyTableHtml(text, inEffect, KeyProfile::Custom, [](const std::string &k) { return k; });
    check("with the Custom profile no column is marked", custom.find("class=\"current\"") == std::string::npos);

    if (failures == 0) {
        std::printf("key-table-test: all passed\n");
    }
    return failures == 0 ? 0 : 1;
}
