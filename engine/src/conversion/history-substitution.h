#pragma once
// Puts the texts that the user chose before into the model's answer, where the answer contains the reading of an
// earlier choice.
//
// UserHistory is looked up by the whole reading of a bunsetsu. The model cuts the typed text into bunsetsu in its
// own way, so the reading the user once corrected (でぷろい) is often a part of a longer bunsetsu (でぷろいされたかな),
// or is split by a bunsetsu boundary (で|ぷろいされたかな). This finds the stretches of the typed text that are the
// reading of a choice, finds the part of the model's output that comes from each stretch, and replaces that part
// with the chosen text. Bunsetsu that the stretch runs across are joined.
//
// The output is cut into pieces (a run of kanji, and every other character alone), and the reading of each piece is
// found with alignReadings. A stretch is applied only when its two ends fall on the ends of pieces, which is not so
// when an end lies inside a run of kanji; then the model's output is left as it is, because the part that comes
// from the stretch cannot be told.
#include "conversion/user-history.h"

#include <string>
#include <vector>

namespace llmime::conversion {

struct Substituted {
    std::vector<std::u32string> texts;      // the bunsetsu after the substitution
    std::vector<std::u32string> originals;  // the same bunsetsu as the model wrote them (joined where joined)
    std::vector<std::u32string> readings;   // the reading of each bunsetsu
};

// Readings shorter than this many characters are not looked for inside a longer reading.
constexpr size_t kShortestHistoryReading = 3;

Substituted substituteHistory(const std::u32string &reading, const std::vector<std::u32string> &segments,
                              const UserHistory &history);

// The text cut into the units whose reading is told apart: a run of kanji, and every other character alone.
std::vector<std::u32string> historyPieces(const std::u32string &text);

} // namespace llmime::conversion
