#include "model/transformer.h"

#include <Eigen/Dense>

#include <cmath>
#include <stdexcept>

namespace llmime::model {

namespace {

using RowMat = Eigen::Matrix<float, Eigen::Dynamic, Eigen::Dynamic, Eigen::RowMajor>;
using ConstMat = Eigen::Map<const RowMat>;
using ConstRow = Eigen::Map<const Eigen::RowVectorXf>;

constexpr float kLayerNormEps = 1e-5f;

// nn.Linear: y = x W^T + b, W of (out, in).
struct Linear {
    const float *w = nullptr;
    const float *b = nullptr;
    int out = 0;
    int in = 0;

    Linear() = default;
    Linear(const Weights &weights, const std::string &name) {
        const Tensor &wt = weights.get(name + ".weight");
        w = wt.data;
        out = wt.rows();
        in = wt.cols();
        b = weights.get(name + ".bias").data;
    }
    // A slice of the rows of a packed projection (the q, k and v of in_proj_weight).
    Linear(const float *weight, const float *bias, int outRows, int inCols)
        : w(weight), b(bias), out(outRows), in(inCols) {}

    RowMat apply(const RowMat &x) const {
        RowMat y = x * ConstMat(w, out, in).transpose();
        y.rowwise() += ConstRow(b, out);
        return y;
    }
};

struct LayerNorm {
    const float *g = nullptr;
    const float *b = nullptr;
    int d = 0;

    LayerNorm() = default;
    LayerNorm(const Weights &weights, const std::string &name) {
        g = weights.get(name + ".weight").data;
        b = weights.get(name + ".bias").data;
        d = weights.get(name + ".weight").rows();
    }
    RowMat apply(const RowMat &x) const {
        RowMat y(x.rows(), d);
        ConstRow gain(g, d), bias(b, d);
        for (Eigen::Index r = 0; r < x.rows(); r++) {
            auto row = x.row(r).array();
            float mean = row.mean();
            float var = (row - mean).square().mean();
            y.row(r) = ((row - mean) / std::sqrt(var + kLayerNormEps)).matrix().cwiseProduct(gain) + bias;
        }
        return y;
    }
};

// nn.MultiheadAttention with its packed input projection.
struct Attention {
    Linear q, k, v, out;
    int heads = 0;

    Attention() = default;
    Attention(const Weights &weights, const std::string &name, int nhead) : heads(nhead) {
        const Tensor &in = weights.get(name + ".in_proj_weight");
        const float *bias = weights.get(name + ".in_proj_bias").data;
        int d = in.cols();
        q = Linear(in.data, bias, d, d);
        k = Linear(in.data + static_cast<size_t>(d) * d, bias + d, d, d);
        v = Linear(in.data + 2 * static_cast<size_t>(d) * d, bias + 2 * d, d, d);
        out = Linear(weights, name + ".out_proj");
    }

    // Scaled dot-product attention of the queries over the keys and values, per head. With `causal`, query i (the
    // position firstQuery + i) sees the keys up to its own position only.
    RowMat attend(const RowMat &query, const RowMat &key, const RowMat &value, bool causal, int firstQuery) const {
        int d = static_cast<int>(query.cols());
        int hd = d / heads;
        float scale = 1.0f / std::sqrt(static_cast<float>(hd));
        RowMat result(query.rows(), d);
        for (int h = 0; h < heads; h++) {
            RowMat scores = (query.middleCols(h * hd, hd) * key.middleCols(h * hd, hd).transpose()) * scale;
            for (Eigen::Index i = 0; i < scores.rows(); i++) {
                Eigen::Index visible = causal ? firstQuery + i + 1 : scores.cols();
                float max = scores.row(i).head(visible).maxCoeff();
                float sum = 0;
                for (Eigen::Index j = 0; j < scores.cols(); j++) {
                    float e = j < visible ? std::exp(scores(i, j) - max) : 0.0f;
                    scores(i, j) = e;
                    sum += e;
                }
                scores.row(i) /= sum;
            }
            result.middleCols(h * hd, hd) = scores * value.middleCols(h * hd, hd);
        }
        return result;
    }
};

struct FeedForward {
    Linear linear1, linear2;

