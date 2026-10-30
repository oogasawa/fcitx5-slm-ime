#pragma once
// The conversion inside the engine. It answers the requests that the engine sent to the conversion server of
// version 1 (post("/api/segment-convert", ...) and the others), so that the engine calls it as it called the server.
//
// The model is in $SLM_IME_MODEL_DIR, or /usr/share/slm-ime/model: slm-ime-weights.bin and tokenizer.json. The
// choices of the user are kept in $XDG_DATA_HOME/slm-ime/user_history.tsv, the file of version 1. Anthy gives the
// dictionary candidates; without Anthy there are none.
#include <nlohmann/json.hpp>

#include <future>
#include <memory>
#include <string>

namespace llmime::conversion {
class Converter;
}

namespace llm_ime {

class LocalConversion {
public:
    // Starts reading the model in the background, so that the first conversion does not wait for it.
    LocalConversion();
    ~LocalConversion();

    // Waits until the model is read. Throws std::runtime_error when it could not be read.
    nlohmann::json post(const std::string &path, const nlohmann::json &body);

    // /api/predict: no predictions.
    nlohmann::json get(const std::string &pathWithQuery);

private:
    std::shared_future<std::shared_ptr<llmime::conversion::Converter>> converter_;
};

} // namespace llm_ime
