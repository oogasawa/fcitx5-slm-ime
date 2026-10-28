#pragma once
// Romaji to hiragana conversion, UTF-8 helpers, and the cursor of the reading while the user is still typing.
// Kept apart from the engine so that they can be tested without fcitx5 (test/romaji-test.cpp).
#include <cstddef>
#include <string>
#include <utility>
#include <vector>

namespace llmime {

// Simple romaji to hiragana conversion table
inline const std::vector<std::pair<std::string, std::string>> romajiTable = {
    // Double consonants (must come before single)
    {"kya", "きゃ"}, {"kyi", "きぃ"}, {"kyu", "きゅ"}, {"kye", "きぇ"}, {"kyo", "きょ"},
    {"sha", "しゃ"}, {"shi", "し"}, {"shu", "しゅ"}, {"she", "しぇ"}, {"sho", "しょ"},
    {"sya", "しゃ"}, {"syi", "しぃ"}, {"syu", "しゅ"}, {"sye", "しぇ"}, {"syo", "しょ"},
    {"cha", "ちゃ"}, {"chi", "ち"}, {"chu", "ちゅ"}, {"che", "ちぇ"}, {"cho", "ちょ"},
    {"cya", "ちゃ"}, {"cyi", "ちぃ"}, {"cyu", "ちゅ"}, {"cye", "ちぇ"}, {"cyo", "ちょ"},
    {"tya", "ちゃ"}, {"tyi", "ちぃ"}, {"tyu", "ちゅ"}, {"tye", "ちぇ"}, {"tyo", "ちょ"},
    {"nya", "にゃ"}, {"nyi", "にぃ"}, {"nyu", "にゅ"}, {"nye", "にぇ"}, {"nyo", "にょ"},
    {"hya", "ひゃ"}, {"hyi", "ひぃ"}, {"hyu", "ひゅ"}, {"hye", "ひぇ"}, {"hyo", "ひょ"},
    {"mya", "みゃ"}, {"myi", "みぃ"}, {"myu", "みゅ"}, {"mye", "みぇ"}, {"myo", "みょ"},
    {"rya", "りゃ"}, {"ryi", "りぃ"}, {"ryu", "りゅ"}, {"rye", "りぇ"}, {"ryo", "りょ"},
    {"gya", "ぎゃ"}, {"gyi", "ぎぃ"}, {"gyu", "ぎゅ"}, {"gye", "ぎぇ"}, {"gyo", "ぎょ"},
    {"ja", "じゃ"}, {"ji", "じ"}, {"ju", "じゅ"}, {"je", "じぇ"}, {"jo", "じょ"},
    {"jya", "じゃ"}, {"jyi", "じぃ"}, {"jyu", "じゅ"}, {"jye", "じぇ"}, {"jyo", "じょ"},
    {"dya", "ぢゃ"}, {"dyi", "ぢぃ"}, {"dyu", "ぢゅ"}, {"dye", "ぢぇ"}, {"dyo", "ぢょ"},
    {"zya", "じゃ"}, {"zyi", "じぃ"}, {"zyu", "じゅ"}, {"zye", "じぇ"}, {"zyo", "じょ"},
    {"bya", "びゃ"}, {"byi", "びぃ"}, {"byu", "びゅ"}, {"bye", "びぇ"}, {"byo", "びょ"},
    {"pya", "ぴゃ"}, {"pyi", "ぴぃ"}, {"pyu", "ぴゅ"}, {"pye", "ぴぇ"}, {"pyo", "ぴょ"},
    {"tsu", "つ"}, {"dzu", "づ"},
    // Foreign sounds (must come before 2-char entries)
    {"fa", "ふぁ"}, {"fi", "ふぃ"}, {"fu", "ふ"}, {"fe", "ふぇ"}, {"fo", "ふぉ"},
    {"va", "ゔぁ"}, {"vi", "ゔぃ"}, {"vu", "ゔ"}, {"ve", "ゔぇ"}, {"vo", "ゔぉ"},
    {"tha", "てぁ"}, {"thi", "てぃ"}, {"thu", "てゅ"}, {"the", "てぇ"}, {"tho", "てょ"},
    {"dha", "でぁ"}, {"dhi", "でぃ"}, {"dhu", "でゅ"}, {"dhe", "でぇ"}, {"dho", "でょ"},
    {"twa", "とぁ"}, {"twi", "とぃ"}, {"twu", "とぅ"}, {"twe", "とぇ"}, {"two", "とぉ"},
    {"dwa", "どぁ"}, {"dwi", "どぃ"}, {"dwu", "どぅ"}, {"dwe", "どぇ"}, {"dwo", "どぉ"},
    {"wha", "うぁ"}, {"whi", "うぃ"}, {"whu", "う"}, {"whe", "うぇ"}, {"who", "うぉ"},
    {"qya", "くゃ"}, {"qyi", "くぃ"}, {"qyu", "くゅ"}, {"qye", "くぇ"}, {"qyo", "くょ"},
    {"qa", "くぁ"}, {"qi", "くぃ"}, {"qe", "くぇ"}, {"qo", "くぉ"},
    {"gwa", "ぐぁ"}, {"gwi", "ぐぃ"}, {"gwu", "ぐぅ"}, {"gwe", "ぐぇ"}, {"gwo", "ぐぉ"},
    {"tsa", "つぁ"}, {"tsi", "つぃ"}, {"tse", "つぇ"}, {"tso", "つぉ"},
    {"ka", "か"}, {"ki", "き"}, {"ku", "く"}, {"ke", "け"}, {"ko", "こ"},
    {"sa", "さ"}, {"si", "し"}, {"su", "す"}, {"se", "せ"}, {"so", "そ"},
    {"ta", "た"}, {"ti", "ち"}, {"tu", "つ"}, {"te", "て"}, {"to", "と"},
    {"na", "な"}, {"ni", "に"}, {"nu", "ぬ"}, {"ne", "ね"}, {"no", "の"},
    {"ha", "は"}, {"hi", "ひ"}, {"hu", "ふ"}, {"he", "へ"}, {"ho", "ほ"},
    {"ma", "ま"}, {"mi", "み"}, {"mu", "む"}, {"me", "め"}, {"mo", "も"},
    {"ya", "や"}, {"yi", "い"}, {"yu", "ゆ"}, {"ye", "いぇ"}, {"yo", "よ"},
    {"ra", "ら"}, {"ri", "り"}, {"ru", "る"}, {"re", "れ"}, {"ro", "ろ"},
    {"wa", "わ"}, {"wi", "ゐ"}, {"we", "ゑ"}, {"wo", "を"},
    {"ga", "が"}, {"gi", "ぎ"}, {"gu", "ぐ"}, {"ge", "げ"}, {"go", "ご"},
    {"za", "ざ"}, {"zi", "じ"}, {"zu", "ず"}, {"ze", "ぜ"}, {"zo", "ぞ"},
    {"da", "だ"}, {"di", "ぢ"}, {"du", "づ"}, {"de", "で"}, {"do", "ど"},
    {"ba", "ば"}, {"bi", "び"}, {"bu", "ぶ"}, {"be", "べ"}, {"bo", "ぼ"},
    {"pa", "ぱ"}, {"pi", "ぴ"}, {"pu", "ぷ"}, {"pe", "ぺ"}, {"po", "ぽ"},
    {"nn", "ん"}, {"n'", "ん"},
    {"a", "あ"}, {"i", "い"}, {"u", "う"}, {"e", "え"}, {"o", "お"},
    // Small kana (x- and l- prefixes, both supported like MS-IME)
    {"xya", "ゃ"}, {"xyu", "ゅ"}, {"xyo", "ょ"}, {"xtu", "っ"}, {"xtsu", "っ"},
    {"lya", "ゃ"}, {"lyu", "ゅ"}, {"lyo", "ょ"}, {"ltu", "っ"}, {"ltsu", "っ"},
    {"xa", "ぁ"}, {"xi", "ぃ"}, {"xu", "ぅ"}, {"xe", "ぇ"}, {"xo", "ぉ"},
    {"la", "ぁ"}, {"li", "ぃ"}, {"lu", "ぅ"}, {"le", "ぇ"}, {"lo", "ぉ"},
    {"xwa", "ゎ"}, {"lwa", "ゎ"}, {"xke", "ヶ"}, {"xka", "ヵ"},
    {"-", "ー"},
    // Punctuation
    {",", "、"}, {".", "。"},
    {"!", "！"}, {"?", "？"},
    {"/", "・"}, {"~", "〜"},
    {"[", "「"}, {"]", "」"},
    {"{", "｛"}, {"}", "｝"},
    {"(", "（"}, {")", "）"},
};

// Convert romaji string to hiragana
inline std::string romajiToHiragana(const std::string &input) {
    std::string result;
    size_t i = 0;
    while (i < input.size()) {
        // Handle double consonant (sokuon): same consonant twice
        if (i + 1 < input.size() && input[i] == input[i + 1] &&
            input[i] != 'a' && input[i] != 'i' && input[i] != 'u' &&
            input[i] != 'e' && input[i] != 'o' && input[i] != 'n') {
            result += "っ";
            i++;
            continue;
        }

        bool found = false;
        // Try longest match first (4 chars, then 3, then 2, then 1)
        for (int len = 4; len >= 1; len--) {
            if (i + len > input.size()) continue;
            std::string sub = input.substr(i, len);
            for (const auto &[romaji, kana] : romajiTable) {
                if (sub == romaji) {
                    result += kana;
                    i += len;
                    found = true;
                    break;
                }
            }
            if (found) break;
        }
        if (!found) {
            // Handle 'n' before non-vowel or end of string
            if (input[i] == 'n' &&
                (i + 1 >= input.size() ||
                 (input[i + 1] != 'a' && input[i + 1] != 'i' &&
                  input[i + 1] != 'u' && input[i + 1] != 'e' &&
                  input[i + 1] != 'o' && input[i + 1] != 'y'))) {
                result += "ん";
                i++;
            } else {
                // Pass through unchanged
                result += input[i];
                i++;
            }
        }
    }
    return result;
}

// Count the number of UTF-8 characters in a string
inline size_t utf8CharCount(const std::string &s) {
    size_t count = 0;
    for (size_t i = 0; i < s.size(); ) {
        unsigned char c = s[i];
        if (c < 0x80) i += 1;
        else if (c < 0xE0) i += 2;
        else if (c < 0xF0) i += 3;
        else i += 4;
        count++;
    }
    return count;
}

// Get the first N UTF-8 characters of a string
inline std::string utf8Substr(const std::string &s, size_t start, size_t count) {
    size_t charIdx = 0;
    size_t byteStart = 0;
    // Find byte offset for 'start' characters
    for (size_t i = 0; i < s.size() && charIdx < start; ) {
        unsigned char c = s[i];
        if (c < 0x80) i += 1;
        else if (c < 0xE0) i += 2;
        else if (c < 0xF0) i += 3;
        else i += 4;
        charIdx++;
        byteStart = i;
    }
    // Find byte length for 'count' characters
    size_t charsRead = 0;
    size_t byteEnd = byteStart;
    for (size_t i = byteStart; i < s.size() && charsRead < count; ) {
        unsigned char c = s[i];
        if (c < 0x80) i += 1;
        else if (c < 0xE0) i += 2;
        else if (c < 0xF0) i += 3;
        else i += 4;
        charsRead++;
        byteEnd = i;
    }
    return s.substr(byteStart, byteEnd - byteStart);
}

// Whether the cursor at romaji position i is on the border of a kana: what the first i letters show is the
// beginning of what the whole input shows. In "kaki" the positions 0, 2 and 4 are borders; 1 (k|aki) and 3 are not.
inline bool isKanaBoundary(const std::string &input, const std::string &whole, size_t i) {
    if (i == 0 || i >= input.size()) {
        return true;
    }
    const std::string shown = romajiToHiragana(input.substr(0, i));
    return whole.compare(0, shown.size(), shown) == 0;
}

// The bytes of the shown text that lie before each position of the romaji input; -1 where the position is not on
// the border of a kana. (In "nn" the positions 1 and 2 both lie behind the one shown ん.)
inline std::vector<int> shownBytesAt(const std::string &input) {
    const std::string whole = romajiToHiragana(input);
    std::vector<int> bytes(input.size() + 1, -1);
    for (size_t i = 0; i <= input.size(); i++) {
        if (isKanaBoundary(input, whole, i)) {
            bytes[i] = static_cast<int>(romajiToHiragana(input.substr(0, i)).size());
        }
    }
    return bytes;
}

// The cursor moved by one kana. direction is -1 (left) or +1 (right); the result is a position in the romaji input.
// Of the positions that lie at the same shown place, the one farthest to the right is taken.
inline size_t movedByOneKana(const std::string &input, size_t cursor, int direction) {
    const std::vector<int> bytes = shownBytesAt(input);
    const int here = bytes[cursor > input.size() ? input.size() : cursor];
    int target = -1;
    if (direction < 0) {
        for (size_t i = 0; i < cursor && i < bytes.size(); i++) {
            if (bytes[i] >= 0 && bytes[i] < here) {
                target = bytes[i];            // grows with i, so the last one is the nearest
            }
        }
        if (target < 0) {
            return 0;
        }
    } else {
        for (size_t i = cursor + 1; i < bytes.size(); i++) {
            if (bytes[i] > here) {
                target = bytes[i];
                break;
            }
        }
        if (target < 0) {
            return input.size();
        }
    }
    size_t result = 0;
    for (size_t i = 0; i < bytes.size(); i++) {
        if (bytes[i] == target) {
            result = i;
        }
    }
    return result;
}

// Where the cursor is shown (a byte offset in the hiragana of the whole input). A cursor that is not on the
// border of a kana (the k of k|a) is shown at the border before it.
inline int shownCursorBytes(const std::string &input, size_t cursor) {
    const std::vector<int> bytes = shownBytesAt(input);
    if (cursor > input.size()) {
        cursor = input.size();
    }
    for (size_t i = cursor + 1; i > 0; i--) {
        if (bytes[i - 1] >= 0) {
            return bytes[i - 1];
        }
    }
    return 0;
}

} // namespace llmime
