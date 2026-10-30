#pragma once
// UTF-8 and UTF-32 strings. The conversion works on characters (code points), so its strings are std::u32string;
// fcitx5 and the files speak UTF-8.
#include <string>

namespace llmime::conversion {

inline std::u32string toU32(const std::string &s) {
    std::u32string out;
    out.reserve(s.size());
    for (size_t i = 0; i < s.size();) {
        unsigned char c = static_cast<unsigned char>(s[i]);
        char32_t cp;
        size_t n;
        if (c < 0x80) {
            cp = c;
            n = 1;
        } else if ((c >> 5) == 0x6) {
            cp = c & 0x1F;
            n = 2;
        } else if ((c >> 4) == 0xE) {
            cp = c & 0x0F;
            n = 3;
        } else if ((c >> 3) == 0x1E) {
            cp = c & 0x07;
            n = 4;
        } else {
            cp = 0xFFFD;  // a stray byte
            n = 1;
        }
        if (i + n > s.size()) {
            out.push_back(0xFFFD);
            break;
        }
        for (size_t k = 1; k < n; k++) {
            cp = (cp << 6) | (static_cast<unsigned char>(s[i + k]) & 0x3F);
        }
        out.push_back(cp);
        i += n;
    }
    return out;
}

inline std::string toU8(const std::u32string &s) {
    std::string out;
    out.reserve(s.size() * 3);
    for (char32_t cp : s) {
        if (cp < 0x80) {
            out.push_back(static_cast<char>(cp));
        } else if (cp < 0x800) {
            out.push_back(static_cast<char>(0xC0 | (cp >> 6)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        } else if (cp < 0x10000) {
            out.push_back(static_cast<char>(0xE0 | (cp >> 12)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        } else {
            out.push_back(static_cast<char>(0xF0 | (cp >> 18)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 12) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        }
    }
    return out;
}

inline bool isKanji(char32_t c) {
    return (c >= U'一' && c <= U'鿿') || (c >= U'㐀' && c <= U'䶿') || c == U'々' || c == U'〆' ||
           (c >= U'豈' && c <= U'﫿');
}

inline bool isKana(char32_t c) {
    return (c >= U'ぁ' && c <= U'ゖ') || (c >= U'ァ' && c <= U'ヺ') || c == U'ー' || c == U'ヽ' || c == U'ヾ' ||
           c == U'ゝ' || c == U'ゞ';
}

inline char32_t toHiragana(char32_t c) { return c >= U'ァ' && c <= U'ヶ' ? c - 0x60 : c; }

// The character as NFKC would make it, for the characters the alignment meets: full-width ASCII and the
// ideographic space. Others are left as they are.
inline char32_t foldWidth(char32_t c) {
    if (c >= 0xFF01 && c <= 0xFF5E) {
        return c - 0xFEE0;
    }
    if (c == 0x3000) {
        return U' ';
    }
    return c;
}

} // namespace llmime::conversion
