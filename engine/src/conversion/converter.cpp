#include "conversion/converter.h"

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
      source_(std::move(source)),
      barId_(tokenizer_.idOf(U'|')) {}

std::vector<int> Converter::sourceIds(const std::u32string &input, const std::u32string &context) const {
    std::u32string tail = context.size() > settings_.contextChars
                              ? context.substr(context.size() - settings_.contextChars)
                              : context;
    std::vector<int> ids = tokenizer_.encode(tail);
    ids.push_back(Tokenizer::kSep);
    for (int id : tokenizer_.encode(input)) {
        ids.push_back(id);
    }
    ids.push_back(model::Transformer::kEos);
    return ids;
}

std::u32string Converter::translate(const std::u32string &input, const std::u32string &context) const {
    return translate(*model_.encode(sourceIds(input, context)), input);
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
    Substituted answer = substituteHistory(input, modelSegments(input, translate(input, context)), history_);
    std::vector<Segment> segments;
    for (size_t i = 0; i < answer.texts.size(); i++) {
        segments.push_back({answer.readings[i], withAlternatives(answer.texts[i], answer.originals[i],
                                                                 answer.readings[i], nullptr, wanted)});
    }
    return segments;
}

std::vector<std::u32string> Converter::segmentCandidates(const std::u32string &input, const std::u32string &context,
                                                         size_t wanted, std::u32string *output) {
    // One run of the encoder for the conversion and for the scores of the dictionary candidates.
    auto memory = model_.encode(sourceIds(input, context));
    Substituted answer = substituteHistory(input, modelSegments(input, translate(*memory, input)), history_);
    std::u32string own = joined(answer.texts);
    if (output != nullptr) {
        *output = own;
    }
    return withAlternatives(own, joined(answer.originals), input, memory.get(), wanted);
}

std::u32string Converter::convert(const std::u32string &input, const std::u32string &context) {
    return joined(substituteHistory(input, modelSegments(input, translate(input, context)), history_).texts);
}

void Converter::record(const std::u32string &reading, const std::u32string &chosen) {
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
                    record(text(correction, "reading"), text(correction, "chosen"));
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
