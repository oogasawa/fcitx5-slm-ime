#pragma once
// The operations of the engine that keys are bound to, and the keys of each key binding profile. Kept apart from the
// engine so that the profiles can be tested without fcitx5 (test/key-actions-test.cpp).
//
// The keys are written as fcitx5 writes them in its configuration (Key::toString), for example "Control+Return".
// The MS-IME and ATOK profiles follow the key binding styles of those input methods as the style files of
// fcitx5-anthy (msime.sty, atok.sty) describe them, for the operations this engine has.
#include <string>
#include <vector>

namespace llmime {

enum class Action {
    // While typing the reading
    Convert,          // start the conversion
    Commit,           // commit the reading as it is, or the conversion
    Cancel,           // forget the reading
    Backspace,        // delete the kana before the cursor
    Delete,           // delete the kana after the cursor
    CaretLeft,        // move the cursor one kana to the left
    CaretRight,       // move the cursor one kana to the right
    CaretFirst,       // move the cursor to the start of the reading
    CaretLast,        // move the cursor to the end of the reading
    // While converting
    BackToReading,    // leave the conversion and go back to the reading
    NextCandidate,
    PrevCandidate,
    NextSegment,
    PrevSegment,
    FirstSegment,
    LastSegment,
    ExpandSegment,    // move the border after the active bunsetsu one kana to the right
    ShrinkSegment,    // move it one kana to the left
    // In both
    ModelConvert,     // the model's conversion of the whole reading as one candidate
    ToHiragana,       // the active bunsetsu (the whole reading while typing) in hiragana
    ToKatakana,       // in katakana
    ToHalfKatakana,   // in half-width katakana
    ToWideLatin,      // as the typed letters, full-width
    ToLatin,          // as the typed letters
    TakeBack,         // right after a commit: take the commit back and show the conversion again
};

enum class KeyProfile { Default, MSIME, ATOK, Custom };

// Where an operation is looked for.
enum ActionModes {
    kTyping = 1,
    kConverting = 2,
};

struct ActionInfo {
    Action action;
    const char *name;   // the name in the configuration file
    const char *label;  // the label in the configuration tool
    int modes;
    std::vector<std::string> defaultKeys;
    std::vector<std::string> msimeKeys;
    std::vector<std::string> atokKeys;
};

// Every operation, in the order of the configuration tool. In each mode one key starts at most one operation (the
// test checks it), so the order does not decide anything.
inline const std::vector<ActionInfo> &actionInfos() {
    static const std::vector<ActionInfo> infos = {
        {Action::Convert, "Convert", "Convert", kTyping,
         {"space"}, {"space", "Shift+space", "Henkan"}, {"space", "Henkan"}},
        {Action::Commit, "Commit", "Commit", kTyping | kConverting,
         {"Return"}, {"Return"}, {"Return", "Control+m"}},
        {Action::Cancel, "Cancel", "Cancel the reading", kTyping,
         {"Escape"}, {"Escape"}, {"Escape"}},
        {Action::Backspace, "Backspace", "Delete the character before the cursor", kTyping,
         {"BackSpace"}, {"BackSpace", "Control+h"}, {"BackSpace", "Control+h"}},
        {Action::Delete, "Delete", "Delete the character after the cursor", kTyping,
         {"Delete"}, {"Delete", "Control+g"}, {"Delete", "Control+g"}},
        {Action::CaretLeft, "CaretLeft", "Move the cursor left", kTyping,
         {"Left"}, {"Left", "Shift+Left"}, {"Left", "Control+k"}},
        {Action::CaretRight, "CaretRight", "Move the cursor right", kTyping,
         {"Right"}, {"Right", "Shift+Right"}, {"Right", "Control+l"}},
        {Action::CaretFirst, "CaretFirst", "Move the cursor to the start", kTyping,
         {"Home"}, {"Home", "Control+Left"}, {"Home", "Control+Left"}},
        {Action::CaretLast, "CaretLast", "Move the cursor to the end", kTyping,
         {"End"}, {"End", "Control+Right"}, {"End", "Control+Right"}},
        {Action::BackToReading, "BackToReading", "Back to the reading", kConverting,
         {"Escape"}, {"Escape", "BackSpace"}, {"Escape", "BackSpace"}},
        {Action::NextCandidate, "NextCandidate", "Next candidate", kConverting,
         {"space", "Down"}, {"space", "Tab", "Henkan", "Down"}, {"space", "Tab", "Henkan"}},
        {Action::PrevCandidate, "PrevCandidate", "Previous candidate", kConverting,
         {"Up"}, {"Up", "Shift+space", "Shift+Henkan"}, {"Up"}},
        {Action::NextSegment, "NextSegment", "Next bunsetsu", kConverting,
         {"Right"}, {"Right", "Control+d"}, {"Shift+Right"}},
        {Action::PrevSegment, "PrevSegment", "Previous bunsetsu", kConverting,
         {"Left"}, {"Left", "Control+s"}, {"Shift+Left"}},
        {Action::FirstSegment, "FirstSegment", "First bunsetsu", kConverting,
         {"Home"}, {"Home", "Control+Left"}, {"Control+Left"}},
        {Action::LastSegment, "LastSegment", "Last bunsetsu", kConverting,
         {"End"}, {"End", "Control+Right"}, {"Control+Right"}},
        {Action::ExpandSegment, "ExpandSegment", "Make the bunsetsu longer", kConverting,
         {"Shift+Right"}, {"Shift+Right", "Control+l"}, {"Right", "Control+l"}},
        {Action::ShrinkSegment, "ShrinkSegment", "Make the bunsetsu shorter", kConverting,
         {"Shift+Left"}, {"Shift+Left", "Control+e"}, {"Left", "Control+k"}},
        {Action::ModelConvert, "ModelConvert", "Convert the whole reading with the model only",
         kTyping | kConverting, {"Control+Return"}, {"Control+Return"}, {"Control+Return"}},
        {Action::ToHiragana, "ToHiragana", "Hiragana", kTyping | kConverting,
         {"F6"}, {"F6", "Control+u"}, {"F6", "Control+u"}},
        {Action::ToKatakana, "ToKatakana", "Katakana", kTyping | kConverting,
         {"F7"}, {"F7", "Control+i"}, {"F7", "Control+i"}},
        {Action::ToHalfKatakana, "ToHalfKatakana", "Half-width katakana", kTyping | kConverting,
         {"F8"}, {"F8", "Control+o"}, {"F8", "Control+o"}},
        {Action::ToWideLatin, "ToWideLatin", "Full-width alphabet", kTyping | kConverting,
         {"F9"}, {"F9", "Control+p"}, {"F9", "Control+p"}},
        {Action::ToLatin, "ToLatin", "Alphabet", kTyping | kConverting,
         {"F10"}, {"F10", "Control+t"}, {"F10", "Control+at"}},
        {Action::TakeBack, "TakeBack", "Take the last commit back", kTyping | kConverting,
         {"Control+Shift+Insert"}, {"Control+Shift+Insert"}, {"Control+Shift+Insert"}},
    };
    return infos;
}

// The keys of an operation in a profile. Custom has no keys of its own: they are in the configuration.
inline const std::vector<std::string> &profileKeys(const ActionInfo &info, KeyProfile profile) {
    static const std::vector<std::string> none;
    switch (profile) {
    case KeyProfile::Default:
        return info.defaultKeys;
    case KeyProfile::MSIME:
        return info.msimeKeys;
    case KeyProfile::ATOK:
        return info.atokKeys;
    case KeyProfile::Custom:
        break;
    }
    return none;
}

} // namespace llmime
