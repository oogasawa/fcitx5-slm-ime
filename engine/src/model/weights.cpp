#include "model/weights.h"

#include <nlohmann/json.hpp>

#include <cstdint>
#include <cstring>
#include <limits>
#include <fcntl.h>
#include <stdexcept>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

namespace llmime::model {

namespace {
constexpr char kMagic[8] = {'S', 'L', 'M', 'I', 'M', 'E', 'W', '1'};
// No dimension of the model is larger than this; larger numbers in a header are damage.
constexpr int kLargestDimension = 1 << 24;

int dimension(const nlohmann::json &value, const char *what) {
    long long n = value.get<long long>();
    if (n <= 0 || n > kLargestDimension) {
        throw std::runtime_error(std::string("the weights have a wrong ") + what);
    }
    return static_cast<int>(n);
}
} // namespace

Weights::Weights(const std::string &path) {
    try {
        read(path);
    } catch (...) {
        if (mapped_ != nullptr) {
            munmap(mapped_, mappedSize_);
        }
        throw;
    }
}

void Weights::read(const std::string &path) {
    int fd = open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        throw std::runtime_error("cannot open the weights " + path);
    }
    struct stat st {};
    if (fstat(fd, &st) != 0 || st.st_size < 12) {
        close(fd);
        throw std::runtime_error("not a weights file: " + path);
    }
    mappedSize_ = static_cast<size_t>(st.st_size);
    mapped_ = mmap(nullptr, mappedSize_, PROT_READ, MAP_PRIVATE, fd, 0);
    close(fd);
    if (mapped_ == MAP_FAILED) {
        mapped_ = nullptr;
        throw std::runtime_error("cannot map the weights " + path);
    }
    const char *bytes = static_cast<const char *>(mapped_);
    if (std::memcmp(bytes, kMagic, sizeof kMagic) != 0) {
        throw std::runtime_error("not a weights file: " + path);
    }
    uint32_t headerLen = 0;
    std::memcpy(&headerLen, bytes + 8, 4);  // little endian, as the machines this runs on
    if (12 + static_cast<size_t>(headerLen) > mappedSize_) {
        throw std::runtime_error("the header of the weights is cut: " + path);
    }
    auto header = nlohmann::json::parse(bytes + 12, bytes + 12 + headerLen);
    const auto &c = header.at("config");
    config_.vocabSize = dimension(c.at("vocab_size"), "vocab_size");
    config_.dModel = dimension(c.at("d_model"), "d_model");
    config_.nhead = dimension(c.at("nhead"), "nhead");
    config_.encLayers = dimension(c.at("enc_layers"), "enc_layers");
    config_.decLayers = dimension(c.at("dec_layers"), "dec_layers");
    config_.dFf = dimension(c.at("d_ff"), "d_ff");
    config_.maxLen = dimension(c.at("max_len"), "max_len");
    if (config_.dModel % config_.nhead != 0) {
        throw std::runtime_error("the weights have a d_model that is not a multiple of nhead");
    }

    const char *data = bytes + 12 + headerLen;
    size_t dataSize = mappedSize_ - 12 - headerLen;
    for (const auto &t : header.at("tensors")) {
        Tensor tensor;
        size_t count = 1;
        for (const auto &dim : t.at("shape")) {
            int n = dimension(dim, "shape");
            tensor.shape.push_back(n);
            if (count > std::numeric_limits<size_t>::max() / sizeof(float) / static_cast<size_t>(n)) {
                throw std::runtime_error("a tensor of the weights is too large");
            }
            count *= static_cast<size_t>(n);
        }
        unsigned long long offset = t.at("offset").get<unsigned long long>();
        if (offset % alignof(float) != 0 || offset > dataSize || count > (dataSize - offset) / sizeof(float)) {
            throw std::runtime_error("a tensor of the weights is cut: " + t.at("name").get<std::string>());
        }
        tensor.data = reinterpret_cast<const float *>(data + offset);
        tensors_.emplace(t.at("name").get<std::string>(), std::move(tensor));
    }
}

Weights::~Weights() {
    if (mapped_ != nullptr) {
        munmap(mapped_, mappedSize_);
    }
}

const Tensor &Weights::get(const std::string &name, const std::vector<int> &shape) const {
    auto it = tensors_.find(name);
    if (it == tensors_.end()) {
        throw std::runtime_error("the weights have no tensor " + name);
    }
    if (it->second.shape != shape) {
        throw std::runtime_error("the tensor " + name + " of the weights has another shape");
    }
    return it->second;
}

} // namespace llmime::model
