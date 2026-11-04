#include "conversion/converter.h"

#include "conversion/hint-substitution.h"
#include "conversion/history-substitution.h"
#include "conversion/reading-aligner.h"
#include "conversion/text.h"

#include <algorithm>
#include <cmath>
#include <numeric>

namespace llmime::conversion {

namespace {

// The user can pick among at least this many candidates for a bunsetsu, whatever the engine asked for.
constexpr size_t kAtLeastCandidates = 50;
// The model's answer is checked against the reading only if it cannot be explained better than this.
constexpr double kUnexplainedCost = 3.0;
// A reading explains the answer well if it costs no more than this.
constexpr double kExplainedCost = 1.0;
// The most characters of the end of a reading that are put back.
constexpr size_t kMostPutBack = 3;
// The longest reading the model converts in one run. The source (the context, SEP, the reading, EOS) and the answer
// (the text with '|' between the bunsetsu) must both fit in max_len positions; a longer reading is converted in
// pieces of at most this many characters.
constexpr size_t kLongestPiece = 64;
// A piece is cut after a punctuation mark when there is one in the last characters it may take.
constexpr size_t kLookBackForPunctuation = 32;

// Where the piece of `reading` that starts at `start` ends: after the last punctuation mark within reach, else at
// the longest piece.
size_t pieceEnd(const std::u32string &reading, size_t start) {
    size_t end = std::min(reading.size(), start + kLongestPiece);
    if (end == reading.size()) {
        return end;
    }
    static const std::u32string marks = U"、。，．！？!?・」』）";
    for (size_t at = end; at > start + kLongestPiece - kLookBackForPunctuation; at--) {
        if (marks.find(reading[at - 1]) != std::u32string::npos) {
            return at;
        }
    }
    return end;
}

bool onlyJapanese(const std::u32string &text) {
    static const std::u32string extra = U"ー々、。！？「」『』（）…・";
    for (char32_t c : text) {
        bool japanese = (c >= 0x3040 && c <= 0x309F) || (c >= 0x30A0 && c <= 0x30FF) ||
                        (c >= 0x31F0 && c <= 0x31FF) || (c >= 0xFF66 && c <= 0xFF9D) || isKanji(c) ||
                        c == U'〇' || (c >= 0x20000 && c <= 0x323AF) || extra.find(c) != std::u32string::npos;
        if (!japanese) {
            return false;
        }
    }
    return true;
}

std::u32string joined(const std::vector<std::u32string> &parts) {
    std::u32string all;
    for (const auto &p : parts) {
        all += p;
    }
    return all;
}

std::u32string text(const nlohmann::json &object, const char *name) {
    auto it = object.find(name);
    return it != object.end() && it->is_string() ? toU32(it->get<std::string>()) : std::u32string();
}

size_t wanted(const nlohmann::json &request) {
    auto it = request.find("n");
    size_t asked = it != request.end() && it->is_number_integer() && it->get<long>() > 0
                       ? static_cast<size_t>(it->get<long>())
                       : 0;
    return std::max(asked, kAtLeastCandidates);
}

nlohmann::json strings(const std::vector<std::u32string> &values) {
    nlohmann::json array = nlohmann::json::array();
    for (const auto &v : values) {
        array.push_back(toU8(v));
    }
    return array;
}

} // namespace

std::u32string covered(const std::u32string &reading, const std::u32string &answer) {
    if (answer.empty() || reading.size() < 2) {
        return answer;
    }
    std::u32string plain;
    for (char32_t c : answer) {
        if (c != U'|') {
            plain.push_back(c);
        }
    }
    if (plain.empty() || !onlyJapanese(plain) || alignmentCost(reading, plain) < kUnexplainedCost) {
        return answer;
    }
    for (size_t k = 1; k <= kMostPutBack && k < reading.size(); k++) {
        if (alignmentCost(reading.substr(0, reading.size() - k), plain) <= kExplainedCost) {
            return answer + reading.substr(reading.size() - k);
        }
    }
    return answer;
}

Converter::Converter(const ConverterSettings &settings, std::unique_ptr<CandidateSource> source)
    : settings_(settings),
      tokenizer_(settings.tokenizerPath),
      model_(settings.weightsPath),
      history_(settings.historyPath.empty() ? UserHistory() : UserHistory(settings.historyPath)),
      hints_(settings.hintsPath.empty() ? DictionaryHints() : DictionaryHints(settings.hintsPath)),
      source_(std::move(source)),
      barId_(tokenizer_.idOf(U'|')) {}

std::vector<int> Converter::sourceIds(const std::u32string &input, const std::u32string &context,
                                      std::vector<Hint> *hints) const {
    // As many characters before the cursor as the model was trained to read (in the header of its weights), and no
    // more characters in all than the longest source it was trained with (its positions are that many and 4 more).
    const size_t contextChars = static_cast<size_t>(model_.config().contextChars);
    const size_t longest = static_cast<size_t>(std::max(model_.config().maxLen - 4, 1));
    std::vector<Hint> found = hints_.of(input);
    // The user's choices go to the model after the words of the dictionary, and not to substituteHints: the model is
    // to judge them by the text before the cursor, not to have them put in
    std::vector<Hint> all = found;
    if (settings_.historyHints) {
        for (const auto &m : history_.matchesIn(input, kShortestHistoryReading)) {
            bool seen = std::any_of(all.begin(), all.end(), [&](const Hint &h) { return h.word == m.chosen; });
            if (!seen) {
                all.push_back({input.substr(m.start, m.end - m.start), m.chosen, U'~'});
            }
        }
    }
    std::vector<int> ids;
    for (char32_t c : hintedSource(context, input, contextChars, all, longest)) {
        ids.push_back(c == U'\x1f' ? Tokenizer::kSep : tokenizer_.idOf(c));
    }
    ids.push_back(model::Transformer::kEos);
    if (hints != nullptr) {
        *hints = std::move(found);
    }
    return ids;
}

std::u32string Converter::run(const std::u32string &input, const std::u32string &context,
                              std::shared_ptr<const model::Transformer::Memory> *memory) const {
    std::vector<Hint> hints;
    auto encoded = model_.encode(sourceIds(input, context, &hints));
    if (memory != nullptr) {
        *memory = encoded;
    }
    // The words the model could not copy from its hints are put in by rule
    return substituteHints(input, translate(*encoded, input), hints);
}

std::u32string Converter::translate(const std::u32string &input, const std::u32string &context) const {
    if (input.size() <= kLongestPiece) {
        return run(input, context);
    }
    // A long reading in pieces, each after the text before it and the conversion of the pieces before it
    std::u32string answer;
    std::u32string before = context;
    for (size_t start = 0; start < input.size();) {
        size_t end = pieceEnd(input, start);
        std::u32string piece = input.substr(start, end - start);
        std::u32string converted = covered(piece, run(piece, before));
        if (!answer.empty() && !converted.empty()) {
            answer.push_back(U'|');
        }
        answer += converted;
        for (char32_t c : converted) {
            if (c != U'|') {
                before.push_back(c);
            }
        }
        start = end;
    }
    return answer;
}

std::u32string Converter::translate(const model::Transformer::Memory &memory, const std::u32string &input) const {
    int readingLen = static_cast<int>(input.size()) + 1;
    return tokenizer_.decode(model_.greedy(memory, readingLen, barId_));
}

std::vector<std::u32string> Converter::modelSegments(const std::u32string &input,
                                                     const std::u32string &translated) const {
    std::u32string answer = covered(input, translated);
    std::vector<std::u32string> segments;
    size_t start = 0;
    while (start <= answer.size()) {
        size_t bar = answer.find(U'|', start);
        size_t end = bar == std::u32string::npos ? answer.size() : bar;
        if (end > start) {
            segments.push_back(answer.substr(start, end - start));
        }
        if (bar == std::u32string::npos) {
            break;
        }
        start = bar + 1;
    }
    if (segments.empty()) {
        segments.push_back(input);
    }
    return segments;
}

std::vector<std::u32string> Converter::withAlternatives(const std::u32string &own, const std::u32string &original,
                                                        const std::u32string &reading,
                                                        const model::Transformer::Memory *memory, size_t wanted) {
    std::vector<std::u32string> first;
    auto addFirst = [&first](const std::u32string &t) {
        if (std::find(first.begin(), first.end(), t) == first.end()) {
            first.push_back(t);
        }
    };
    if (auto preferred = history_.preferred(reading)) {
        addFirst(*preferred);
    }
    addFirst(own);
    addFirst(original);
    std::vector<std::u32string> others;
    if (source_) {
        for (auto &candidate : source_->candidates(reading, wanted)) {
            if (std::find(first.begin(), first.end(), candidate) == first.end() &&
                std::find(others.begin(), others.end(), candidate) == others.end()) {
                others.push_back(std::move(candidate));
            }
        }
    }
    std::vector<std::u32string> all = first;
    if (others.empty() || memory == nullptr) {
        all.insert(all.end(), others.begin(), others.end());
        return all;
    }
    // The model scores the first candidates of the dictionary after the text before the bunsetsu; the others stay
    // after them in the order of the dictionary.
    size_t count = std::min(others.size(), settings_.mostScored);
    std::vector<std::vector<int>> texts;
    for (size_t i = 0; i < count; i++) {
        texts.push_back(tokenizer_.encode(others[i]));
    }
    std::vector<double> scores = model_.logProbabilities(*memory, texts);
    scores.resize(others.size(), -INFINITY);
    std::vector<size_t> order(others.size());
    std::iota(order.begin(), order.end(), 0);
    std::stable_sort(order.begin(), order.end(), [&](size_t a, size_t b) {
        return scores[a] - settings_.sourceWeight * std::log1p(static_cast<double>(a)) >
               scores[b] - settings_.sourceWeight * std::log1p(static_cast<double>(b));
    });
    for (size_t i : order) {
        all.push_back(others[i]);
    }
    return all;
}

std::vector<Converter::Segment> Converter::segmentConvert(const std::u32string &input, const std::u32string &context,
                                                          size_t wanted) {
    Substituted answer = substituteHistory(input, modelSegments(input, translate(input, context)), settings_.historySubstitution ? history_ : noHistory_);
    std::vector<Segment> segments;
    for (size_t i = 0; i < answer.texts.size(); i++) {
        segments.push_back({answer.readings[i], withAlternatives(answer.texts[i], answer.originals[i],
                                                                 answer.readings[i], nullptr, wanted)});
    }
    return segments;
}

std::vector<std::u32string> Converter::segmentCandidates(const std::u32string &input, const std::u32string &context,
                                                         size_t wanted, std::u32string *output) {
    // One run of the encoder for the conversion and for the scores of the dictionary candidates. A reading too long
    // for one run is converted in pieces, and its dictionary candidates are left in the order of the dictionary.
    std::shared_ptr<const model::Transformer::Memory> memory;
    std::u32string translated;
    if (input.size() <= kLongestPiece) {
        translated = run(input, context, &memory);
    } else {
        translated = translate(input, context);
    }
    Substituted answer = substituteHistory(input, modelSegments(input, translated), settings_.historySubstitution ? history_ : noHistory_);
    std::u32string own = joined(answer.texts);
    if (output != nullptr) {
        *output = own;
    }
    return withAlternatives(own, joined(answer.originals), input, memory.get(), wanted);
}

std::u32string Converter::convert(const std::u32string &input, const std::u32string &context) {
    return joined(substituteHistory(input, modelSegments(input, translate(input, context)), settings_.historySubstitution ? history_ : noHistory_).texts);
}

void Converter::record(const std::u32string &reading, const std::u32string &chosen,
                       const std::u32string &turnedDown) {
    if (!turnedDown.empty()) {
        auto preferred = history_.preferred(reading);
        if (preferred && *preferred == turnedDown) {
            history_.forget(reading, turnedDown);
        }
        // A choice put in for a stretch of the reading (要るの in 要るのかな) that the user's text does not keep
        for (const auto &m : history_.matchesIn(reading, kShortestHistoryReading)) {
            if (m.end - m.start < reading.size() && turnedDown.find(m.chosen) != std::u32string::npos &&
                chosen.find(m.chosen) == std::u32string::npos) {
                history_.forget(reading.substr(m.start, m.end - m.start), m.chosen);
            }
        }
    }
    history_.learn(reading, chosen);
}

nlohmann::json Converter::post(const std::string &path, const nlohmann::json &request) {
    if (path == "/api/segment-convert") {
        nlohmann::json segments = nlohmann::json::array();
        for (const auto &s : segmentConvert(text(request, "input"), text(request, "context"), wanted(request))) {
            segments.push_back({{"reading", toU8(s.reading)}, {"candidates", strings(s.candidates)}});
        }
        return {{"segments", segments}};
    }
    if (path == "/api/segment-candidates") {
        std::u32string output;
        auto candidates =
            segmentCandidates(text(request, "input"), text(request, "context"), wanted(request), &output);
        return {{"candidates", strings(candidates)}, {"output", toU8(output)}};
    }
    if (path == "/api/convert") {
        std::u32string output = convert(text(request, "input"), text(request, "context"));
        return {{"candidates", strings({output})}, {"output", toU8(output)}};
    }
    if (path == "/api/record") {
        auto it = request.find("corrections");
        if (it != request.end() && it->is_array()) {
            for (const auto &correction : *it) {
                if (correction.is_object()) {
                    record(text(correction, "reading"), text(correction, "chosen"), text(correction, "turnedDown"));
                }
            }
        }
        return nlohmann::json::object();
    }
    if (path == "/api/continue") {
        return {{"candidates", nlohmann::json::array()}};
    }
    return nlohmann::json::array();  // /api/predict: no predictions
}

} // namespace llmime::conversion
