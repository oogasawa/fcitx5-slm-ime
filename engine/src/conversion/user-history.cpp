#include "conversion/user-history.h"

#include "conversion/text.h"

#include <algorithm>
#include <cstdio>
#include <fstream>
#include <tuple>

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

void UserHistory::forget(const std::u32string &reading, const std::u32string &chosen) {
    std::lock_guard<std::mutex> lock(mutex_);
    auto it = choices_.find(reading);
    if (it == choices_.end() || it->second.erase(chosen) == 0) {
        return;
    }
    if (it->second.empty()) {
        choices_.erase(it);
    }
    rewrite();
}

// Each choice is written as many times as it was made, in the order it was made last, so that reading the file
// again gives the same counts and the same latest choices. Written to another file first, which then takes the
// place of the old one, so that a crash never leaves half a history.
void UserHistory::rewrite() const {
    if (path_.empty()) {
        return;
    }
    std::vector<std::tuple<long, const std::u32string *, const std::u32string *, int>> lines;
    for (const auto &[reading, texts] : choices_) {
        for (const auto &[text, choice] : texts) {
            lines.emplace_back(choice.order, &reading, &text, choice.count);
        }
    }
    std::sort(lines.begin(), lines.end(),
              [](const auto &a, const auto &b) { return std::get<0>(a) < std::get<0>(b); });
    std::string temporary = path_ + ".new";
    {
        std::ofstream out(temporary, std::ios::trunc);
        for (const auto &[order, reading, text, count] : lines) {
            for (int i = 0; i < count; i++) {
                out << toU8(*reading) << '\t' << toU8(*text) << '\n';
            }
        }
        if (!out) {
            std::remove(temporary.c_str());
            return;
        }
    }
    std::rename(temporary.c_str(), path_.c_str());
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
