// Tests of the key binding profiles (src/key-actions.h): in each mode one key starts at most one operation, and
// every key is one that fcitx5 can read. Needs the fcitx5 utilities library only.
#include "key-actions.h"

#include <fcitx-utils/key.h>

#include <cstdio>
#include <map>
#include <string>

using namespace llmime;

int main() {
    int failures = 0;
    const std::pair<KeyProfile, const char *> profiles[] = {
        {KeyProfile::Default, "Default"}, {KeyProfile::MSIME, "MS-IME"}, {KeyProfile::ATOK, "ATOK"}};
    for (const auto &[profile, profileName] : profiles) {
        for (int mode : {kTyping, kConverting}) {
            std::map<std::string, std::string> taken;  // the normalized key and the operation that has it
            for (const auto &info : actionInfos()) {
                if (!(info.modes & mode)) {
                    continue;
                }
                const auto &keys = profileKeys(info, profile);
                if (keys.empty()) {
                    std::printf("FAIL %s: %s has no key\n", profileName, info.name);
                    failures++;
                }
                for (const auto &text : keys) {
                    fcitx::Key key(text);
                    if (!key.isValid()) {
                        std::printf("FAIL %s: %s: fcitx5 cannot read \"%s\"\n", profileName, info.name,
                                    text.c_str());
                        failures++;
                        continue;
                    }
                    if (key.toString() != text) {
                        // fcitx5 compares letters as they are: Control+M never matches the key Control+m
                        std::printf("FAIL %s: %s: \"%s\" is written \"%s\" by fcitx5\n", profileName, info.name,
                                    text.c_str(), key.toString().c_str());
                        failures++;
                    }
                    std::string normalized = key.normalize().toString();
                    auto [at, added] = taken.emplace(normalized, info.name);
                    if (!added) {
                        std::printf("FAIL %s, %s: %s is bound to both %s and %s\n", profileName,
                                    mode == kTyping ? "typing" : "converting", normalized.c_str(),
                                    at->second.c_str(), info.name);
                        failures++;
                    }
                }
            }
        }
    }
    if (failures == 0) {
        std::printf("key-actions-test: all passed\n");
    }
    return failures == 0 ? 0 : 1;
}
