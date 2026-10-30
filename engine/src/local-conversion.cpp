#include "local-conversion.h"

#include "conversion/converter.h"

#include <fcitx-utils/log.h>

#include <cstdlib>
#include <fcntl.h>
#include <filesystem>
#include <unistd.h>
#include <stdexcept>

namespace llm_ime {

namespace {

using llmime::conversion::AnthyCandidates;
using llmime::conversion::CandidateSource;
using llmime::conversion::Converter;
using llmime::conversion::ConverterSettings;

std::string environment(const char *name, const std::string &otherwise) {
    const char *value = std::getenv(name);
    return value != nullptr && *value != '\0' ? value : otherwise;
}

std::shared_ptr<Converter> load() {
    std::string model = environment("SLM_IME_MODEL_DIR", "/usr/share/slm-ime/model");
    std::filesystem::path data =
        std::filesystem::path(environment("XDG_DATA_HOME", environment("HOME", ".") + "/.local/share")) / "slm-ime";
    // The history holds words the user typed: only the user may read it
    std::error_code ignored;
    std::filesystem::create_directories(data, ignored);
    std::filesystem::permissions(data, std::filesystem::perms::owner_all, ignored);
    std::filesystem::path history = data / "user_history.tsv";
    if (!std::filesystem::exists(history, ignored)) {
        int fd = open(history.c_str(), O_WRONLY | O_CREAT | O_CLOEXEC, 0600);
        if (fd >= 0) {
            close(fd);
        }
    }
    std::filesystem::permissions(history, std::filesystem::perms::owner_read | std::filesystem::perms::owner_write,
                                 ignored);

    ConverterSettings settings;
    settings.weightsPath = model + "/slm-ime-weights.bin";
    settings.tokenizerPath = model + "/tokenizer.json";
    settings.historyPath = history.string();
    std::unique_ptr<CandidateSource> anthy;
    try {
        anthy = std::make_unique<AnthyCandidates>();
    } catch (const std::exception &e) {
        FCITX_WARN() << "slm-ime: no dictionary candidates: " << e.what();
    }
    auto converter = std::make_shared<Converter>(settings, std::move(anthy));
    // One conversion reads the pages of the weights from the disk now, not at the first key of the user.
    converter->translate(U"あ", U"");
    return converter;
}

} // namespace

LocalConversion::LocalConversion() : converter_(std::async(std::launch::async, [] {
                                         try {
                                             return load();
                                         } catch (const std::exception &e) {
                                             FCITX_ERROR() << "slm-ime: the model could not be read: " << e.what();
                                             throw;
                                         }
                                     }).share()) {}

LocalConversion::~LocalConversion() = default;

nlohmann::json LocalConversion::post(const std::string &path, const nlohmann::json &body) {
    std::shared_ptr<Converter> converter;
    try {
        converter = converter_.get();
    } catch (const std::exception &e) {
        throw std::runtime_error(std::string("no model: ") + e.what());
    }
    return converter->post(path, body);
}

nlohmann::json LocalConversion::get(const std::string & /*pathWithQuery*/) { return nlohmann::json::array(); }

} // namespace llm_ime