    FeedForward() = default;
    FeedForward(const Weights &weights, const std::string &name)
        : linear1(weights, name + ".linear1"), linear2(weights, name + ".linear2") {}
    RowMat apply(const RowMat &x) const { return linear2.apply(linear1.apply(x).cwiseMax(0.0f)); }
};

struct EncoderLayer {
    LayerNorm norm1, norm2;
    Attention selfAttn;
    FeedForward ff;
};

struct DecoderLayer {
    LayerNorm norm1, norm2, norm3;
    Attention selfAttn, crossAttn;
    FeedForward ff;
};

} // namespace

struct Transformer::Memory {
    std::vector<RowMat> crossK;  // per decoder layer: (source length, d_model)
    std::vector<RowMat> crossV;
};

struct Transformer::Impl {
    Weights weights;
    Config config;
    const float *srcEmbed = nullptr;
    const float *tgtEmbed = nullptr;
    const float *positions = nullptr;  // (max_len, d_model)
    float embedScale = 0;
    std::vector<EncoderLayer> encoder;
    std::vector<DecoderLayer> decoder;
    Linear outProj;

    explicit Impl(const std::string &path) : weights(path), config(weights.config()) {
        srcEmbed = weights.get("src_embed.weight").data;
        tgtEmbed = weights.get("tgt_embed.weight").data;
        positions = weights.get("pos_enc.pe").data;
        embedScale = std::sqrt(static_cast<float>(config.dModel));
        for (int i = 0; i < config.encLayers; i++) {
            std::string p = "encoder.layers." + std::to_string(i);
            encoder.push_back({LayerNorm(weights, p + ".norm1"), LayerNorm(weights, p + ".norm2"),
                               Attention(weights, p + ".self_attn", config.nhead), FeedForward(weights, p)});
        }
        for (int i = 0; i < config.decLayers; i++) {
            std::string p = "decoder.layers." + std::to_string(i);
            decoder.push_back({LayerNorm(weights, p + ".norm1"), LayerNorm(weights, p + ".norm2"),
                               LayerNorm(weights, p + ".norm3"), Attention(weights, p + ".self_attn", config.nhead),
                               Attention(weights, p + ".multihead_attn", config.nhead), FeedForward(weights, p)});
        }
        outProj = Linear(weights, "out_proj");
    }

    // Embedding times the square root of d_model, plus the position, for the tokens at firstPosition onward.
    RowMat embed(const float *table, const std::vector<int> &ids, int firstPosition) const {
        int d = config.dModel;
        RowMat x(static_cast<Eigen::Index>(ids.size()), d);
        for (size_t i = 0; i < ids.size(); i++) {
            int pos = firstPosition + static_cast<int>(i);
            if (ids[i] < 0 || ids[i] >= config.vocabSize || pos >= config.maxLen) {
                throw std::out_of_range("token or position out of range");
            }
            x.row(static_cast<Eigen::Index>(i)) = ConstRow(table + static_cast<size_t>(ids[i]) * d, d) * embedScale +
                                                  ConstRow(positions + static_cast<size_t>(pos) * d, d);
        }
        return x;
    }

