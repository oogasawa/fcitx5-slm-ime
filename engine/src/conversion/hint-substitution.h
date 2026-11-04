#pragma once
// Puts the words of the dictionary into the model's output where the model got them wrong. The model reads the
// words as hints (DictionaryHints) and copies most of them, but it cannot write a character it was never trained to
// write (諛 of 阿諛追従): it wrote アユ追唱 with the hint あゆついしょう=阿諛追従 in front of it.
//
// A hint replaces bunsetsu of the output when its reading starts at the start of a bunsetsu (or after 右, 左 or 両
// there, which are then written with it) and ends in the same or a later bunsetsu, and when
//  - the text it replaces is as long as the word, and what follows it is what follows the reading: nothing, or
//    text that begins with the same kana (完成昏睡|に対しては for 肝性昏睡, 多発間|農用 for 多発肝膿瘍), not a
//    longer word that holds the reading (人民委員部),
//  - the model did not write a word of the dictionary for that reading itself (リンパ節腫大 stays, though the
//    dictionary has ＬＮ腫大 for the same reading),
//  - the text does not end in kana: the model wrote that kana as the ending of a word, and the hint stands across a
//    boundary (影響か|ら with えいきょうか=影響下),
//  - the text does not begin with a kana that the word does not begin with (the honorific of ご了承 is meant so).
// On the medical case and radiology reports of the Real-MedNLP Test Collection, with the Manbyo dictionary, the
// medical words that the training text never holds were written right 68.1 % of the time with the rule and 56.7 %
// with the hints alone (47.7 % with the model of version 2); on 5000 rows of ordinary text it changed two outputs.
// The same rule as HintSubstitution of the Java conversion server of version 3.
#include "conversion/dictionary-hints.h"

#include <string>
#include <vector>

namespace llmime::conversion {

// The output ('|' between the bunsetsu) with the words of the hints put in. `hints` are the hints the model read.
std::u32string substituteHints(const std::u32string &reading, const std::u32string &output,
                               const std::vector<Hint> &hints);

} // namespace llmime::conversion
