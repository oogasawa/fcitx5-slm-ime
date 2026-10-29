#pragma once
// The text the user committed in one input context, kept by the engine. It stands for the text before the cursor
// when the application does not tell it, and it tells whether the last commit can be taken back. Kept apart from the
// engine so that it can be tested without fcitx5 (test/committed-text-test.cpp).
#include "romaji.h"

#include <string>

namespace llmime {

// The last `chars` characters of the UTF-8 string `s` (all of `s` when it is shorter). A character is never cut.
inline std::string lastChars(const std::string &s, size_t chars) {
    size_t total = utf8CharCount(s);
    if (total <= chars) {
        return s;
    }
    return utf8Substr(s, total - chars, chars);
}

// Whether `s` ends with `suffix`. An empty suffix is not taken as an end, because there is nothing to take back.
inline bool endsWithText(const std::string &s, const std::string &suffix) {
    return !suffix.empty() && s.size() >= suffix.size() &&
           s.compare(s.size() - suffix.size(), suffix.size(), suffix) == 0;
}

// `s` without `suffix` at its end; `s` as it is when it does not end with `suffix`.
inline std::string withoutEnd(const std::string &s, const std::string &suffix) {
    return endsWithText(s, suffix) ? s.substr(0, s.size() - suffix.size()) : s;
}

// The committed text after `text` was committed: the two joined, cut to the last `chars` characters.
inline std::string appendCommitted(const std::string &committed, const std::string &text, size_t chars) {
    return lastChars(committed + text, chars);
}

} // namespace llmime
