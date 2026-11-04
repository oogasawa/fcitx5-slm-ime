#pragma once
// Which bunsetsu of a commit the conversion server should learn from. Kept apart from the engine so that it can
// be tested without fcitx5 (test/corrections-test.cpp).
#include "romaji.h"

#include <string>
#include <vector>

namespace llmime {

// One bunsetsu: its reading and the text that stands for it.
struct Choice {
    std::string reading;
    std::string text;
};

// The bunsetsu the user changed. The server offered `offered` (every bunsetsu with its first candidate); the user
// committed `committed`. A committed bunsetsu is a change when the server did not offer exactly this reading with
// exactly this text: the user took another candidate, or moved a bunsetsu border so that the reading is new (the
// first candidate of a new reading is then taken as it is, and is still the user's choice).
// When `offered` is empty, nothing is known about what the server offered and no bunsetsu is a change.
inline std::vector<Choice> changedChoices(const std::vector<Choice> &offered,
                                          const std::vector<Choice> &committed) {
    std::vector<Choice> changed;
    if (offered.empty()) {
        return changed;
    }
    for (const auto &c : committed) {
        bool same = false;
        for (const auto &o : offered) {
            if (o.reading == c.reading && o.text == c.text) {
                same = true;
                break;
            }
        }
        if (!same) {
            changed.push_back(c);
        }
    }
    return changed;
}

// The bunsetsu of a commit that are left after the user erased its last `erased` characters with BackSpace: those
// none of whose characters were erased. A commit erased at once was a mistake, and is not learned.
inline std::vector<Choice> notErased(const std::vector<Choice> &committed, size_t erased) {
    size_t total = 0;
    for (const auto &c : committed) {
        total += utf8CharCount(c.text);
    }
    std::vector<Choice> kept;
    size_t end = 0;
    for (const auto &c : committed) {
        end += utf8CharCount(c.text);
        if (end + erased > total) {
            break;
        }
        kept.push_back(c);
    }
    return kept;
}

// The text the server offered for the reading of `changed`, which the user turned down by taking another; empty
// when the server offered no bunsetsu with that reading (a border was moved).
inline std::string turnedDown(const std::vector<Choice> &offered, const Choice &changed) {
    for (const auto &o : offered) {
        if (o.reading == changed.reading && o.text != changed.text) {
            return o.text;
        }
    }
    return "";
}

// What the history learns from a commit: each changed bunsetsu joined to the one before it, or to the one after it
// when it is the first, with the text turned down for the two (empty when none was). The reading of one short
// bunsetsu tells nothing of where it is meant (いる), that of two does (おかねが|いる, not へやに|いる), and the model
// that reads the choices as hints judges them by it. A commit of one bunsetsu is learned as it is.
struct Learned {
    Choice choice;
    std::string turnedDown;
};

inline std::vector<Learned> learnedChoices(const std::vector<Choice> &offered, const std::vector<Choice> &committed) {
    std::vector<Learned> learned;
    for (const auto &changed : changedChoices(offered, committed)) {
        size_t i = 0;
        while (committed[i].reading != changed.reading || committed[i].text != changed.text) {
            i++;
        }
        std::string down = turnedDown(offered, changed);
        if (committed.size() == 1) {
            learned.push_back({changed, down});
        } else if (i > 0) {
            const Choice &before = committed[i - 1];
            learned.push_back({{before.reading + changed.reading, before.text + changed.text},
                               down.empty() ? "" : before.text + down});
        } else {
            const Choice &after = committed[1];
            learned.push_back({{changed.reading + after.reading, changed.text + after.text},
                               down.empty() ? "" : down + after.text});
        }
    }
    return learned;
}

} // namespace llmime
