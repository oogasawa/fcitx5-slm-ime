// Checks, with the real model, that the history of the user's choices can be turned off and on again: while it is off
// the choices are not put into the answer or first in the candidates, and a choice reported then is not kept; turned
// back on, the choices from before are used again. The weights are not in the repository, so the directory comes
// from the environment:
//   SLM_IME_MODEL_DIR  slm-ime-weights.bin and tokenizer.json
// Without it the test is skipped (exit code 77).
#include "conversion/converter.h"
#include "conversion/text.h"

#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <string>
#include <unistd.h>
#include <vector>

using namespace llmime::conversion;

static int failures = 0;

static void check(const char *what, const std::string &got, const std::string &want) {
    if (got != want) {
        std::printf("FAIL %s: got \"%s\", want \"%s\"\n", what, got.c_str(), want.c_str());
        failures++;
    }
}

static void check(const char *what, bool ok) {
    if (!ok) {
        std::printf("FAIL %s\n", what);
        failures++;
    }
}

static std::string first(Converter &converter, const char32_t *reading) {
    auto candidates = converter.segmentCandidates(reading, U"", 20);
    return candidates.empty() ? "" : toU8(candidates[0]);
}

static std::string contentsOf(const std::string &path) {
    std::ifstream in(path);
    return std::string(std::istreambuf_iterator<char>(in), std::istreambuf_iterator<char>());
}

int main() {
    const char *dir = std::getenv("SLM_IME_MODEL_DIR");
    if (dir == nullptr) {
        std::printf("history-check: SLM_IME_MODEL_DIR is not set; skipped\n");
        return 77;
    }
    char path[] = "/tmp/history-check-XXXXXX";
    int fd = mkstemp(path);
    if (fd < 0) {
        std::printf("history-check: no temporary file\n");
        return 1;
    }
    close(fd);

    ConverterSettings settings;
    settings.weightsPath = std::string(dir) + "/slm-ime-weights.bin";
    settings.tokenizerPath = std::string(dir) + "/tokenizer.json";
    settings.historyPath = path;
    Converter converter(settings, nullptr);

    std::string model = first(converter, U"\u3082\u3046");  // the answer of the model for もう
    check("the model alone does not put 望 first", model != toU8(U"\u671b"));

    // The control: with the history used, one choice made by mistake takes the first place at once.
    converter.record(U"\u3082\u3046", U"\u671b", U"\u3082\u3046");
    check("the history used: the choice comes first", first(converter, U"\u3082\u3046"), toU8(U"\u671b"));
    std::string kept = contentsOf(path);
    check("and it is written to the history file", !kept.empty());

    // Off: not used.
    converter.setHistoryUsed(false);
    check("the history off: the model's answer comes first", first(converter, U"\u3082\u3046"), model);
    check("the history off: not put into the answer",
          toU8(converter.convert(U"\u3082\u3046", U"")) != toU8(U"\u671b"));

    // Off: not added to.
    converter.record(U"\u3058", U"\u6642", U"");
    check("the history off: nothing is added to the file", contentsOf(path) == kept);

    // On again: what was kept is used again, and what was reported while it was off is not there.
    converter.setHistoryUsed(true);
    check("on again: the earlier choice is used again", first(converter, U"\u3082\u3046"), toU8(U"\u671b"));
    check("on again: the choice reported while off was not kept", first(converter, U"\u3058") != toU8(U"\u6642") ||
                                                                      model == toU8(U"\u6642"));
    std::remove(path);
    if (failures == 0) {
        std::printf("history-check: all passed\n");
    }
    return failures == 0 ? 0 : 1;
}
