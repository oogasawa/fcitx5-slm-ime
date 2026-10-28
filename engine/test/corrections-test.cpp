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

    if (failures == 0) {
        std::printf("corrections-test: all passed\n");
    }
    return failures == 0 ? 0 : 1;
}
