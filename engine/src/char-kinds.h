#pragma once
// The reading of a bunsetsu written in another kind of characters (F6 to F10): hiragana, katakana, half-width
// katakana, and the typed letters, full-width or not. Kept apart from the engine so that it can be tested without
// fcitx5 (test/char-kinds-test.cpp).
#include "conversion/text.h"
#include "romaji.h"

#include <map>
#include <string>

namespace llmime {

inline std::string hiraganaToKatakana(const std::string &text) {
    std::u32string s = conversion::toU32(text);
    for (char32_t &c : s) {
        if (c >= U'ぁ' && c <= U'ゖ') {
            c += 0x60;
        } else if (c == U'ゝ' || c == U'ゞ') {
            c += 0x60;
        }
    }
    return conversion::toU8(s);
}

inline std::string katakanaToHiragana(const std::string &text) {
    std::u32string s = conversion::toU32(text);
    for (char32_t &c : s) {
        if ((c >= U'ァ' && c <= U'ヶ') || c == U'ヽ' || c == U'ヾ') {
            c -= 0x60;
        }
    }
    return conversion::toU8(s);
}

// Hiragana and katakana in half-width katakana; the voiced kana take a second character (ガ: ｶﾞ). Characters that
// have no half-width form stay as they are.
inline std::string toHalfKatakana(const std::string &text) {
    static const std::u32string full = U"。「」、・ヲァィゥェォャュョッーアイウエオカキクケコサシスセソタチツテトナニヌネノ"
                                       U"ハヒフヘホマミムメモヤユヨラリルレロワン゛゜";
    static const std::u32string voiced = U"ガギグゲゴザジズゼゾダヂヅデドバビブベボ";
    static const std::u32string unvoiced = U"カキクケコサシスセソタチツテトハヒフヘホ";
    static const std::u32string semiVoiced = U"パピプペポ";
    static const std::u32string plain = U"ハヒフヘホ";
    std::u32string out;
    for (char32_t c : conversion::toU32(hiraganaToKatakana(text))) {
        size_t at;
        if ((at = full.find(c)) != std::u32string::npos) {
            out.push_back(0xFF61 + static_cast<char32_t>(at));
        } else if ((at = voiced.find(c)) != std::u32string::npos) {
            out.push_back(0xFF61 + static_cast<char32_t>(full.find(unvoiced[at])));
            out.push_back(U'ﾞ');
        } else if ((at = semiVoiced.find(c)) != std::u32string::npos) {
            out.push_back(0xFF61 + static_cast<char32_t>(full.find(plain[at])));
            out.push_back(U'ﾟ');
        } else if (c == U'ヴ') {
            out.push_back(U'ｳ');
            out.push_back(U'ﾞ');
        } else {
            out.push_back(c);
        }
    }
    return conversion::toU8(out);
}

// ASCII in full-width forms; the space is the ideographic space.
inline std::string toWideLatin(const std::string &text) {
    std::u32string out;
    for (char32_t c : conversion::toU32(text)) {
        if (c == U' ') {
            out.push_back(0x3000);
        } else if (c > 0x20 && c < 0x7F) {
            out.push_back(c + 0xFEE0);
        } else {
            out.push_back(c);
        }
    }
    return conversion::toU8(out);
}

// Hiragana written back in the letters that type it with romajiTable: かんじ is kanji, きって is kitte. Each kana
// is written in its shortest spelling in the table (the first of equally short ones), but し, ち, つ and ふ in the
// Hepburn spelling (shi, chi, tsu, fu); ん is nn before a vowel, y or n, so that typing the letters again gives the
// same kana. Other characters stay as they are.
inline std::string kanaToRomaji(const std::string &hiragana) {
    static const std::map<std::u32string, std::string> spelling = [] {
        std::map<std::u32string, std::string> m;
        for (const auto &[romaji, kana] : romajiTable) {
            auto [at, added] = m.emplace(conversion::toU32(kana), romaji);
            if (!added && romaji.size() < at->second.size()) {
                at->second = romaji;
            }
        }
        m[U"し"] = "shi";
        m[U"ち"] = "chi";
        m[U"つ"] = "tsu";
        m[U"ふ"] = "fu";
        return m;
    }();
    const std::u32string s = conversion::toU32(katakanaToHiragana(hiragana));
    auto spelled = [&](size_t i, size_t *length) -> std::string {
        for (size_t n = 2; n >= 1; n--) {
            if (i + n <= s.size()) {
                auto it = spelling.find(s.substr(i, n));
                if (it != spelling.end()) {
                    *length = n;
                    return it->second;
                }
            }
        }
        *length = 1;
        return conversion::toU8(s.substr(i, 1));
    };
    std::string out;
    size_t i = 0;
    while (i < s.size()) {
        size_t length;
        if (s[i] == U'っ' && i + 1 < s.size()) {
            std::string next = spelled(i + 1, &length);
            char first = next.empty() ? '\0' : next[0];
            if (first >= 'a' && first <= 'z' && std::string("aiueon").find(first) == std::string::npos) {
                out += first;
                i++;
                continue;
            }
        }
        if (s[i] == U'ん') {
            std::string next = i + 1 < s.size() ? spelled(i + 1, &length) : std::string();
            out += !next.empty() && std::string("aiueoyn").find(next[0]) != std::string::npos ? "nn" : "n";
            i++;
            continue;
        }
        out += spelled(i, &length);
        i += length;
    }
    return out;
}

// The next form of letters when the same key is pressed again: lower case, then upper case, then the first letter
// in upper case, then lower case again. `lower` is the letters in lower case; `shown` is what is shown now.
inline std::string nextLatinForm(const std::string &lower, const std::string &shown) {
    std::string upper = lower;
    for (char &c : upper) {
        if (c >= 'a' && c <= 'z') {
            c -= 32;
        }
    }
    std::string capital = lower;
    if (!capital.empty() && capital[0] >= 'a' && capital[0] <= 'z') {
        capital[0] -= 32;
    }
    if (shown == lower && upper != lower) {
        return upper;
    }
    if (shown == upper && capital != upper) {
        return capital;
    }
    return lower;
}

} // namespace llmime
