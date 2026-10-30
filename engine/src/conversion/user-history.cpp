#include "conversion/user-history.h"

#include "conversion/text.h"

#include <algorithm>
#include <fstream>

namespace llmime::conversion {

namespace {

constexpr size_t kLongest = 64;

bool acceptable(const std::u32string &text) {
    return !text.empty() && text.size() <= kLongest && text.find_first_of(U"\t\n\r") == std::u32string::npos;
}

} // namespace

UserHistory::UserHistory(std::string path) : path_(std::move(path)) {
    std::ifstream in(path_);
    std::string line;
    while (std::getline(in, line)) {
        size_t tab = line.find('\t');
        if (tab != std::string::npos && tab > 0) {
            remember(toU32(line.substr(0, tab)), toU32(line.substr(tab + 1)));
        }
    }
}

void UserHistory::learn(const std::u32string &reading, const std::u32string &chosen) {
    if (!acceptable(reading) || !acceptable(chosen)) {
        return;
    }
    std::lock_guard<std::mutex> lock(mutex_);
    remember(reading, chosen);
    if (!path_.empty()) {
        std::ofstream out(path_, std::ios::app);
        out << toU8(reading) << '\t' << toU8(chosen) << '\n';
    }
}

std::optional<std::u32string> UserHistory::preferred(const std::u32string &reading) const {
    std::lock_guard<std::mutex> lock(mutex_);
    return preferredLocked(reading);
}

std::optional<std::u32string> UserHistory::preferredLocked(const std::u32string &reading) const {
    auto it = choices_.find(reading);
    if (it == choices_.end() || it->second.empty()) {
        return std::nullopt;
    }
    const std::u32string *best = nullptr;
    const Choice *bestChoice = nullptr;
    for (const auto &[text, choice] : it->second) {
        if (bestChoice == nullptr || choice.count > bestChoice->count ||
            (choice.count == bestChoice->count && choice.order > bestChoice->order)) {
            best = &text;
            bestChoice = &choice;
        }
    }
    return *best;
}

std::vector<UserHistory::Match> UserHistory::matchesIn(const std::u32string &reading, size_t shortest) const {
    std::lock_guard<std::mutex> lock(mutex_);
    std::vector<Match> found;
    for (const auto &entry : choices_) {
        const std::u32string &key = entry.first;
        if (key.size() < shortest) {
            continue;
        }
        std::u32string chosen = preferredLocked(key).value_or(U"");
        for (size_t at = reading.find(key); at != std::u32string::npos; at = reading.find(key, at + 1)) {
            found.push_back({at, at + key.size(), chosen});
        }
    }
    std::stable_sort(found.begin(), found.end(), [](const Match &a, const Match &b) {
        if (a.end - a.start != b.end - b.start) {
            return a.end - a.start > b.end - b.start;
        }
        return a.start < b.start;
    });
    std::vector<Match> taken;
    for (const Match &candidate : found) {
        bool overlaps = std::any_of(taken.begin(), taken.end(), [&](const Match &t) {
            return candidate.start < t.end && t.start < candidate.end;
        });
        if (!overlaps) {
            taken.push_back(candidate);
        }
    }
    std::sort(taken.begin(), taken.end(), [](const Match &a, const Match &b) { return a.start < b.start; });
    return taken;
}

size_t UserHistory::size() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return choices_.size();
}

void UserHistory::remember(const std::u32string &reading, const std::u32string &chosen) {
    Choice &choice = choices_[reading][chosen];
    choice.count++;
    choice.order = ++clock_;
}

} // namespace llmime::conversion
