#include "conversion/hint-substitution.h"

#include "conversion/reading-aligner.h"

#include <algorithm>

namespace llmime::conversion {

namespace {

// The words a bunsetsu may begin with before a word of the dictionary: right, left and both sides, which the medical
// text puts in front of the names of parts of the body (うかようむきはい, 右下葉無気肺, which the dictionary has as
// 下葉無気肺). A short list, so that a hint is not taken where a kana of another word comes before it.
const std::vector<Hint> kPrefixes = {{U"う", U"右"}, {U"さ", U"左"}, {U"りょう", U"両"}};

bool isKana(char32_t c) { return (c >= 0x3041 && c <= 0x309F) || c == U'ー'; }

bool allKana(const std::u32string &text) { return std::all_of(text.begin(), text.end(), isKana); }

bool startsWith(const std::u32string &text, const std::u32string &prefix, size_t at = 0) {
    return text.size() >= at + prefix.size() && text.compare(at, prefix.size(), prefix) == 0;
}

std::vector<std::u32string> split(const std::u32string &output) {
    std::vector<std::u32string> parts(1);
    for (char32_t c : output) {
        if (c == U'|') {
            parts.emplace_back();
        } else {
            parts.back().push_back(c);
        }
    }
    return parts;
}

// Replaces bunsetsu i and those after it that the hint covers, if the rules let it. `hints` are the words of the
// dictionary for the hint's reading, to know whether the model wrote one of them.
void replace(const std::u32string &reading, std::vector<std::u32string> &texts, std::vector<std::u32string> &readings,
             size_t i, size_t start, const Hint &hint, const std::vector<Hint> &hints) {
    size_t end = start + hint.reading.size();
    size_t at = start;
    for (size_t j = i; j < texts.size(); j++) {
        at += readings[j].size();
        if (at < end) {
            continue;
        }
        std::u32string tail = reading.substr(end, at - end);
        std::u32string joined;
        for (size_t k = i; k <= j; k++) {
            joined += texts[k];
        }
        const std::u32string &word = hint.word;
        if (joined.size() < word.size()) {
            return;
        }
        std::u32string part = joined.substr(0, word.size());
        std::u32string rest = joined.substr(word.size());
        bool follows = rest.empty() ? tail.empty() : isKana(rest[0]) && !tail.empty() && tail[0] == rest[0];
        bool written = std::any_of(hints.begin(), hints.end(),
                                   [&](const Hint &h) { return h.reading == hint.reading && h.word == part; });
        bool keepsItsKana = isKana(part[0]) && word[0] != part[0];
        if (!follows || written || part.empty() || isKana(part.back()) || keepsItsKana) {
            return;
        }
        texts.erase(texts.begin() + static_cast<long>(i) + 1, texts.begin() + static_cast<long>(j) + 1);
        readings.erase(readings.begin() + static_cast<long>(i) + 1, readings.begin() + static_cast<long>(j) + 1);
        texts[i] = word + rest;
        readings[i] = reading.substr(start, at - start);
        return;
    }
}

} // namespace

std::u32string substituteHints(const std::u32string &reading, const std::u32string &output,
                               const std::vector<Hint> &hints) {
    if (hints.empty()) {
        return output;
    }
    std::vector<std::u32string> texts = split(output);
    std::u32string plain;
    for (const auto &t : texts) {
        plain += t;
    }
    if (plain.empty()) {
        return output;
    }
    std::vector<std::u32string> readings = alignReadings(reading, texts);
    for (const Hint &hint : hints) {
        size_t start = 0;
        for (size_t i = 0; i < texts.size(); i++) {
            if (startsWith(reading, hint.reading, start)) {
                replace(reading, texts, readings, i, start, hint, hints);
            } else {
                for (const Hint &prefix : kPrefixes) {
                    if (startsWith(reading, prefix.reading, start) &&
                        startsWith(reading, hint.reading, start + prefix.reading.size())) {
                        std::vector<Hint> withPrefix;
                        for (const Hint &h : hints) {
                            withPrefix.push_back({prefix.reading + h.reading, prefix.word + h.word});
                        }
                        replace(reading, texts, readings, i, start,
                                {prefix.reading + hint.reading, prefix.word + hint.word}, withPrefix);
                    }
                }
            }
            start += readings[i].size();
        }
    }
    std::u32string result;
    for (size_t i = 0; i < texts.size(); i++) {
        result += (i ? U"|" : U"") + texts[i];
    }
    return result;
}

} // namespace llmime::conversion
