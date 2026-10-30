#include "conversion/reading-aligner.h"

#include "conversion/text.h"

#include <cmath>
#include <optional>

namespace llmime::conversion {

namespace {

constexpr double kInfinity = std::numeric_limits<double>::max() / 4;
constexpr double kKanaMismatch = 4.0;
constexpr double kOtherMismatch = 1.0;
constexpr double kLongestPlausiblePerKanji = 5;
constexpr double kPreferredPerKanji = 2.2;
constexpr double kSpreadCost = 0.3;
constexpr double kGiveUpPerToken = 1.5;

enum class Kind { Kana, Kanji, Other };

struct Token {
    Kind kind;
    std::u32string text;
    size_t segment;
};

std::vector<Token> tokenize(const std::vector<std::u32string> &segments) {
    std::vector<Token> tokens;
    for (size_t s = 0; s < segments.size(); s++) {
        const std::u32string &text = segments[s];
        size_t i = 0;
        while (i < text.size()) {
            if (isKanji(text[i])) {
                size_t j = i;
                while (j < text.size() && isKanji(text[j])) {
                    j++;
                }
                tokens.push_back({Kind::Kanji, text.substr(i, j - i), s});
                i = j;
            } else {
                tokens.push_back({isKana(text[i]) ? Kind::Kana : Kind::Other, text.substr(i, 1), s});
                i++;
            }
        }
    }
    return tokens;
}

struct Alignment {
    std::vector<size_t> ends;  // where every token ends in the reading
    double cost;
};

std::optional<Alignment> cheapest(const std::u32string &reading, const std::vector<Token> &tokens) {
    size_t m = reading.size();
    size_t n = tokens.size();
    std::vector<std::vector<double>> cost(n + 1, std::vector<double>(m + 1, kInfinity));
    std::vector<std::vector<size_t>> from(n + 1, std::vector<size_t>(m + 1, 0));
    cost[0][0] = 0;
    for (size_t t = 0; t < n; t++) {
        const Token &token = tokens[t];
        for (size_t i = 0; i <= m; i++) {
            double here = cost[t][i];
            if (here >= kInfinity) {
                continue;
            }
            if (token.kind == Kind::Kanji) {
                size_t len = token.text.size();
                size_t longest = static_cast<size_t>(
                    std::min(static_cast<double>(m - i), kLongestPlausiblePerKanji * static_cast<double>(len)));
                for (size_t k = len; k <= longest; k++) {
                    double c = here + kSpreadCost * std::fabs(static_cast<double>(k) - kPreferredPerKanji * len);
                    if (c < cost[t + 1][i + k]) {
                        cost[t + 1][i + k] = c;
                        from[t + 1][i + k] = i;
                    }
                }
            } else if (i < m) {
                double step;
                if (token.kind == Kind::Kana) {
                    step = toHiragana(token.text[0]) == toHiragana(reading[i]) ? 0 : kKanaMismatch;
                } else {
                    step = foldWidth(token.text[0]) == foldWidth(reading[i]) ? 0 : kOtherMismatch;
                }
                if (here + step < cost[t + 1][i + 1]) {
                    cost[t + 1][i + 1] = here + step;
                    from[t + 1][i + 1] = i;
                }
            }
        }
    }
    if (cost[n][m] >= kInfinity) {
        return std::nullopt;
    }
    Alignment alignment{std::vector<size_t>(n), cost[n][m]};
    size_t i = m;
    for (size_t t = n; t >= 1; t--) {
        alignment.ends[t - 1] = i;
        i = from[t][i];
    }
    return alignment;
}

std::vector<std::u32string> proportional(const std::u32string &reading, const std::vector<std::u32string> &segments) {
    size_t total = 0;
    for (const auto &s : segments) {
        total += std::max<size_t>(1, s.size());
    }
    std::vector<std::u32string> result;
    size_t taken = 0;
    size_t cumulative = 0;
    for (size_t s = 0; s < segments.size(); s++) {
        cumulative += std::max<size_t>(1, segments[s].size());
        long end = s == segments.size() - 1
                       ? static_cast<long>(reading.size())
                       : std::lround(static_cast<double>(reading.size()) * cumulative / total);
        end = std::max<long>(static_cast<long>(taken), std::min<long>(static_cast<long>(reading.size()), end));
        result.push_back(reading.substr(taken, static_cast<size_t>(end) - taken));
        taken = static_cast<size_t>(end);
    }
    return result;
}

} // namespace

std::vector<std::u32string> alignReadings(const std::u32string &reading, const std::vector<std::u32string> &segments) {
    if (segments.empty()) {
        return {};
    }
    if (segments.size() == 1) {
        return {reading};
    }
    std::vector<Token> tokens = tokenize(segments);
    auto alignment = cheapest(reading, tokens);
    if (!alignment || alignment->cost > kGiveUpPerToken * static_cast<double>(tokens.size())) {
        return proportional(reading, segments);
    }
    std::vector<std::u32string> result;
    size_t segmentStart = 0;
    size_t tokenIndex = 0;
    for (size_t s = 0; s < segments.size(); s++) {
        size_t last = tokenIndex;
        while (last < tokens.size() && tokens[last].segment == s) {
            last++;
        }
        size_t end = last > tokenIndex ? alignment->ends[last - 1] : segmentStart;
        result.push_back(reading.substr(segmentStart, end - segmentStart));
        segmentStart = end;
        tokenIndex = last;
    }
    return result;
}

double alignmentCost(const std::u32string &reading, const std::u32string &text) {
    auto alignment = cheapest(reading, tokenize({text}));
    return alignment ? alignment->cost : std::numeric_limits<double>::infinity();
}

} // namespace llmime::conversion
