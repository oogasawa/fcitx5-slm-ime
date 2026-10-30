#pragma once
// The candidates that the user chose in place of the first one, kept by reading.
//
// When the user picks another candidate for a bunsetsu, the pair (reading of the bunsetsu, chosen text) is learned.
// The next time the same reading comes up, the chosen text is put before the model's answer. The model itself is
// not changed. The choice made most often wins; among equal counts, the latest one wins. The history is a text file
// with one `reading TAB chosen text` per line, appended to at every choice and read again at start-up; deleting the
// file forgets everything. It is the same file as that of the Java conversion server of version 1.
#include <map>
#include <mutex>
#include <optional>
#include <string>
#include <vector>

namespace llmime::conversion {

class UserHistory {
public:
    // A history that is lost at exit.
    UserHistory() = default;
    // A history kept in `path`; the lines already in it are read now.
    explicit UserHistory(std::string path);

    // Records that the user chose `chosen` for the bunsetsu typed as `reading`.
    void learn(const std::u32string &reading, const std::u32string &chosen);

    // The text the user chose most often for `reading`, if any.
    std::optional<std::u32string> preferred(const std::u32string &reading) const;

    // A stretch of a typed reading, start inclusive to end exclusive, for which the user chose a text.
    struct Match {
        size_t start;
        size_t end;
        std::u32string chosen;
    };

    // The stretches of `reading` that are the reading of an earlier choice. A longer reading is taken before a
    // shorter one, and a stretch never overlaps one already taken. Readings shorter than `shortest` characters are
    // left out: a short reading such as め occurs inside many words that the user never meant. In the order of
    // their position in `reading`.
    std::vector<Match> matchesIn(const std::u32string &reading, size_t shortest) const;

    size_t size() const;

private:
    struct Choice {
        int count;
        long order;  // when it was chosen last; the larger, the later
    };
    std::optional<std::u32string> preferredLocked(const std::u32string &reading) const;
    void remember(const std::u32string &reading, const std::u32string &chosen);

    std::string path_;
    std::map<std::u32string, std::map<std::u32string, Choice>> choices_;
    long clock_ = 0;
    mutable std::mutex mutex_;
};

} // namespace llmime::conversion