    // The decoder layers over the target rows x (positions firstPosition onward). With a cache, the keys and
    // values of the earlier positions are taken from it and those of x are added to it.
    RowMat decode(RowMat x, const Memory &memory, std::vector<RowMat> *cacheK, std::vector<RowMat> *cacheV,
                  int firstPosition) const {
        for (size_t l = 0; l < decoder.size(); l++) {
            const DecoderLayer &layer = decoder[l];
            RowMat h = layer.norm1.apply(x);
            RowMat q = layer.selfAttn.q.apply(h);
            RowMat k = layer.selfAttn.k.apply(h);
            RowMat v = layer.selfAttn.v.apply(h);
            if (cacheK != nullptr) {
                RowMat &ck = (*cacheK)[l];
                RowMat &cv = (*cacheV)[l];
                Eigen::Index old = ck.rows();
                ck.conservativeResize(old + k.rows(), k.cols());
                cv.conservativeResize(old + v.rows(), v.cols());
                ck.bottomRows(k.rows()) = k;
                cv.bottomRows(v.rows()) = v;
                x += layer.selfAttn.out.apply(layer.selfAttn.attend(q, ck, cv, true, firstPosition));
            } else {
                x += layer.selfAttn.out.apply(layer.selfAttn.attend(q, k, v, true, 0));
            }
            h = layer.norm2.apply(x);
            x += layer.crossAttn.out.apply(
                layer.crossAttn.attend(layer.crossAttn.q.apply(h), memory.crossK[l], memory.crossV[l], false, 0));
            x += layer.ff.apply(layer.norm3.apply(x));
        }
        return x;
    }
};

Transformer::Transformer(const std::string &weightsPath) : impl_(std::make_unique<Impl>(weightsPath)) {}

Transformer::~Transformer() = default;

const Config &Transformer::config() const { return impl_->config; }

std::shared_ptr<const Transformer::Memory> Transformer::encode(const std::vector<int> &srcIds) const {
    const Impl &m = *impl_;
    RowMat x = m.embed(m.srcEmbed, srcIds, 0);
    for (const EncoderLayer &layer : m.encoder) {
        RowMat h = layer.norm1.apply(x);
        x += layer.selfAttn.out.apply(
            layer.selfAttn.attend(layer.selfAttn.q.apply(h), layer.selfAttn.k.apply(h), layer.selfAttn.v.apply(h),
                                  false, 0));
        x += layer.ff.apply(layer.norm2.apply(x));
    }
    auto memory = std::make_shared<Memory>();
    for (const DecoderLayer &layer : m.decoder) {
        memory->crossK.push_back(layer.crossAttn.k.apply(x));
        memory->crossV.push_back(layer.crossAttn.v.apply(x));
    }
    return memory;
}

std::vector<int> Transformer::greedy(const Memory &memory, int maxTextLen, int barId) const {
    const Impl &m = *impl_;
    std::vector<RowMat> cacheK(m.decoder.size()), cacheV(m.decoder.size());
    std::vector<int> output;
    int token = kBos;
    int textLen = 0;
    for (int step = 0; step < 2 * maxTextLen + 5 && step < m.config.maxLen; step++) {
        RowMat x = m.decode(m.embed(m.tgtEmbed, {token}, step), memory, &cacheK, &cacheV, step);
        RowMat logits = m.outProj.apply(x);
        Eigen::Index next = 0;
        logits.row(0).maxCoeff(&next);  // the first of equal maxima, as argmax in PyTorch
        if (next == kEos) {
            break;
        }
        output.push_back(static_cast<int>(next));
        token = static_cast<int>(next);
        if (token != barId) {
            textLen++;
        }
        if (textLen >= maxTextLen) {
            break;
        }
    }
    return output;
}

std::vector<double> Transformer::logProbabilities(const Memory &memory,
                                                  const std::vector<std::vector<int>> &texts) const {
    const Impl &m = *impl_;
    std::vector<double> result;
    result.reserve(texts.size());
    for (const auto &text : texts) {
        std::vector<int> prefix{kBos};
        prefix.insert(prefix.end(), text.begin(), text.end());
        if (static_cast<int>(prefix.size()) > m.config.maxLen) {
            result.push_back(-INFINITY);
            continue;
        }
        RowMat logits = m.outProj.apply(m.decode(m.embed(m.tgtEmbed, prefix, 0), memory, nullptr, nullptr, 0));
        double sum = 0;
        for (size_t i = 0; i < prefix.size(); i++) {
            auto row = logits.row(static_cast<Eigen::Index>(i)).cast<double>();
            double max = row.maxCoeff();
            double logSum = max + std::log((row.array() - max).exp().sum());
            int next = i + 1 < prefix.size() ? prefix[i + 1] : kEos;
            sum += row(next) - logSum;
        }
        result.push_back(sum);
    }
    return result;
}

} // namespace llmime::model
