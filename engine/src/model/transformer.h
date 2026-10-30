#pragma once
// The conversion model: a character-level encoder-decoder Transformer (model/transformer.py), computed on the CPU
// with Eigen. The layers are the pre-norm layers of PyTorch (norm_first=True) without a final norm, with ReLU, and
// without dropout. The decoder computes one output position per step and keeps the keys and values of the earlier
// positions (as model/cached_decoder.py).
#include "model/weights.h"

#include <memory>
#include <string>
#include <vector>

namespace llmime::model {

class Transformer {
public:
    // What the encoder made of one source: the keys and values that the cross attention of every decoder layer
    // takes from it. Made once per conversion.
    struct Memory;

    explicit Transformer(const std::string &weightsPath);
    ~Transformer();

    const Config &config() const;

    // The source ids end with EOS, as the training rows do.
    std::shared_ptr<const Memory> encode(const std::vector<int> &srcIds) const;

    // Greedy decoding. Stops at EOS, or when `maxTextLen` tokens other than `barId` are written (the '|' between
    // the bunsetsu are not counted), or after 2 * maxTextLen + 5 steps.
    std::vector<int> greedy(const Memory &memory, int maxTextLen, int barId) const;

    // The log probability that the model writes each text, and EOS after it, from BOS.
    std::vector<double> logProbabilities(const Memory &memory, const std::vector<std::vector<int>> &texts) const;

    static constexpr int kPad = 0;
    static constexpr int kBos = 1;
    static constexpr int kEos = 2;

private:
    struct Impl;
    std::unique_ptr<Impl> impl_;
};

} // namespace llmime::model
