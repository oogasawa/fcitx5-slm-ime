#pragma once
// The configuration of the engine, shown by fcitx5-configtool and kept in conf/llm-ime.conf: the key binding
// profile and the keys of every operation. The keys shown are always the keys in effect: choosing a profile puts its
// keys (src/key-actions.h) into the list, and changing a key in the list makes the profile Custom (the engine does
// both when the configuration is saved). The labels are English; po/ has their translations.
#include "key-actions.h"

#include <fcitx-config/configuration.h>
#include <fcitx-config/enum.h>
#include <fcitx-config/option.h>
#include <fcitx-utils/i18n.h>
#include <fcitx-utils/key.h>

#include <memory>
#include <vector>

namespace llmime {

FCITX_CONFIG_ENUM_NAME_WITH_I18N(KeyProfile, N_("Default"), N_("MS-IME"), N_("ATOK"), N_("Custom"));

inline fcitx::KeyList keyList(const std::vector<std::string> &keys) {
    fcitx::KeyList list;
    for (const auto &k : keys) {
        list.emplace_back(k);
    }
    return list;
}

// The keys of every operation, one option per operation, in the order of actionInfos(). They start as the keys of
// the Default profile.
class SlmImeKeyConfig : public fcitx::Configuration {
public:
    SlmImeKeyConfig() {
        for (const auto &info : actionInfos()) {
            keys_.push_back(std::make_unique<fcitx::KeyListOption>(
                this, info.name, _(info.label), keyList(info.defaultKeys),
                fcitx::KeyListConstrain(fcitx::KeyConstrainFlag::AllowModifierLess)));
        }
    }
    SlmImeKeyConfig(const SlmImeKeyConfig &other) : SlmImeKeyConfig() { copyHelper(other); }
    SlmImeKeyConfig &operator=(const SlmImeKeyConfig &other) {
        copyHelper(other);
        return *this;
    }
    bool operator==(const SlmImeKeyConfig &other) const { return compareHelper(other); }
    const char *typeName() const override { return "SlmImeKeyConfig"; }

    // The keys of the operation at `index` in actionInfos().
    const fcitx::KeyList &keys(size_t index) const { return keys_[index]->value(); }
    void setKeys(size_t index, const fcitx::KeyList &keys) { keys_[index]->setValue(keys); }

    // Whether every operation has the keys of `profile` (Custom has none of its own).
    bool hasKeysOf(KeyProfile profile) const {
        for (size_t i = 0; i < actionInfos().size(); i++) {
            if (keys(i) != keyList(profileKeys(actionInfos()[i], profile))) {
                return false;
            }
        }
        return true;
    }
    // Puts the keys of `profile` into every operation.
    void takeKeysOf(KeyProfile profile) {
        for (size_t i = 0; i < actionInfos().size(); i++) {
            setKeys(i, keyList(profileKeys(actionInfos()[i], profile)));
        }
    }

private:
    std::vector<std::unique_ptr<fcitx::KeyListOption>> keys_;
};
FCITX_SPECIALIZE_TYPENAME(SlmImeKeyConfig, "SlmImeKeyConfig")

FCITX_CONFIGURATION(
    SlmImeConfig,
    fcitx::OptionWithAnnotation<KeyProfile, KeyProfileI18NAnnotation> profile{
        this, "KeyProfile", _("Key binding profile (press OK to put its keys into the list below)"),
        KeyProfile::Default};
    fcitx::Option<SlmImeKeyConfig> keys{
        this, "Keys", _("Keys in effect (changing one makes the profile Custom)")};);

} // namespace llmime
