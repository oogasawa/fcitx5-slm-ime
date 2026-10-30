// Checks the C++ model against the answers of the PyTorch model (eval/make_cpp_golden.py): the greedy output must be
// the same token for token, and the log probability of every candidate the same within 1e-3.
// The weights and the answers are large and not in the repository, so the paths come from the environment:
//   SLM_IME_WEIGHTS  the weights file (export_weights.py)
//   SLM_IME_GOLDEN   the answers (make_cpp_golden.py)
// Without them the test is skipped (exit code 77).
#include "model/transformer.h"

#include <nlohmann/json.hpp>

#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <fstream>

using llmime::model::Transformer;

int main() {
    const char *weightsPath = std::getenv("SLM_IME_WEIGHTS");
    const char *goldenPath = std::getenv("SLM_IME_GOLDEN");
    if (weightsPath == nullptr || goldenPath == nullptr) {
        std::printf("model-check: SLM_IME_WEIGHTS or SLM_IME_GOLDEN is not set; skipped\n");
        return 77;
    }
    auto start = std::chrono::steady_clock::now();
    Transformer model(weightsPath);
    double loadMs = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start).count();

    std::ifstream in(goldenPath);
    auto cases = nlohmann::json::parse(in);
    int failures = 0;
    double worst = 0;
    double totalMs = 0;
    for (const auto &c : cases) {
        auto t0 = std::chrono::steady_clock::now();
        auto memory = model.encode(c.at("src").get<std::vector<int>>());
        auto output = model.greedy(*memory, c.at("max_text_len"), c.at("bar"));
        totalMs += std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
        if (output != c.at("greedy").get<std::vector<int>>()) {
            std::printf("FAIL greedy of %s\n", c.at("reading").get<std::string>().c_str());
            failures++;
        }
        auto texts = c.at("texts").get<std::vector<std::vector<int>>>();
        auto logprobs = model.logProbabilities(*memory, texts);
        for (size_t i = 0; i < texts.size(); i++) {
            double want = c.at("logprobs")[i];
            double diff = std::fabs(logprobs[i] - want);
            worst = std::max(worst, diff);
            if (diff > 1e-3) {
                std::printf("FAIL log probability %zu of %s: got %.6f, want %.6f\n", i,
                            c.at("reading").get<std::string>().c_str(), logprobs[i], want);
                failures++;
            }
        }
    }
    std::printf("model-check: %zu cases, %d failures, largest difference of a log probability %.2e, "
                "load %.0f ms, conversion %.1f ms on average\n",
                cases.size(), failures, worst, loadMs, totalMs / cases.size());
    return failures == 0 ? 0 : 1;
}
