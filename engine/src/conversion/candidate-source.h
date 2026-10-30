#pragma once
// Where the alternatives to the model's conversion of one bunsetsu come from.
//
// The model gives one answer. When it is wrong, the user picks another candidate for that bunsetsu. The candidates
// are made from the reading of the bunsetsu by a dictionary-based converter, so that rare words, single kanji and
// proper nouns, which the model is weak at, can still be reached. The source can be replaced (Anthy on Linux, a
// dictionary of its own elsewhere).
#include <memory>
#include <mutex>
#include <string>
#include <vector>

namespace llmime::conversion {

class CandidateSource {
public:
    virtual ~CandidateSource() = default;
    // The candidates for the reading of one bunsetsu, best first, without duplicates, at most `limit`.
    virtual std::vector<std::u32string> candidates(const std::u32string &reading, size_t limit) = 0;
};

// Anthy, called through libanthy. Anthy cuts the text it is given into bunsetsu by itself. The first candidate is
// Anthy's own conversion of the whole reading, its bunsetsu joined: it reaches a word like 天気 even when the model
// gave a reading of several words as one piece. The others are the candidates of the reading taken as one bunsetsu:
// the first segment is stretched until it covers the whole reading, and its candidates are read. One instance holds
// one Anthy context; calls are serialised.
class AnthyCandidates : public CandidateSource {
public:
    // Throws std::runtime_error when Anthy cannot be started.
    AnthyCandidates();
    ~AnthyCandidates() override;
    std::vector<std::u32string> candidates(const std::u32string &reading, size_t limit) override;

private:
    void *context_ = nullptr;  // anthy_context_t
    std::mutex mutex_;
};

} // namespace llmime::conversion
