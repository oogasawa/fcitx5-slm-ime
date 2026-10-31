#pragma once
// The configuration of the engine, shown by fcitx5-configtool and kept in conf/llm-ime.conf: the key binding
// profile and the keys of every operation, in three groups. The keys shown are always the keys in effect: choosing a
// profile puts its keys (src/key-actions.h) into the lists, and changing a key in a list makes the profile Custom (the engine does
// both when the configuration is saved). The labels are English; po/ has their translations. SLM_IME_KEYS_PATH is the
// installed slm-ime-keys, which shows the keys of every profile.
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

// The keys of the operations of one group of actionGroup() (while typing, while converting, in both), one option per
// operation, in the order of actionInfos(). They start as the keys of the Default profile. The configuration tool
// shows each group under its own heading.
template <int Group>
class SlmImeKeyGroup : public fcitx::Configuration {
public:
    SlmImeKeyGroup() {
        const auto &infos = actionInfos();
        for (size_t i = 0; i < infos.size(); i++) {
            if (actionGroup(infos[i].action) == Group) {
                indexes_.push_back(i);
                keys_.push_back(std::make_unique<fcitx::KeyListOption>(
                    this, infos[i].name, _(infos[i].label), keyList(infos[i].defaultKeys),
                    fcitx::KeyListConstrain(fcitx::KeyConstrainFlag::AllowModifierLess)));
            }
        }
    }
    SlmImeKeyGroup(const SlmImeKeyGroup &other) : SlmImeKeyGroup() { copyHelper(other); }
    SlmImeKeyGroup &operator=(const SlmImeKeyGroup &other) {
        copyHelper(other);
        return *this;
    }
    bool operator==(const SlmImeKeyGroup &other) const { return compareHelper(other); }
    const char *typeName() const override;

    // The option of the operation at `index` in actionInfos(); null for an operation of another group.
    fcitx::KeyListOption *option(size_t index) const {
        for (size_t k = 0; k < indexes_.size(); k++) {
            if (indexes_[k] == index) {
                return keys_[k].get();
            }
        }
        return nullptr;
    }

private:
    std::vector<size_t> indexes_;
    std::vector<std::unique_ptr<fcitx::KeyListOption>> keys_;
};
template <> inline const char *SlmImeKeyGroup<0>::typeName() const { return "SlmImeKeyGroup0"; }
template <> inline const char *SlmImeKeyGroup<1>::typeName() const { return "SlmImeKeyGroup1"; }
template <> inline const char *SlmImeKeyGroup<2>::typeName() const { return "SlmImeKeyGroup2"; }
FCITX_SPECIALIZE_TYPENAME(SlmImeKeyGroup<0>, "SlmImeKeyGroup0")
FCITX_SPECIALIZE_TYPENAME(SlmImeKeyGroup<1>, "SlmImeKeyGroup1")
FCITX_SPECIALIZE_TYPENAME(SlmImeKeyGroup<2>, "SlmImeKeyGroup2")

FCITX_CONFIGURATION(
    SlmImeConfig,
    fcitx::OptionWithAnnotation<KeyProfile, KeyProfileI18NAnnotation> profile{
        this, "KeyProfile", _("Key binding profile (press OK to put its keys into the lists below)"),
        KeyProfile::Default};
    // A button that opens the table of the keys of every profile in a browser (slm-ime-keys)
    fcitx::ExternalOption keyTable{this, "KeyTable", _("Table of the keys of every profile (opens in a browser)"),
                                   SLM_IME_KEYS_PATH};
    fcitx::Option<SlmImeKeyGroup<0>> typingKeys{
        this, "TypingKeys", _("Keys while typing the reading (changing one makes the profile Custom)")};
    fcitx::Option<SlmImeKeyGroup<1>> convertingKeys{this, "ConvertingKeys", _("Keys while converting")};
    fcitx::Option<SlmImeKeyGroup<2>> bothKeys{this, "KeysInBoth", _("Keys in both")};);

// The keys in effect of the operation at `index` in actionInfos().
inline fcitx::KeyList configKeys(const SlmImeConfig &config, size_t index) {
    switch (actionGroup(actionInfos()[index].action)) {
    case 0:
        return config.typingKeys->option(index)->value();
    case 1:
        return config.convertingKeys->option(index)->value();
    default:
        return config.bothKeys->option(index)->value();
    }
}

// Whether every operation has the keys of `profile` (Custom has none of its own).
inline bool configHasKeysOf(const SlmImeConfig &config, KeyProfile profile) {
    for (size_t i = 0; i < actionInfos().size(); i++) {
        if (configKeys(config, i) != keyList(profileKeys(actionInfos()[i], profile))) {
            return false;
        }
    }
    return true;
}

// Puts the keys of `profile` into every operation.
inline void configTakeKeysOf(SlmImeConfig &config, KeyProfile profile) {
    SlmImeKeyGroup<0> typing = *config.typingKeys;
    SlmImeKeyGroup<1> converting = *config.convertingKeys;
    SlmImeKeyGroup<2> both = *config.bothKeys;
    for (size_t i = 0; i < actionInfos().size(); i++) {
        fcitx::KeyList keys = keyList(profileKeys(actionInfos()[i], profile));
        for (fcitx::KeyListOption *option : {typing.option(i), converting.option(i), both.option(i)}) {
            if (option != nullptr) {
                option->setValue(keys);
            }
        }
    }
    config.typingKeys.setValue(typing);
    config.convertingKeys.setValue(converting);
    config.bothKeys.setValue(both);
}

} // namespace llmime
