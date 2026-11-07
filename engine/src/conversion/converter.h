#pragma once
// The conversion of typed hiragana into kanji-kana text, inside the engine: the model, the reading of every
// bunsetsu, the user's earlier choices and the candidates of a dictionary.
//
// It answers the requests that the engine sent to the conversion server of version 1, with the same JSON, so that
// the engine changes little (post()). The model is read once; the methods can be called from several threads.
#include "conversion/candidate-source.h"
#include "conversion/dictionary-hints.h"
#include "conversion/tokenizer.h"
#include "conversion/user-history.h"
#include "model/transformer.h"

#include <nlohmann/json.hpp>

#include <atomic>
#include <memory>
#include <string>
#include <vector>

namespace llmime::conversion {

struct ConverterSettings {
    std::string weightsPath;     // export_weights.py
    std::string tokenizerPath;   // tokenizer.json
    std::string historyPath;     // the choices of the user; empty: kept in memory only
    std::string hintsPath;       // the words given to the model as hints (prep/hard_words.py); empty: no hints
    // The user's earlier choices whose readings the typed reading holds are given to the model as hints too, so that
    // it can take them where the text before the cursor fits (いい感じ) and leave them where it does not (漢字を書く)
    bool historyHints = true;
    // The user's earlier choices are put into the model's answer whatever the text before the cursor
    // (substituteHistory), as in version 2
    bool historySubstitution = true;
    size_t mostScored = 20;      // how many dictionary candidates of a bunsetsu the model puts in order
    double sourceWeight = 0;     // how much the order of the dictionary counts against the scores of the model
    bool historyUsed = true;     // false: the choices of the user are neither used nor added to (setHistoryUsed)
};

class Converter {
public:
    // Throws std::runtime_error when the model or the tokenizer cannot be read. A null source gives no
    // dictionary candidates.
    Converter(const ConverterSettings &settings, std::unique_ptr<CandidateSource> source);

    struct Segment {
        std::u32string reading;
        std::vector<std::u32string> candidates;
    };

    // The typed text cut into bunsetsu, each with its reading and its candidates (the dictionary candidates in the
    // order of the dictionary: each bunsetsu would cost a run of the model).
    std::vector<Segment> segmentConvert(const std::u32string &input, const std::u32string &context, size_t wanted);
    // The candidates of one bunsetsu whose reading is known, the dictionary candidates put in order by the model
    // after the text before the bunsetsu. `output` gets the text of the model (with the user's choices put in).
    std::vector<std::u32string> segmentCandidates(const std::u32string &input, const std::u32string &context,
                                                  size_t wanted, std::u32string *output = nullptr);
    // One candidate for the whole text.
    std::u32string convert(const std::u32string &input, const std::u32string &context);
    // The user took `chosen` for the bunsetsu typed as `reading`, turning down `turnedDown`, the text offered for
    // it (empty when not known). An earlier choice that was put into the offered text and that the user took out
    // is forgotten: one correction undoes a choice made by mistake, however often it was made.
    void record(const std::u32string &reading, const std::u32string &chosen, const std::u32string &turnedDown = U"");

    // Whether the choices of the user are used and added to. Off: they are not put into the answer or first in the
    // candidates of a reading, and record() keeps nothing; the history file is left alone, and what is in it is used
    // again when this is turned back on. May be called from any thread, at any time.
    void setHistoryUsed(bool used) { historyUsed_ = used; }
    bool historyUsed() const { return historyUsed_; }

    // The requests of the engine as the conversion server of version 1 took them: /api/segment-convert,
    // /api/segment-candidates, /api/convert, /api/record, /api/continue, /api/predict.
    nlohmann::json post(const std::string &path, const nlohmann::json &request);

    // The model's conversion, with '|' between the bunsetsu.
    std::u32string translate(const std::u32string &input, const std::u32string &context) const;

private:
    // The source of the model, with the hints of the dictionary in front when there are any (`hints` gets them).
    std::vector<int> sourceIds(const std::u32string &input, const std::u32string &context,
                               std::vector<Hint> *hints = nullptr) const;
    // The model's conversion of a reading short enough for one run, with the words of its hints put in
    // (substituteHints); `memory` gets the encoded source, for the scores of the dictionary candidates.
    std::u32string run(const std::u32string &input, const std::u32string &context,
                       std::shared_ptr<const model::Transformer::Memory> *memory = nullptr) const;
    std::u32string translate(const model::Transformer::Memory &memory, const std::u32string &input) const;
    std::vector<std::u32string> modelSegments(const std::u32string &input, const std::u32string &translated) const;
    // `memory`: the encoded reading and context, to score the dictionary candidates with; null: not scored.
    std::vector<std::u32string> withAlternatives(const std::u32string &own, const std::u32string &original,
                                                 const std::u32string &reading,
                                                 const model::Transformer::Memory *memory, size_t wanted);

    // The history the answer is put through: the user's, or an empty one when the history is off
    // (and the user's choices are put into it only when settings_.historySubstitution is on, as in version 2)
    const UserHistory &historyInUse() const {
        return historyUsed_ && settings_.historySubstitution ? history_ : noHistory_;
    }

    ConverterSettings settings_;
    Tokenizer tokenizer_;
    model::Transformer model_;
    UserHistory history_;
    UserHistory noHistory_;  // put into the answers instead of history_ when settings_.historySubstitution is off
    DictionaryHints hints_;
    std::atomic<bool> historyUsed_;
    std::unique_ptr<CandidateSource> source_;
    int barId_;
};

// The model leaves out the last characters of a reading that ends inside a word: asked to convert せいどがよ it
// answers 精度が. This puts the characters left out back, as they were typed (精度がよ), only when the answer cannot
// be explained as the conversion of the whole reading but can be explained, well, as the conversion of the reading
// without its last one to three characters, and only for an answer of kanji, kana and punctuation.
std::u32string covered(const std::u32string &reading, const std::u32string &answer);

} // namespace llmime::conversion
