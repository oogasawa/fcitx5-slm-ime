#include "conversion/tokenizer.h"

#include "conversion/text.h"

#include <nlohmann/json.hpp>

#include <fstream>
#include <stdexcept>

namespace llmime::conversion {

Tokenizer::Tokenizer(const std::string &path) {
    std::ifstream in(path);
    if (!in) {
        throw std::runtime_error("cannot read the tokenizer " + path);
    }
    auto vocab = nlohmann::json::parse(in);
    for (const auto &[key, value] : vocab.items()) {
        int id = value.get<int>();
        if (id < 0) {
            continue;
        }
        if (static_cast<size_t>(id) >= idToChar_.size()) {
            idToChar_.resize(static_cast<size_t>(id) + 1, 0);
        }
        if (id < kFirstCharacter) {
            continue;  // the special tokens are not characters
        }
        std::u32string chars = toU32(key);
        if (chars.size() == 1) {
            charToId_[chars[0]] = id;
            idToChar_[static_cast<size_t>(id)] = chars[0];
        }
    }
}

int Tokenizer::idOf(char32_t c) const {
    auto it = charToId_.find(c);
    return it == charToId_.end() ? kUnk : it->second;
}

std::vector<int> Tokenizer::encode(const std::u32string &text) const {
    std::vector<int> ids;
    ids.reserve(text.size());
    for (char32_t c : text) {
        ids.push_back(idOf(c));
    }
    return ids;
}

std::u32string Tokenizer::decode(const std::vector<int> &ids) const {
    std::u32string text;
    for (int id : ids) {
        if (id >= kFirstCharacter && static_cast<size_t>(id) < idToChar_.size() && idToChar_[id] != 0) {
            text.push_back(idToChar_[static_cast<size_t>(id)]);
        }
    }
    return text;
}

} // namespace llmime::conversion
