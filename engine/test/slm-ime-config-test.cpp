// Tests of the configuration in three groups: every operation in exactly one group, the keys of a profile put into
// the groups and recognized again, and the keys kept through a save and a load.
#include "slm-ime-config.h"

#include <fcitx-config/rawconfig.h>

#include <cstdio>

using namespace llmime;

static int failures = 0;

static void check(const char *what, bool ok) {
    if (!ok) {
        std::printf("FAIL %s\n", what);
        failures++;
    }
}

int main() {
    SlmImeConfig config;
    for (size_t i = 0; i < actionInfos().size(); i++) {
        int found = (config.typingKeys->option(i) != nullptr) + (config.convertingKeys->option(i) != nullptr) +
                    (config.bothKeys->option(i) != nullptr);
        if (found != 1) {
            std::printf("FAIL %s is in %d groups\n", actionInfos()[i].name, found);
            failures++;
        }
    }
    check("a new configuration has the keys of Default", configHasKeysOf(config, KeyProfile::Default));
    check("and not those of ATOK", !configHasKeysOf(config, KeyProfile::ATOK));

    configTakeKeysOf(config, KeyProfile::ATOK);
    check("the keys of ATOK are put into the groups", configHasKeysOf(config, KeyProfile::ATOK));
    check("the keys of ATOK for the next bunsetsu", configKeys(config, static_cast<size_t>(Action::NextSegment)) ==
                                                        keyList({"Shift+Right"}));

    fcitx::RawConfig raw;
    config.save(raw);
    check("the groups are saved under their names", raw.get("TypingKeys/Convert") && raw.get("ConvertingKeys/NextSegment") &&
                                                        raw.get("KeysInBoth/TakeBack"));
    SlmImeConfig loaded;
    loaded.load(raw);
    check("the keys come back after a save and a load", configHasKeysOf(loaded, KeyProfile::ATOK));

    if (failures == 0) {
        std::printf("slm-ime-config-test: all passed\n");
    }
    return failures == 0 ? 0 : 1;
}
