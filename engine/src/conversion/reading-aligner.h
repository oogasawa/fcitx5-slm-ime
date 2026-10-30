#pragma once
// Finds the reading (the hiragana the user typed) of each bunsetsu in the model's output.
//
// The model turns きかいがくしゅうのあるごりずむ into 機械学習|の|アルゴリズム but does not say which part of the
// typed text each bunsetsu comes from. The kana in the output (の, アルゴリズム) occur in the typed text, so they
// fix the positions; a run of kanji takes as many typed characters as are left between them. The cheapest such
// alignment is found by dynamic programming. When no alignment is cheap enough (the typed text and the output
// disagree), the typed text is cut in proportion to the length of the output pieces.
#include <limits>
#include <string>
#include <vector>

namespace llmime::conversion {

// Cuts `reading` into one piece per element of `segments`, in order, so that the pieces joined give `reading` back.
std::vector<std::u32string> alignReadings(const std::u32string &reading, const std::vector<std::u32string> &segments);

// What it costs to explain `text` as the conversion of the whole of `reading`: 0 when every kana of the text is
// where the reading has it and every run of kanji takes about as many characters as it usually does; infinity when
// the text cannot account for the reading.
double alignmentCost(const std::u32string &reading, const std::u32string &text);

} // namespace llmime::conversion
