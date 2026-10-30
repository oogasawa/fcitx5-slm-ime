#pragma once
// The character vocabulary of the model: tokenizer.json, a JSON object from character to id. The ids 0 to 4 are
// [PAD], [BOS], [EOS], [UNK] and [SEP].
#include <string>
#include <unordered_map>
#include <vector>

namespace llmime::conversion {

class Tokenizer {
public:
    static constexpr int kUnk = 3;
    static constexpr int kSep = 4;
    static constexpr int kFirstCharacter = 5;

    // Throws std::runtime_error when the file cannot be read.
    explicit Tokenizer(const std::string &path);

    // One id per character; a character outside the vocabulary is [UNK].
    std::vector<int> encode(const std::u32string &text) const;
    // The characters of the ids, without the special tokens.
    std::u32string decode(const std::vector<int> &ids) const;

    int idOf(char32_t c) const;
    size_t size() const { return idToChar_.size(); }

private:
    std::unordered_map<char32_t, int> charToId_;
    std::vector<char32_t> idToChar_;
};

} // namespace llmime::conversion
