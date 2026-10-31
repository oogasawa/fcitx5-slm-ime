// Tests that a damaged weights file is refused with an exception, never read past its end: a tiny model is
// written, then damaged in one place at a time. No fcitx5 and no real model are needed.
#include "model/transformer.h"
#include "model/weights.h"

#include <nlohmann/json.hpp>

#include <cstdio>
#include <fstream>
#include <functional>
#include <string>
#include <vector>

using namespace llmime::model;
using json = nlohmann::json;

static const char *kPath = "weights-test.bin";

// The header and data of a model with vocab 6, d_model 4, 2 heads, one layer each, d_ff 4, max_len 8.
static json tinyHeader(size_t *dataBytes) {
    json h;
    h["config"] = {{"vocab_size", 6}, {"d_model", 4}, {"nhead", 2}, {"enc_layers", 1},
                   {"dec_layers", 1}, {"d_ff", 4}, {"max_len", 8}};
    std::vector<std::pair<std::string, std::vector<int>>> tensors = {
        {"src_embed.weight", {6, 4}}, {"tgt_embed.weight", {6, 4}}, {"pos_enc.pe", {1, 8, 4}},
        {"out_proj.weight", {6, 4}}, {"out_proj.bias", {6}}};
    auto attention = [&](const std::string &p) {
        tensors.push_back({p + ".in_proj_weight", {12, 4}});
        tensors.push_back({p + ".in_proj_bias", {12}});
        tensors.push_back({p + ".out_proj.weight", {4, 4}});
        tensors.push_back({p + ".out_proj.bias", {4}});
    };
    auto layer = [&](const std::string &p, int norms) {
        tensors.push_back({p + ".linear1.weight", {4, 4}});
        tensors.push_back({p + ".linear1.bias", {4}});
        tensors.push_back({p + ".linear2.weight", {4, 4}});
        tensors.push_back({p + ".linear2.bias", {4}});
        for (int n = 1; n <= norms; n++) {
            tensors.push_back({p + ".norm" + std::to_string(n) + ".weight", {4}});
            tensors.push_back({p + ".norm" + std::to_string(n) + ".bias", {4}});
        }
    };
    attention("encoder.layers.0.self_attn");
    layer("encoder.layers.0", 2);
    attention("decoder.layers.0.self_attn");
    attention("decoder.layers.0.multihead_attn");
    layer("decoder.layers.0", 3);
    size_t offset = 0;
    h["tensors"] = json::array();
    for (auto &[name, shape] : tensors) {
        size_t count = 1;
        for (int d : shape) count *= static_cast<size_t>(d);
        h["tensors"].push_back({{"name", name}, {"shape", shape}, {"offset", offset}});
        offset += count * sizeof(float);
    }
    *dataBytes = offset;
    return h;
}

// The file as export_weights.py writes it: the header padded with spaces so that the data starts at a multiple of
// 64 bytes (`misalign` moves the start of the data by that many bytes).
static void write(const json &header, size_t dataBytes, const char *magic = "SLMIMEW1", size_t misalign = 0) {
    std::string text = header.dump();
    text.append((64 - (12 + text.size()) % 64) % 64 + misalign, ' ');
    uint32_t length = static_cast<uint32_t>(text.size());
    std::ofstream out(kPath, std::ios::binary);
    out.write(magic, 8);
    out.write(reinterpret_cast<const char *>(&length), 4);
    out.write(text.data(), static_cast<std::streamsize>(text.size()));
    std::vector<float> data(dataBytes / sizeof(float), 0.01f);
    out.write(reinterpret_cast<const char *>(data.data()), static_cast<std::streamsize>(dataBytes));
}

static int failures = 0;

static void expect(const char *what, bool refused) {
    bool threw = false;
    try {
        Transformer model(kPath);
        auto memory = model.encode({5, 4, 5, 2});
        model.greedy(*memory, 3, 4);
    } catch (const std::exception &) {
        threw = true;
    }
    if (threw != refused) {
        std::printf("FAIL %s: %s\n", what, refused ? "was read" : "was refused");
        failures++;
    }
}

int main() {
    size_t bytes;
    json good = tinyHeader(&bytes);

    write(good, bytes);
    expect("a good file", false);

    write(good, bytes, "NOTAFILE");
    expect("another magic", true);

    write(good, bytes - 4);
    expect("the data cut at the end", true);

    write(good, bytes, "SLMIMEW1", 2);
    expect("data that is not float-aligned in memory", true);

    auto damaged = [&](const char *what, const std::function<void(json &)> &damage) {
        json h = good;
        damage(h);
        write(h, bytes);
        expect(what, true);
    };
    damaged("a negative dimension", [](json &h) { h["tensors"][0]["shape"] = {-6, 4}; });
    damaged("a zero dimension", [](json &h) { h["tensors"][0]["shape"] = {0, 4}; });
    damaged("dimensions whose product overflows",
            [](json &h) { h["tensors"][0]["shape"] = {16777216, 16777216, 16777216, 16777216}; });
    damaged("an offset past the end", [](json &h) { h["tensors"][3]["offset"] = 1ull << 40; });
    damaged("an offset that is not aligned", [](json &h) { h["tensors"][3]["offset"] = 2; });
    damaged("a tensor smaller than the model reads", [](json &h) { h["tensors"][0]["shape"] = {2, 4}; });
    damaged("an embedding narrower than d_model", [](json &h) { h["tensors"][0]["shape"] = {6, 2}; });
    damaged("max_len larger than the position table", [](json &h) { h["config"]["max_len"] = 64; });
    damaged("d_model not a multiple of nhead", [](json &h) { h["config"]["nhead"] = 3; });
    damaged("a huge vocab_size", [](json &h) { h["config"]["vocab_size"] = 1LL << 40; });

    // a header length that points past the end of the file
    {
        std::ofstream out(kPath, std::ios::binary);
        uint32_t length = 0xFFFFFFF0u;
        out.write("SLMIMEW1", 8);
        out.write(reinterpret_cast<const char *>(&length), 4);
        out.write("{}", 2);
    }
    expect("a header longer than the file", true);

    // a token out of the vocabulary and a source longer than max_len are refused when converting
    write(good, bytes);
    {
        Transformer model(kPath);
        for (const auto &ids : std::vector<std::vector<int>>{{99, 2}, {-1, 2}, std::vector<int>(20, 5)}) {
            bool threw = false;
            try {
                model.encode(ids);
            } catch (const std::exception &) {
                threw = true;
            }
            if (!threw) {
                std::printf("FAIL source of %zu ids was encoded\n", ids.size());
                failures++;
            }
        }
    }

    std::remove(kPath);
    if (failures == 0) {
        std::printf("weights-test: all passed\n");
    }
    return failures == 0 ? 0 : 1;
}
