#pragma once
// The table of the key bindings that slm-ime-keys shows in a browser: every operation with the keys in effect and the
// keys of the Default, MS-IME and ATOK profiles. Kept apart from fcitx5 so that it can be tested
// (test/key-table-test.cpp); the caller gives the translated texts and the names of the keys.
#include "key-actions.h"

#include <functional>
#include <string>
#include <vector>

namespace llmime {

// The words of the page, already translated.
struct KeyTableText {
    std::string title;
    std::string operation;  // the header of the column of the operations
    std::string inEffect;   // the header of the column of the keys in effect
    std::string current;    // put after the name of the profile in effect
    std::string typing;     // the group of the operations while typing the reading
    std::string converting; // while converting
    std::string both;       // in both
    std::function<std::string(const char *)> translate; // the labels of the operations and the names of the profiles
};

// The group of an operation in the table: 0 while typing, 1 while converting, 2 in both (the groups of Action).
int keyTableGroup(Action action);

// The page. `inEffect` holds the keys in effect of every operation, in the order of actionInfos(); `profile` is the
// profile in effect, whose column is marked; `keyName` gives the name of a key as the configuration tool shows it.
std::string keyTableHtml(const KeyTableText &text, const std::vector<std::vector<std::string>> &inEffect,
                         KeyProfile profile, const std::function<std::string(const std::string &)> &keyName);

} // namespace llmime
