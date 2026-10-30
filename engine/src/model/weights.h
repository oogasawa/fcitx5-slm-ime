#pragma once
// The weights of the conversion model, read from the file that export_weights.py writes. The file is mapped into
// memory, so the weights are shared between the processes that read the same file and are not copied.
#include <cstddef>
#include <map>
#include <string>
#include <vector>

namespace llmime::model {

// What is needed to build the model, from the header of the file.
struct Config {
    int vocabSize = 0;
    int dModel = 0;
    int nhead = 0;
    int encLayers = 0;
    int decLayers = 0;
    int dFf = 0;
    int maxLen = 0;
};

// One tensor of the file: its shape and its float32 data, row-major as in PyTorch.
struct Tensor {
    std::vector<int> shape;
    const float *data = nullptr;
    int rows() const { return shape.empty() ? 0 : shape[0]; }
    int cols() const { return shape.size() < 2 ? 1 : shape[1]; }
};

class Weights {
public:
    // Maps the file; throws std::runtime_error when it cannot be read or is not a weights file.
    explicit Weights(const std::string &path);
    ~Weights();
    Weights(const Weights &) = delete;
    Weights &operator=(const Weights &) = delete;

    const Config &config() const { return config_; }
    // The tensor of that name; throws std::runtime_error when there is none.
    const Tensor &get(const std::string &name) const;

private:
    Config config_;
    std::map<std::string, Tensor> tensors_;
    void *mapped_ = nullptr;
    size_t mappedSize_ = 0;
};

} // namespace llmime::model
