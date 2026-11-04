#include "conversion/dictionary-hints.h"

#include "conversion/text.h"

#include <algorithm>
#include <fstream>
#include <set>
#include <stdexcept>

namespace llmime::conversion {

DictionaryHints::DictionaryHints(const std::string &path, size_t shortest, size_t fewestChars, size_t most)
    : most_(most) {
    std::ifstream in(path);
    if (!in) {
        throw std::runtime_error("cannot read the dictionary of hints " + path);
    }
    std::set<size_t, std::greater<>> lengths;
    std::string line;
    while (std::getline(in, line)) {
        size_t tab = line.find('\t');
        if (tab == std::string::npos) {
            continue;
        }
        size_t end = line.find('\t', tab + 1);
        std::u32string reading = toU32(line.substr(0, tab));
        std::u32string word = toU32(line.substr(tab + 1, end == std::string::npos ? std::string::npos : end - tab - 1));
        if (word.size() < fewestChars) {
            continue;
        }
        words_[reading].push_back(word);
        if (reading.size() >= shortest) {
            lengths.insert(reading.size());
        }
    }
    lengths_.assign(lengths.begin(), lengths.end());
}

std::vector<Hint> DictionaryHints::of(const std::u32string &reading) const {
    std::vector<Hint> found;
    for (size_t n : lengths_) {
        for (size_t i = 0; i + n <= reading.size(); i++) {
            std::u32string stretch = reading.substr(i, n);
            auto it = words_.find(stretch);
            if (it == words_.end()) {
                continue;
            }
            for (const auto &word : it->second) {
                bool seen = std::any_of(found.begin(), found.end(), [&](const Hint &h) { return h.word == word; });
                if (!seen) {
                    found.push_back({stretch, word});
                    if (found.size() == most_) {
                        return found;
                    }
                }
            }
        }
    }
    return found;
}

std::u32string hintedSource(const std::u32string &context, const std::u32string &reading, size_t contextChars,
                            const std::vector<Hint> &hints, size_t maxLen) {
    const char32_t sep = U'\x1f';
    std::u32string before = context.size() > contextChars ? context.substr(context.size() - contextChars) : context;
    std::u32string plain = before + sep + reading;
    if (hints.empty()) {
        return plain;
    }
    std::u32string head;
    for (const auto &h : hints) {
        head += (head.empty() ? U"" : U" ") + h.reading + U"=" + h.word;
    }
    head += sep;
    if (head.size() + 1 + reading.size() > maxLen) {
        return plain;
    }
    size_t room = maxLen - head.size() - 1 - reading.size();
    return head + (before.size() > room ? before.substr(before.size() - room) : before) + sep + reading;
}

} // namespace llmime::conversion
