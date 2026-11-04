#pragma once
// The words of a dictionary that a typed reading may hold, which a model trained with hints (prep/add_hints.py)
// reads in front of its source as "reading=word reading=word ... SEP", and copies where they fit. The words are
// those whose reading is a stretch of the typed reading at least `shortest` kana long and that are at least
// `fewestChars` characters long, the longest readings first, at most `most` of them: the rules of add_hints.py, so
// that the model gets the form it was trained on. The words it could not copy are put in by HintSubstitution.
//
// Read once and not changed after, so that any thread may use it.
#include <string>
#include <unordered_map>
#include <vector>

namespace llmime::conversion {

struct Hint {
    std::u32string reading;
    std::u32string word;
};

class DictionaryHints {
public:
    // No words: no hints.
    DictionaryHints() = default;
    // Reads a table of "reading TAB word [TAB kind [TAB field]]" lines (prep/hard_words.py), keeping the words of at
    // least `fewestChars` characters in the order of the file. Throws std::runtime_error when it cannot be read.
    // The defaults are the limits the model of version 4 was trained with.
    explicit DictionaryHints(const std::string &path, size_t shortest = 6, size_t fewestChars = 3, size_t most = 4);

    bool empty() const { return words_.empty(); }
    // The words whose readings are stretches of the reading, the longest readings first.
    std::vector<Hint> of(const std::u32string &reading) const;

private:
    std::unordered_map<std::u32string, std::vector<std::u32string>> words_;
    std::vector<size_t> lengths_; // of the readings at least `shortest` long, the longest first
    size_t most_ = 4;
};

// The source of a model trained with hints, as text, with U+001F where the tokenizer is to put SEP:
// "reading=word ... SEP <text before the cursor> SEP <reading>", the text before the cursor cut to its last
// `contextChars` characters and then from the left so that the source stays within `maxLen` characters. Without
// hints, or when the hints and the reading alone are too long, "<text before the cursor> SEP <reading>".
std::u32string hintedSource(const std::u32string &context, const std::u32string &reading, size_t contextChars,
                            const std::vector<Hint> &hints, size_t maxLen);

} // namespace llmime::conversion
