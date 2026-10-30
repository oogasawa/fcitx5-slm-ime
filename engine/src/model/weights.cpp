#include "model/weights.h"

#include <nlohmann/json.hpp>

#include <cstdint>
#include <cstring>
#include <fcntl.h>
#include <stdexcept>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

namespace llmime::model {

namespace {
constexpr char kMagic[8] = {'S', 'L', 'M', 'I', 'M', 'E', 'W', '1'};
}

Weights::Weights(const std::string &path) {
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
    config_.vocabSize = c.at("vocab_size");
    config_.dModel = c.at("d_model");
    config_.nhead = c.at("nhead");
    config_.encLayers = c.at("enc_layers");
    config_.decLayers = c.at("dec_layers");
    config_.dFf = c.at("d_ff");
    config_.maxLen = c.at("max_len");

    const char *data = bytes + 12 + headerLen;
    size_t dataSize = mappedSize_ - 12 - headerLen;
    for (const auto &t : header.at("tensors")) {
        Tensor tensor;
        size_t count = 1;
        for (int dim : t.at("shape")) {
            tensor.shape.push_back(dim);
            count *= static_cast<size_t>(dim);
        }
        size_t offset = t.at("offset");
        if (offset + count * sizeof(float) > dataSize) {
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

const Tensor &Weights::get(const std::string &name) const {
    auto it = tensors_.find(name);
    if (it == tensors_.end()) {
        throw std::runtime_error("the weights have no tensor " + name);
    }
    return it->second;
}

} // namespace llmime::model
