// Tests of the bunsetsu that are learned (src/corrections.h). No fcitx5 is needed.
#include "corrections.h"

#include <cstdio>
#include <string>
#include <vector>

using namespace llmime;

static int failures = 0;

static void check(const char *what, const std::vector<Choice> &got, const std::vector<Choice> &want) {
    bool same = got.size() == want.size();
    for (size_t i = 0; same && i < got.size(); i++) {
        same = got[i].reading == want[i].reading && got[i].text == want[i].text;
    }
    if (!same) {
        std::printf("FAIL %s: got %zu bunsetsu, want %zu\n", what, got.size(), want.size());
        failures++;
    }
}

int main() {
    const std::vector<Choice> offered = {{"どうで", "銅で"}, {"しょう", "賞"}};

    // nothing changed
    check("unchanged", changedChoices(offered, offered), {});

    // another candidate taken for the second bunsetsu
    check("other candidate", changedChoices(offered, {{"どうで", "銅で"}, {"しょう", "しょう"}}),
          {{"しょう", "しょう"}});

    // the border was moved: the whole reading is one bunsetsu whose first candidate was taken as it is
    check("border moved", changedChoices(offered, {{"どうでしょう", "どうでしょう"}}),
          {{"どうでしょう", "どうでしょう"}});

    // the border was moved and moved back
    check("moved back", changedChoices(offered, {{"どうで", "銅で"}, {"しょう", "賞"}}), {});

    // only the changed bunsetsu are learned
    check("one of two", changedChoices(offered, {{"どう", "動"}, {"でしょう", "でしょう"}}),
          {{"どう", "動"}, {"でしょう", "でしょう"}});

    // the same reading with another text is a change
    check("same reading", changedChoices(offered, {{"どうで", "同で"}, {"しょう", "賞"}}), {{"どうで", "同で"}});

    // nothing is known about what was offered
    check("nothing offered", changedChoices({}, {{"どうで", "銅で"}}), {});

    // a commit erased at once
    const std::vector<Choice> committed = {{"なにが", "何が"}, {"いるの", "要るの"}};
    check("nothing erased", notErased(committed, 0), committed);
    check("the end of the last bunsetsu erased", notErased(committed, 1), {{"なにが", "何が"}});
    check("the last bunsetsu erased", notErased(committed, 3), {{"なにが", "何が"}});
    check("into the bunsetsu before", notErased(committed, 4), {});
    check("more than the commit", notErased(committed, 9), {});

    // a changed bunsetsu is learned with the one before it, the first with the one after it
    {
        const std::vector<Choice> shown = {{"おかねが", "お金が"}, {"いる", "居る"}, {"のかな", "のかな"}};
        auto got = learnedChoices(shown, {{"おかねが", "お金が"}, {"いる", "要る"}, {"のかな", "のかな"}});
        auto first = learnedChoices(shown, {{"おかねが", "おかねが"}, {"いる", "居る"}, {"のかな", "のかな"}});
        auto alone = learnedChoices({{"いる", "居る"}}, {{"いる", "要る"}});
        if (got.size() != 1 || got[0].choice.reading != "おかねがいる" || got[0].choice.text != "お金が要る" ||
            got[0].turnedDown != "お金が居る" || first.size() != 1 || first[0].choice.reading != "おかねがいる" ||
            first[0].choice.text != "おかねが居る" || first[0].turnedDown != "お金が居る" || alone.size() != 1 ||
            alone[0].choice.text != "要る" || alone[0].turnedDown != "居る") {
            std::printf("FAIL learnedChoices\n");
            failures++;
        }
    }

    // the text offered for the same reading is the one turned down
    if (turnedDown(offered, {"しょう", "しょう"}) != "賞" || !turnedDown(offered, {"どうでしょう", "どうでしょう"}).empty()) {
        std::printf("FAIL turnedDown\n");
        failures++;
    }

    if (failures == 0) {
        std::printf("corrections-test: all passed\n");
    }
    return failures == 0 ? 0 : 1;
}
