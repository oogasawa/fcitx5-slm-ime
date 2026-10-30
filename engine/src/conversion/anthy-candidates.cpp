#include "conversion/candidate-source.h"

#include "conversion/text.h"

#include <anthy/anthy.h>

#include <algorithm>
#include <stdexcept>

namespace llmime::conversion {

namespace {

constexpr int kBufferBytes = 4096;

anthy_context_t ctx(void *p) { return static_cast<anthy_context_t>(p); }

int segmentCount(anthy_context_t context) {
    struct anthy_conv_stat stat {};
    anthy_get_stat(context, &stat);
    return stat.nr_segment;
}

std::u32string segmentText(anthy_context_t context, int segment, int candidate) {
    char buffer[kBufferBytes];
    int written = anthy_get_segment(context, segment, candidate, buffer, kBufferBytes);
    return written > 0 ? toU32(std::string(buffer, static_cast<size_t>(written))) : std::u32string();
}

} // namespace

AnthyCandidates::AnthyCandidates() {
    if (anthy_init() != 0) {
        throw std::runtime_error("anthy_init failed");
    }
    anthy_context_t context = anthy_create_context();
    if (context == nullptr) {
        throw std::runtime_error("anthy_create_context failed");
    }
    anthy_context_set_encoding(context, ANTHY_UTF8_ENCODING);
    context_ = context;
}

AnthyCandidates::~AnthyCandidates() {
    if (context_ != nullptr) {
        anthy_release_context(ctx(context_));
    }
}

std::vector<std::u32string> AnthyCandidates::candidates(const std::u32string &reading, size_t limit) {
    if (reading.empty() || limit == 0) {
        return {};
    }
    std::lock_guard<std::mutex> lock(mutex_);
    anthy_context_t context = ctx(context_);
    std::vector<std::u32string> found;
    auto add = [&found](std::u32string text) {
        if (!text.empty() && std::find(found.begin(), found.end(), text) == found.end()) {
            found.push_back(std::move(text));
        }
    };
    if (anthy_set_string(context, toU8(reading).c_str()) != 0) {
        return {};
    }
    std::u32string joined;
    int own = segmentCount(context);
    for (int i = 0; i < own; i++) {
        joined += segmentText(context, i, 0);
    }
    add(joined);
    for (size_t guard = reading.size() + 1; segmentCount(context) > 1 && guard > 0; guard--) {
        anthy_resize_segment(context, 0, 1);
    }
    if (segmentCount(context) < 1) {
        return {};
    }
    struct anthy_segment_stat segment {};
    anthy_get_segment_stat(context, 0, &segment);
    for (int k = 0; k < segment.nr_candidate && found.size() < limit; k++) {
        add(segmentText(context, 0, k));
    }
    return found;
}

} // namespace llmime::conversion
