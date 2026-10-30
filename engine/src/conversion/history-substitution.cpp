#include "conversion/history-substitution.h"

#include "conversion/reading-aligner.h"
#include "conversion/text.h"

namespace llmime::conversion {

namespace {

const UserHistory::Match *matchStartingAt(const std::vector<UserHistory::Match> &matches, size_t start) {
    for (const auto &m : matches) {
        if (m.start == start) {
            return &m;
        }
    }
    return nullptr;
}

// The index of the piece, from `from` on, whose reading ends at `end`; -1 if no piece ends there.
long pieceEndingAt(const std::vector<size_t> &begin, size_t end, size_t from) {
    for (size_t i = from; i + 1 < begin.size(); i++) {
        if (begin[i + 1] == end) {
            return static_cast<long>(i);
        }
        if (begin[i + 1] > end) {
            return -1;
        }
    }
    return -1;
}

} // namespace

std::vector<std::u32string> historyPieces(const std::u32string &text) {
    std::vector<std::u32string> pieces;
    size_t i = 0;
    while (i < text.size()) {
        size_t j = i + 1;
        if (isKanji(text[i])) {
            while (j < text.size() && isKanji(text[j])) {
                j++;
            }
        }
        pieces.push_back(text.substr(i, j - i));
        i = j;
    }
    return pieces;
}

Substituted substituteHistory(const std::u32string &reading, const std::vector<std::u32string> &segments,
                              const UserHistory &history) {
    auto matches = history.matchesIn(reading, kShortestHistoryReading);
    if (matches.empty()) {
        return {segments, segments, alignReadings(reading, segments)};
    }
    std::vector<std::u32string> pieces;
    std::vector<size_t> segmentOf;
    for (size_t s = 0; s < segments.size(); s++) {
        for (auto &piece : historyPieces(segments[s])) {
            pieces.push_back(std::move(piece));
            segmentOf.push_back(s);
        }
    }
    auto pieceReadings = alignReadings(reading, pieces);
    std::vector<size_t> begin(pieces.size() + 1, 0);
    for (size_t i = 0; i < pieces.size(); i++) {
        begin[i + 1] = begin[i] + pieceReadings[i].size();
    }

    std::vector<bool> joinedWithNext(segments.size(), false);
    std::vector<std::u32string> unitTexts, unitReadings;
    std::vector<size_t> unitSegment;
    size_t piece = 0;
    int applied = 0;
    while (piece < pieces.size()) {
        const UserHistory::Match *match = matchStartingAt(matches, begin[piece]);
        long last = match == nullptr ? -1 : pieceEndingAt(begin, match->end, piece);
        if (last >= 0) {
            for (size_t s = segmentOf[piece]; s < segmentOf[static_cast<size_t>(last)]; s++) {
                joinedWithNext[s] = true;
            }
            unitTexts.push_back(match->chosen);
            unitReadings.push_back(reading.substr(match->start, match->end - match->start));
            unitSegment.push_back(segmentOf[piece]);
            piece = static_cast<size_t>(last) + 1;
            applied++;
        } else {
            unitTexts.push_back(pieces[piece]);
            unitReadings.push_back(pieceReadings[piece]);
            unitSegment.push_back(segmentOf[piece]);
            piece++;
        }
    }
    if (applied == 0) {
        return {segments, segments, alignReadings(reading, segments)};
    }

    std::vector<size_t> groupOf(segments.size(), 0);
    size_t groups = 0;
    for (size_t s = 0; s < segments.size(); s++) {
        groupOf[s] = s > 0 && joinedWithNext[s - 1] ? groupOf[s - 1] : groups++;
    }
    Substituted result{std::vector<std::u32string>(groups), std::vector<std::u32string>(groups),
                       std::vector<std::u32string>(groups)};
    for (size_t s = 0; s < segments.size(); s++) {
        result.originals[groupOf[s]] += segments[s];
    }
    for (size_t u = 0; u < unitTexts.size(); u++) {
        size_t g = groupOf[unitSegment[u]];
        result.texts[g] += unitTexts[u];
        result.readings[g] += unitReadings[u];
    }
    return result;
}

} // namespace llmime::conversion
