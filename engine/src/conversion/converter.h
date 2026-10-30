#pragma once
// The conversion of typed hiragana into kanji-kana text, inside the engine: the model, the reading of every
// bunsetsu, the user's earlier choices and the candidates of a dictionary.
//
// It answers the requests that the engine sent to the conversion server of version 1, with the same JSON, so that
// the engine changes little (post()). The model is read once; the methods can be called from several threads.
#include "conversion/candidate-source.h"
#include "conversion/tokenizer.h"
#include "conversion/user-history.h"
#include "model/transformer.h"

#include <nlohmann/json.hpp>

#include <memory>
#include <string>
#include <vector>

namespace llmime::conversion {

struct ConverterSettings {
    std::string weightsPath;     // export_weights.py
    std::string tokenizerPath;   // tokenizer.json
    std::string historyPath;     // the choices of the user; empty: kept in memory only
    size_t contextChars = 48;    // how many characters before the cursor the model was trained to read
    size_t mostScored = 20;      // how many dictionary candidates of a bunsetsu the model puts in order
    double sourceWeight = 0;     // how much the order of the dictionary counts against the scores of the model
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
    // The user took `chosen` for the bunsetsu typed as `reading`.
    void record(const std::u32string &reading, const std::u32string &chosen);

    // The requests of the engine as the conversion server of version 1 took them: /api/segment-convert,
    // /api/segment-candidates, /api/convert, /api/record, /api/continue, /api/predict.
    nlohmann::json post(const std::string &path, const nlohmann::json &request);

    // The model's conversion, with '|' between the bunsetsu.
    std::u32string translate(const std::u32string &input, const std::u32string &context) const;

private:
    std::vector<int> sourceIds(const std::u32string &input, const std::u32string &context) const;
    std::vector<std::u32string> modelSegments(const std::u32string &input, const std::u32string &context) const;
    std::vector<std::u32string> withAlternatives(const std::u32string &own, const std::u32string &original,
                                                 const std::u32string &reading, const std::u32string *context,
                                                 size_t wanted);

    ConverterSettings settings_;
    Tokenizer tokenizer_;
    model::Transformer model_;
    UserHistory history_;
    std::unique_ptr<CandidateSource> source_;
    int barId_;
};

// The model leaves out the last characters of a reading that ends inside a word: asked to convert せいどがよ it
// answers 精度が. This puts the characters left out back, as they were typed (精度がよ), only when the answer cannot
// be explained as the conversion of the whole reading but can be explained, well, as the conversion of the reading
// without its last one to three characters, and only for an answer of kanji, kana and punctuation.
std::u32string covered(const std::u32string &reading, const std::u32string &answer);

} // namespace llmime::conversion
