#include "local-conversion.h"
#include "char-kinds.h"
#include "committed-text.h"
#include "corrections.h"
#include "romaji.h"
#include "slm-ime-config.h"
#include <fcitx-config/iniparser.h>
#include <fcitx-utils/log.h>
#include <fcitx/addonfactory.h>
#include <fstream>
#include <fcitx/addonmanager.h>
#include <fcitx/inputcontext.h>
#include <fcitx/inputmethodengine.h>
#include <fcitx/inputpanel.h>
#include <fcitx/instance.h>
#include <fcitx/candidatelist.h>
#include <fcitx-utils/eventdispatcher.h>
#include <fcitx-utils/inputbuffer.h>
#include <fcitx-utils/key.h>
#include <algorithm>
#include <chrono>
#include <condition_variable>
#include <functional>
#include <memory>
#include <mutex>
#include <optional>
#include <set>
#include <string>
#include <thread>
#include <vector>

namespace {

using namespace fcitx;
using namespace llmime;
using json = nlohmann::json;

// Segment from the /api/segment-convert answer
struct Segment {
    std::string reading;
    std::vector<std::string> candidates;
    int selectedIndex = 0;
    bool llmFetched = false;
};

// Takes the candidates of a bunsetsu from the /api/segment-candidates answer. The conversion puts them in the
// order the model gives them after the text before the bunsetsu, so its order is taken; the candidates it does not
// have follow, and the selection stays on the same text.
static void takeCandidates(Segment &seg, const nlohmann::json &resp) {
    if (!resp.contains("candidates")) {
        return;
    }
    std::string selected = seg.candidates.empty() ? "" : seg.candidates[seg.selectedIndex];
    std::vector<std::string> ordered;
    std::set<std::string> seen;
    for (auto &c : resp["candidates"]) {
        std::string val = c.get<std::string>();
        if (seen.insert(val).second) {
            ordered.push_back(val);
        }
    }
    for (auto &val : seg.candidates) {
        if (seen.insert(val).second) {
            ordered.push_back(val);
        }
    }
    seg.candidates = ordered;
    auto at = std::find(seg.candidates.begin(), seg.candidates.end(), selected);
    seg.selectedIndex = at == seg.candidates.end() ? 0 : (int)(at - seg.candidates.begin());
}

// A candidate for the candidate list UI
class LlmCandidate : public CandidateWord {
public:
    LlmCandidate(Text text, int segIndex, int candIndex)
        : CandidateWord(std::move(text)), segIndex_(segIndex),
          candIndex_(candIndex) {}

    void select(InputContext * /*ic*/) const override {}

    int segIndex() const { return segIndex_; }
    int candIndex() const { return candIndex_; }

private:
    int segIndex_;
    int candIndex_;
};

class LlmImeState : public InputContextProperty {
public:
    // Romaji input buffer (user types ASCII, we convert to hiragana)
    fcitx::InputBuffer buffer_{
        fcitx::InputBufferOption::AsciiOnly};
    // Segments after conversion
    std::vector<Segment> segments_;
    // Currently active segment index
    int activeSegment_ = 0;
    // Whether we are in conversion mode (showing segments)
    bool converting_ = false;
    // Counts the conversions, so that an answer fetched in the background for an earlier one is dropped
    unsigned conversionId_ = 0;
    // Whether current conversion was initiated by LLM (Ctrl+Enter)
    bool llmMode_ = false;
    // Full hiragana string (for re-segmentation)
    std::string fullHiragana_;
    // Text before the cursor when the conversion started (sent with every request of this conversion)
    std::string textBefore_;
    // What the server offered when the conversion started: each bunsetsu with its first candidate
    std::vector<Choice> offered_;
    // The text committed in this input context (the last characters). It stands for the text before the cursor
    // when the application does not tell it, and Ctrl+Shift+Insert takes the last commit back from its end.
    std::string committed_;
    // The last commit of a conversion, with the conversion as it was just before the commit, so that
    // Ctrl+Shift+Insert can show it again. It is forgotten at the next key, at a reset and at the next commit.
    struct LastCommit {
        bool valid = false;
        std::string text;
        std::vector<Segment> segments;
        int activeSegment = 0;
        bool llmMode = false;
        std::string fullHiragana;
        std::string textBefore;
        std::vector<Choice> offered;
        std::chrono::steady_clock::time_point at;  // when it was committed
    };
    LastCommit lastCommit_;
};

class LlmImeEngine : public InputMethodEngineV2 {
public:
    LlmImeEngine(Instance *instance)
        : instance_(instance),
          factory_([this](InputContext &ic) {
              return new LlmImeState();
          }) {
        instance->inputContextManager().registerProperty("llmImeState",
                                                          &factory_);
        dispatcher_.attach(&instance->eventLoop());
        watchCursor(instance);
        reloadConfig();
    }

    // Waits for the work still running on other threads: it uses the engine.
    ~LlmImeEngine() override {
        std::unique_lock<std::mutex> lock(backgroundMutex_);
        backgroundDone_.wait(lock, [this] { return backgroundRunning_ == 0; });
    }

    // The configuration: the key binding profile and the keys of the Custom profile
    void reloadConfig() override {
        readAsIni(config_, kConfigFile);
        applyKeys();
    }
    const Configuration *getConfig() const override { return &config_; }
    void setConfig(const RawConfig &raw) override {
        config_.load(raw, true);
        safeSaveAsIni(config_, kConfigFile);
        applyKeys();
    }

    void keyEvent(const InputMethodEntry & /*entry*/, KeyEvent &event) override {
        auto *ic = event.inputContext();
        auto *state = ic->propertyFor(&factory_);

        // The take-back key (Ctrl+Shift+Insert) right after a commit takes the commit back; any other key forgets the
        // commit.
        // The key never goes to the application, even when there is nothing to take back, because the applications
        // bind keys like it to deletions (Emacs: Ctrl+BackSpace and Ctrl+Shift+BackSpace delete a word and a line).
        if (!event.isRelease()) {
            auto key = event.key();
            if (is(key, Action::TakeBack)) {
                if (state->lastCommit_.valid && !state->converting_ && state->buffer_.size() == 0 &&
                    continuationCandidates_.empty()) {
                    takeBackLastCommit(ic, state);
                }
                state->lastCommit_.valid = false;
                event.filterAndAccept();
                return;
            }
            if (!key.isModifier()) {
                state->lastCommit_.valid = false;
            }
        }

        // Handle continuation candidates FIRST (before anything else)
        if (!continuationCandidates_.empty() && !event.isRelease()) {
            auto key = event.key();
            if (key.check(Key(FcitxKey_Down))) {
                continuationIndex_ = (continuationIndex_ + 1) % (int)continuationCandidates_.size();
                showContinuationWithSelection(ic);
                event.filterAndAccept();
                return;
            }
            if (key.check(Key(FcitxKey_Up))) {
                continuationIndex_ = (continuationIndex_ <= 0)
                    ? (int)continuationCandidates_.size() - 1 : continuationIndex_ - 1;
                showContinuationWithSelection(ic);
                event.filterAndAccept();
                return;
            }
            if (key.check(Key(FcitxKey_Return)) || key.check(Key(FcitxKey_Tab))) {
                selectContinuation(ic, continuationIndex_);
                event.filterAndAccept();
                return;
            }
            if (key.check(Key(FcitxKey_Escape))) {
                continuationCandidates_.clear();
                continuationIndex_ = 0;
                auto &panel = ic->inputPanel();
                panel.reset();
                ic->updateUserInterface(UserInterfaceComponent::InputPanel);
                event.filterAndAccept();
                return;
            }
            // Any other key -> dismiss continuation and process normally
            continuationCandidates_.clear();
            continuationIndex_ = 0;
            auto &panel = ic->inputPanel();
            panel.reset();
            ic->updateUserInterface(UserInterfaceComponent::InputPanel);
        }

        if (state->converting_) {
            handleConvertingKey(ic, state, event);
        } else {
            handleInputKey(ic, state, event);
        }
    }

    void reset(const InputMethodEntry & /*entry*/,
               InputContextEvent &event) override {
        auto *ic = event.inputContext();
        auto *state = ic->propertyFor(&factory_);
        resetState(ic, state);
        state->lastCommit_.valid = false;
    }

private:
    void handleInputKey(InputContext *ic, LlmImeState *state,
                        KeyEvent &event) {
        if (event.isRelease()) return;
        auto key = event.key();


        // Ctrl+Tab with empty buffer -> request LLM continuation (async)
        if (key.check(Key(FcitxKey_Tab, KeyState::Ctrl)) && state->buffer_.size() == 0) {
            fetchContinuationAsync(ic);
            event.filterAndAccept();
            return;
        }

        // Tab with empty buffer -> accept first/current continuation candidate
        if (key.check(Key(FcitxKey_Tab)) && state->buffer_.size() == 0) {
            if (selectContinuation(ic, continuationIndex_)) {
                event.filterAndAccept();
                return;
            }
        }

        if (state->buffer_.size() == 0) {
            // Without a reading the operations below do nothing and their keys go to the application; a letter
            // starts the reading
            typeLetter(ic, state, key, event);
            return;
        }

        // The model's conversion of the whole reading
        if (is(key, Action::ModelConvert)) {
            startLlmConversion(ic, state);
            event.filterAndAccept();
            return;
        }

        // Start the conversion
        if (is(key, Action::Convert)) {
            startConversion(ic, state);
            event.filterAndAccept();
            return;
        }

        // The whole reading in another kind of characters (F6 to F10)
        if (auto kind = kindAction(key)) {
            startKindConversion(ic, state, *kind);
            event.filterAndAccept();
            return;
        }

        // Commit the reading as hiragana
        if (is(key, Action::Commit)) {
            std::string hiragana = romajiToHiragana(state->buffer_.userInput());
            ic->commitString(hiragana);
            rememberCommitted(ic, state, hiragana);
            resetState(ic, state);
            event.filterAndAccept();
            return;
        }

        // Forget the reading
        if (is(key, Action::Cancel)) {
            resetState(ic, state);
            event.filterAndAccept();
            return;
        }

        // Cursor keys edit the reading before the conversion: one kana left or right, the ends, and the kana after the cursor
        {
            const std::string input = state->buffer_.userInput();
            const size_t cursor = state->buffer_.cursor();
            bool handled = true;
            if (is(key, Action::CaretLeft)) {
                state->buffer_.setCursor(movedByOneKana(input, cursor, -1));
            } else if (is(key, Action::CaretRight)) {
                state->buffer_.setCursor(movedByOneKana(input, cursor, +1));
            } else if (is(key, Action::CaretFirst)) {
                state->buffer_.setCursor(0);
            } else if (is(key, Action::CaretLast)) {
                state->buffer_.setCursor(input.size());
            } else if (is(key, Action::Delete)) {
                if (cursor < input.size()) {
                    state->buffer_.erase(cursor, movedByOneKana(input, cursor, +1));
                }
            } else {
                handled = false;
            }
            if (handled) {
                updatePreedit(ic, state);
                event.filterAndAccept();
                return;
            }
        }

        // Delete the kana before the cursor
        if (is(key, Action::Backspace)) {
            state->buffer_.backspace();
            updatePreedit(ic, state);
            event.filterAndAccept();
            return;
        }

        typeLetter(ic, state, key, event);
    }

    // A printable ASCII key without modifiers goes into the reading
    void typeLetter(InputContext *ic, LlmImeState *state, const Key &key, KeyEvent &event) {
        if (key.isSimple() && !key.hasModifier()) {
            auto ch = Key::keySymToUnicode(key.sym());
            if (ch > 0 && ch < 128) {
                state->buffer_.type(ch);
                updatePreedit(ic, state);
                event.filterAndAccept();
            }
        }
    }

    void handleConvertingKey(InputContext *ic, LlmImeState *state,
                             KeyEvent &event) {
        if (event.isRelease()) return;
        auto key = event.key();

        // The model's conversion of the whole reading
        if (is(key, Action::ModelConvert)) {
            startLlmConversion(ic, state);
            event.filterAndAccept();
            return;
        }

        // Commit the conversion
        if (is(key, Action::Commit)) {
            commitConversion(ic, state);
            event.filterAndAccept();
            return;
        }

        // The active bunsetsu in another kind of characters (F6 to F10)
        if (auto kind = kindAction(key)) {
            applyKind(state, state->activeSegment_, *kind);
            updateConversionDisplay(ic, state);
            event.filterAndAccept();
            return;
        }

        // Next candidate, or after the last one of the model's conversion the conversion in bunsetsu
        if (is(key, Action::NextCandidate)) {
            auto &seg = state->segments_[state->activeSegment_];
            if (state->llmMode_) {
                // In LLM mode: cycle through LLM candidates, then switch to Mozc
                if (seg.selectedIndex < (int)seg.candidates.size() - 1) {
                    seg.selectedIndex++;
                    updateConversionDisplay(ic, state);
                } else {
                    // LLM candidates exhausted -> switch to Mozc conversion
                    startConversion(ic, state);
                }
            } else {
                // In Mozc mode: normal candidate cycling
                if (!seg.llmFetched) {
                    fetchLlmCandidates(state);
                }
                if (seg.selectedIndex < (int)seg.candidates.size() - 1) {
                    seg.selectedIndex++;
                } else {
                    seg.selectedIndex = 0;
                }
                updateConversionDisplay(ic, state);
            }
            event.filterAndAccept();
            return;
        }

        // Back to the reading
        if (is(key, Action::BackToReading)) {
            state->converting_ = false;
            updatePreedit(ic, state);
            event.filterAndAccept();
            return;
        }

        // Make the active bunsetsu longer (the next one shorter)
        if (is(key, Action::ExpandSegment)) {
            resizeSegment(ic, state, +1);
            event.filterAndAccept();
            return;
        }

        // Make the active bunsetsu shorter (the next one longer)
        if (is(key, Action::ShrinkSegment)) {
            resizeSegment(ic, state, -1);
            event.filterAndAccept();
            return;
        }

        // Move to another bunsetsu
        int last = (int)state->segments_.size() - 1;
        int target = is(key, Action::NextSegment)    ? std::min(state->activeSegment_ + 1, last)
                     : is(key, Action::PrevSegment)  ? std::max(state->activeSegment_ - 1, 0)
                     : is(key, Action::FirstSegment) ? 0
                     : is(key, Action::LastSegment)  ? last
                                                     : -1;
        if (target >= 0) {
            if (target != state->activeSegment_) {
                state->activeSegment_ = target;
                updateConversionDisplay(ic, state);
                prefetchCandidates(ic, state);
            }
            event.filterAndAccept();
            return;
        }

        // Previous candidate
        if (is(key, Action::PrevCandidate)) {
            auto &seg = state->segments_[state->activeSegment_];
            if (seg.selectedIndex > 0) {
                seg.selectedIndex--;
                updateConversionDisplay(ic, state);
            }
            event.filterAndAccept();
            return;
        }

        // Number keys 1-9 -> select candidate directly
        for (int n = 1; n <= 9; n++) {
            if (key.check(Key(static_cast<KeySym>(FcitxKey_1 + n - 1)))) {
                auto &seg = state->segments_[state->activeSegment_];
                if (n - 1 < (int)seg.candidates.size()) {
                    seg.selectedIndex = n - 1;
                    updateConversionDisplay(ic, state);
                }
                event.filterAndAccept();
                return;
            }
        }
    }

    // Resize the active segment by moving the boundary by 'delta' characters.
    // delta > 0: extend active segment (take chars from next segment)
    // delta < 0: shrink active segment (give chars to next segment)
    void resizeSegment(InputContext *ic, LlmImeState *state, int delta) {
        int idx = state->activeSegment_;
        if (idx >= (int)state->segments_.size()) return;

        auto &cur = state->segments_[idx];
        size_t curLen = utf8CharCount(cur.reading);

        if (delta > 0) {
            // Extend: take one char from next segment
            if (idx + 1 >= (int)state->segments_.size()) return;
            auto &next = state->segments_[idx + 1];
            size_t nextLen = utf8CharCount(next.reading);
            if (nextLen <= 1) {
                // Next segment would become empty; merge it entirely
                cur.reading += next.reading;
                state->segments_.erase(state->segments_.begin() + idx + 1);
            } else {
                // Move one char from next to current
                std::string movedChar = utf8Substr(next.reading, 0, 1);
                std::string remaining = utf8Substr(next.reading, 1, nextLen - 1);
                cur.reading += movedChar;
                next.reading = remaining;
            }
        } else {
            // Shrink: give one char to next segment
            if (curLen <= 1) return; // Can't shrink further
            std::string movedChar = utf8Substr(cur.reading, curLen - 1, 1);
            cur.reading = utf8Substr(cur.reading, 0, curLen - 1);

            if (idx + 1 < (int)state->segments_.size()) {
                // Prepend to next segment
                state->segments_[idx + 1].reading =
                    movedChar + state->segments_[idx + 1].reading;
            } else {
                // Create new segment after current
                Segment newSeg;
                newSeg.reading = movedChar;
                newSeg.candidates.push_back(movedChar);
                state->segments_.insert(
                    state->segments_.begin() + idx + 1, std::move(newSeg));
            }
        }

        // Re-convert the active segment with its new reading, then everything after it
        reconvertSegment(state, idx);
        reconvertRest(state, idx);
        updateConversionDisplay(ic, state);
    }

    // Fetch LLM candidates for the active segment (blocking, called on first Space)
    void fetchLlmCandidates(LlmImeState *state) {
        int idx = state->activeSegment_;
        if (idx < 0 || idx >= (int)state->segments_.size()) return;
        auto &seg = state->segments_[idx];
        seg.llmFetched = true;

        try {
            std::string context = state->textBefore_;
            for (int i = 0; i < idx; i++) {
                auto &s = state->segments_[i];
                context += s.candidates[s.selectedIndex];
            }

            json req = {{"input", seg.reading}, {"context", context}, {"n", 5}};
            json resp = conversion_.post("/api/segment-candidates", req);
            takeCandidates(seg, resp);
        } catch (const std::exception &) {
            // LLM fetch failed; keep existing Mozc candidates
        }
    }

    // Fetches the candidates of the active bunsetsu in the background, so that the first Space finds them in order.
    // The answer is taken on the main thread, and only if the conversion and the reading of the bunsetsu are the
    // same and its candidates were not fetched meanwhile.
    void prefetchCandidates(InputContext *ic, LlmImeState *state) {
        int idx = state->activeSegment_;
        if (!state->converting_ || idx < 0 || idx >= (int)state->segments_.size() ||
            state->segments_[idx].llmFetched) {
            return;
        }
        std::string context = state->textBefore_;
        for (int i = 0; i < idx; i++) {
            auto &s = state->segments_[i];
            context += s.candidates[s.selectedIndex];
        }
        std::string reading = state->segments_[idx].reading;
        unsigned id = state->conversionId_;
        auto ref = ic->watch();
        runInBackground([this, ref, idx, reading, context, id]() {
            try {
                json resp = conversion_.post("/api/segment-candidates",
                                        {{"input", reading}, {"context", context}, {"n", 5}});
                dispatcher_.schedule([this, ref, idx, reading, id, resp = std::move(resp)]() {
                    auto *ic = ref.get();
                    if (!ic) {
                        return;
                    }
                    auto *state = ic->propertyFor(&factory_);
                    if (state->conversionId_ != id || idx >= (int)state->segments_.size()) {
                        return;
                    }
                    auto &seg = state->segments_[idx];
                    if (seg.llmFetched || seg.reading != reading) {
                        return;
                    }
                    takeCandidates(seg, resp);
                    seg.llmFetched = true;
                });
            } catch (const std::exception &) {
                // the first Space fetches them then
            }
        });
    }

    // Re-fetch candidates for a segment after its reading changed
    void reconvertSegment(LlmImeState *state, int segIdx) {
        if (segIdx < 0 || segIdx >= (int)state->segments_.size()) return;
        auto &seg = state->segments_[segIdx];

        try {
            // Build context from the text before the cursor and the preceding segments
            std::string context = state->textBefore_;
            for (int i = 0; i < segIdx; i++) {
                auto &s = state->segments_[i];
                context += s.candidates[s.selectedIndex];
            }

            json req = {{"input", seg.reading}, {"context", context}, {"n", 5}};
            json resp = conversion_.post("/api/segment-candidates", req);

            if (resp.contains("candidates")) {
                seg.candidates.clear();
                for (auto &c : resp["candidates"]) {
                    seg.candidates.push_back(c.get<std::string>());
                }
            }
            if (seg.candidates.empty()) {
                seg.candidates.push_back(seg.reading);
            }
            seg.selectedIndex = 0;
        } catch (const std::exception &) {
            // Keep reading as sole candidate on error
            seg.candidates.clear();
            seg.candidates.push_back(seg.reading);
            seg.selectedIndex = 0;
        }
    }

    // Convert again everything after segment 'idx', in one request. The text before the cursor is what the
    // conversion started with plus what was chosen up to and including segment 'idx'. After a border was moved
    // the segments behind it begin where the user put the border, often inside a word, and the model converts
    // them better as one stretch of reading, which it cuts into bunsetsu itself, than one segment at a time.
    // The segments made here are the server's offer, not the user's choice, so they are added to offered_.
    void reconvertRest(LlmImeState *state, int idx) {
        if (idx + 1 >= (int)state->segments_.size()) return;
        std::string rest;
        for (int i = idx + 1; i < (int)state->segments_.size(); i++) {
            rest += state->segments_[i].reading;
        }
        std::string context = state->textBefore_;
        for (int i = 0; i <= idx; i++) {
            auto &s = state->segments_[i];
            context += s.candidates[s.selectedIndex];
        }

        try {
            json req = {{"input", rest}, {"n", 5}};
            if (!context.empty()) {
                req["context"] = context;
            }
            json resp = conversion_.post("/api/segment-convert", req);
            if (!resp.contains("segments")) return;

            std::vector<Segment> fresh;
            std::string joined;
            for (auto &segJson : resp["segments"]) {
                Segment s;
                s.reading = segJson.value("reading", "");
                if (segJson.contains("candidates")) {
                    for (auto &c : segJson["candidates"]) {
                        s.candidates.push_back(c.get<std::string>());
                    }
                }
                if (s.reading.empty()) return;
                if (s.candidates.empty()) {
                    s.candidates.push_back(s.reading);
                }
                joined += s.reading;
                fresh.push_back(std::move(s));
            }
            // Use the answer only if its segments are exactly the reading that was asked for
            if (fresh.empty() || joined != rest) return;

            state->segments_.erase(state->segments_.begin() + idx + 1, state->segments_.end());
            for (auto &s : fresh) {
                state->offered_.push_back({s.reading, s.candidates.front()});
                state->segments_.push_back(std::move(s));
            }
        } catch (const std::exception &) {
            // Keep the segments as they are
        }
    }

    // Byte offset of the n-th UTF-8 character of s (s.size() if s has fewer characters)
    static size_t byteOffsetOfChar(const std::string &s, size_t n) {
        size_t i = 0;
        while (n > 0 && i < s.size()) {
            unsigned char c = s[i];
            i += c < 0x80 ? 1 : c < 0xE0 ? 2 : c < 0xF0 ? 3 : 4;
            n--;
        }
        return std::min(i, s.size());
    }

    // Get the text before the cursor from the input context (last 500 characters),
    // so that the conversion can use what the user has already written
    std::string getSurroundingContext(InputContext *ic) {
        if (ic->capabilityFlags().test(CapabilityFlag::SurroundingText)) {
            auto &st = ic->surroundingText();
            if (st.isValid()) {
                const std::string &text = st.text();
                // cursor() counts characters, not bytes
                std::string before = text.substr(0, byteOffsetOfChar(text, st.cursor()));
                size_t chars = utf8CharCount(before);
                if (chars > 500) {
                    before = before.substr(byteOffsetOfChar(before, chars - 500));
                }
                return before;
            }
        }
        return "";
    }

    // Number of characters of committed text kept for each input context
    static constexpr size_t kCommittedChars = 500;

    // Whether the application tells the text around the cursor
    static bool tellsSurroundingText(InputContext *ic) {
        return ic->capabilityFlags().test(CapabilityFlag::SurroundingText) && ic->surroundingText().isValid();
    }

    // The text before the cursor for a conversion: what the application tells, or, when it tells nothing,
    // the text committed in this input context
    std::string textBeforeCursor(InputContext *ic, LlmImeState *state) {
        if (isPrivate(ic)) {
            return "";
        }
        return tellsSurroundingText(ic) ? getSurroundingContext(ic) : state->committed_;
    }

    // A password field, or a field the application marks as sensitive: what is typed there is not learned, not kept
    // as the text before the cursor, and not read from the application.
    static bool isPrivate(InputContext *ic) {
        return ic->capabilityFlags().testAny(CapabilityFlag::PasswordOrSensitive);
    }

    static void rememberCommitted(InputContext *ic, LlmImeState *state, const std::string &text) {
        if (isPrivate(ic)) {
            return;
        }
        state->committed_ = appendCommitted(state->committed_, text, kCommittedChars);
    }

    // Take the last commit back (Ctrl+Shift+Insert right after it): delete the committed text in the application and
    // show the conversion again as it was just before the commit. False, and nothing is changed, when the text before
    // the cursor no longer ends with the commit.
    bool takeBackLastCommit(InputContext *ic, LlmImeState *state) {
        LlmImeState::LastCommit last = state->lastCommit_;
        state->lastCommit_.valid = false;
        const size_t chars = utf8CharCount(last.text);
        if (tellsSurroundingText(ic)) {
            if (!endsWithText(getSurroundingContext(ic), last.text)) {
                return false;
            }
            ic->deleteSurroundingText(-static_cast<int>(chars), static_cast<unsigned int>(chars));
        } else {
            // The application tells nothing: one BackSpace for each character
            for (size_t i = 0; i < chars; i++) {
                ic->forwardKey(Key(FcitxKey_BackSpace), false);
                ic->forwardKey(Key(FcitxKey_BackSpace), true);
            }
        }
        state->committed_ = withoutEnd(state->committed_, last.text);
        state->segments_ = std::move(last.segments);
        state->activeSegment_ = last.activeSegment;
        state->llmMode_ = last.llmMode;
        state->fullHiragana_ = std::move(last.fullHiragana);
        state->textBefore_ = std::move(last.textBefore);
        state->offered_ = std::move(last.offered);
        state->converting_ = true;
        updateConversionDisplay(ic, state);
        return true;
    }

    void startConversion(InputContext *ic, LlmImeState *state) {
        std::string hiragana = romajiToHiragana(state->buffer_.userInput());
        state->fullHiragana_ = hiragana;

        try {
            std::string context = textBeforeCursor(ic, state);
            state->textBefore_ = context;
            json req = {{"input", hiragana}, {"n", 5}};
            if (!context.empty()) {
                req["context"] = context;
            }
            json resp = conversion_.post("/api/segment-convert", req);

            state->segments_.clear();
            state->activeSegment_ = 0;
            state->converting_ = true;
            state->conversionId_++;
            state->llmMode_ = false;

            if (resp.contains("segments")) {
                for (auto &segJson : resp["segments"]) {
                    Segment s;
                    s.reading = segJson.value("reading", "");
                    if (segJson.contains("candidates")) {
                        for (auto &c : segJson["candidates"]) {
                            s.candidates.push_back(c.get<std::string>());
                        }
                    }
                    if (s.candidates.empty()) {
                        s.candidates.push_back(s.reading);
                    }
                    state->segments_.push_back(std::move(s));
                }
            }

            // Fallback if no segments returned
            if (state->segments_.empty()) {
                Segment s;
                s.reading = hiragana;
                s.candidates.push_back(hiragana);
                state->segments_.push_back(std::move(s));
            }

            rememberOffered(state);
            updateConversionDisplay(ic, state);
            prefetchCandidates(ic, state);
        } catch (const std::exception &e) {
            // On error, just commit the hiragana
            ic->commitString(hiragana);
            rememberCommitted(ic, state, hiragana);
            resetState(ic, state);
        }
    }

    // LLM full-sentence conversion: send entire hiragana to /api/convert
    // and show result as a single segment with the LLM result as first candidate
    void startLlmConversion(InputContext *ic, LlmImeState *state) {
        std::string hiragana;
        if (state->converting_) {
            // Already in conversion mode: use stored hiragana
            hiragana = state->fullHiragana_;
        } else {
            hiragana = romajiToHiragana(state->buffer_.userInput());
        }
        state->fullHiragana_ = hiragana;

        try {
            std::string context = textBeforeCursor(ic, state);
            state->textBefore_ = context;
            json req = {{"input", hiragana}};
            if (!context.empty()) {
                req["context"] = context;
            }
            json resp = conversion_.post("/api/convert", req);

            state->segments_.clear();
            state->activeSegment_ = 0;
            state->converting_ = true;
            state->llmMode_ = true;

            Segment s;
            s.reading = hiragana;

            // Read candidates array from response (multiple LLM results)
            if (resp.contains("candidates") && resp["candidates"].is_array()) {
                for (auto &c : resp["candidates"]) {
                    std::string val = c.get<std::string>();
                    if (!val.empty() && val != hiragana) {
                        s.candidates.push_back(val);
                    }
                }
            }
            // Fallback: use "output" field if candidates is empty
            if (s.candidates.empty() && resp.contains("output")) {
                std::string llmResult = resp["output"].get<std::string>();
                if (!llmResult.empty() && llmResult != hiragana) {
                    s.candidates.push_back(llmResult);
                }
            }
            // Raw hiragana as last fallback
            s.candidates.push_back(hiragana);
            state->segments_.push_back(std::move(s));

            rememberOffered(state);
            updateConversionDisplay(ic, state);
        } catch (const std::exception &) {
            // On error, fall back to normal Mozc conversion
            if (!state->converting_) {
                startConversion(ic, state);
            }
        }
    }

    // Remember what the server offered, to tell at commit time which bunsetsu the user changed.
    void rememberOffered(LlmImeState *state) {
        state->offered_.clear();
        for (auto &seg : state->segments_) {
            state->offered_.push_back({seg.reading, seg.candidates.front()});
        }
    }

    void commitConversion(InputContext *ic, LlmImeState *state) {
        std::string hiragana = state->fullHiragana_;
        std::string result;
        for (auto &seg : state->segments_) {
            result += seg.candidates[seg.selectedIndex];
        }
        state->lastCommit_ = {true, result, state->segments_, state->activeSegment_, state->llmMode_,
                              state->fullHiragana_, state->textBefore_, state->offered_,
                              std::chrono::steady_clock::now()};
        ic->commitString(result);

        // Record what the user changed asynchronously (fire and forget). "corrections" lists the segments the user
        // changed: a candidate other than the first was taken, or a border between segments was moved (the first
        // candidate of the new reading is then taken as it is). The reading itself may be what the user took
        // (the result equals the hiragana): that is a change as well, so the result is not compared with the reading.
        try {
            std::vector<Choice> committed;
            for (auto &seg : state->segments_) {
                const std::string &text = seg.candidates[seg.selectedIndex];
                // Letters put in with F9 or F10 are not learned: the next time the reading should be converted
                if (!onlyLetters(text)) {
                    committed.push_back({seg.reading, text});
                }
            }
            json corrections = json::array();
            for (auto &changed : changedChoices(state->offered_, committed)) {
                corrections.push_back({{"reading", changed.reading}, {"chosen", changed.text}});
            }
            if (!corrections.empty() && !isPrivate(ic)) {
                json req = {{"input", hiragana}, {"output", result}, {"corrections", corrections}};
                conversion_.post("/api/record", req);
            }
        } catch (...) {
            // Ignore record failures
        }

        rememberCommitted(ic, state, result);

        resetState(ic, state);
    }

    // Select a continuation candidate and commit it
    bool selectContinuation(InputContext *ic, int index) {
        if (index < 0 || index >= (int)continuationCandidates_.size()) return false;

        std::string text = continuationCandidates_[index];
        ic->commitString(text);
        rememberCommitted(ic, ic->propertyFor(&factory_), text);

        // Clear continuation state
        continuationCandidates_.clear();
        continuationIndex_ = 0;

        auto &panel = ic->inputPanel();
        panel.reset();
        ic->updateUserInterface(UserInterfaceComponent::InputPanel);
        return true;
    }

    // Request LLM continuation asynchronously
    void fetchContinuationAsync(InputContext *ic) {
        std::string context = ic->propertyFor(&factory_)->committed_;
        if (context.empty()) return;

        // Show loading indicator
        auto &panel = ic->inputPanel();
        Text aux;
        aux.append("[LLM thinking...]", TextFormatFlag::DontCommit);
        panel.setAuxDown(aux);
        ic->updateUserInterface(UserInterfaceComponent::InputPanel);

        // Run in background thread; the input context may be gone when the answer comes
        auto ref = ic->watch();
        runInBackground([this, ref, context]() {
            try {
                json req = {{"context", context}, {"n", 5}};
                json resp = conversion_.post("/api/continue", req);

                // Schedule UI update on main thread
                dispatcher_.schedule([this, ref, resp = std::move(resp)]() {
                    if (auto *ic = ref.get()) {
                        showContinuationCandidates(ic, resp);
                    }
                });
            } catch (const std::exception &) {
                dispatcher_.schedule([ref]() {
                    if (auto *ic = ref.get()) {
                        ic->inputPanel().reset();
                        ic->updateUserInterface(UserInterfaceComponent::InputPanel);
                    }
                });
            }
        });
    }

    // Store continuation candidates and show in UI (called on main thread)
    void showContinuationCandidates(InputContext *ic, const json &resp) {
        continuationCandidates_.clear();
        continuationIndex_ = 0;

        if (resp.is_array()) {
            for (int i = 0; i < (int)resp.size(); i++) {
                if (resp[i].contains("text")) {
                    continuationCandidates_.push_back(
                        resp[i]["text"].get<std::string>());
                }
            }
        }

        if (continuationCandidates_.empty()) {
            auto &panel = ic->inputPanel();
            panel.reset();
            ic->updateUserInterface(UserInterfaceComponent::InputPanel);
            return;
        }

        showContinuationWithSelection(ic);
    }

    // Rebuild candidate list with current selection highlighted
    void showContinuationWithSelection(InputContext *ic) {
        auto &panel = ic->inputPanel();
        panel.reset();

        auto candidateList = std::make_unique<CommonCandidateList>();
        candidateList->setPageSize(5);
        candidateList->setLayoutHint(CandidateLayoutHint::Vertical);
        for (int i = 0; i < (int)continuationCandidates_.size(); i++) {
            std::string label = (i == continuationIndex_) ? "▶ " : "  ";
            candidateList->append<LlmCandidate>(
                Text(label + continuationCandidates_[i]), 0, i);
        }
        if (candidateList->totalSize() > 0) {
            panel.setCandidateList(std::move(candidateList));
        }

        // Also show selected in preedit for clarity
        Text preedit;
        preedit.append(continuationCandidates_[continuationIndex_],
                       TextFormatFlag::HighLight);
        panel.setAuxDown(preedit);

        ic->updateUserInterface(UserInterfaceComponent::InputPanel);
    }

    // (sync fetchContinuation removed — all continuation is now async via Ctrl+Tab)

    void updatePreedit(InputContext *ic, LlmImeState *state) {
        auto &panel = ic->inputPanel();
        panel.reset();

        if (state->buffer_.size() > 0) {
            std::string hiragana = romajiToHiragana(state->buffer_.userInput());
            Text preedit;
            preedit.append(hiragana,
                           TextFormatFlag::Underline);
            preedit.setCursor(shownCursorBytes(state->buffer_.userInput(), state->buffer_.cursor()));
            if (ic->capabilityFlags().test(CapabilityFlag::Preedit)) {
                panel.setClientPreedit(preedit);
            } else {
                panel.setPreedit(preedit);
            }

            // Fetch predictions (none at present)
            if (utf8CharCount(hiragana) >= 5) {
                fetchPredictions(ic, state, hiragana);
            }
        }
        ic->updatePreedit();
        ic->updateUserInterface(UserInterfaceComponent::InputPanel);
    }

    // Percent-encode a UTF-8 string for use in URL query parameters
    static std::string urlEncode(const std::string &s) {
        std::string result;
        for (unsigned char c : s) {
            if (isalnum(c) || c == '-' || c == '_' || c == '.' || c == '~') {
                result += c;
            } else {
                char hex[4];
                snprintf(hex, sizeof(hex), "%%%02X", c);
                result += hex;
            }
        }
        return result;
    }

    // Prefix-matched predictions, shown as candidates
    void fetchPredictions(InputContext *ic, LlmImeState * /*state*/,
                          const std::string &hiragana) {
        try {
            std::string query = "/api/predict?prefix=" + urlEncode(hiragana) +
                                "&limit=5";

            json resp = conversion_.get(query);
            if (!resp.is_array() || resp.empty()) return;

            auto &panel = ic->inputPanel();
            auto candidateList = std::make_unique<CommonCandidateList>();
            candidateList->setPageSize(5);
            candidateList->setLayoutHint(CandidateLayoutHint::Vertical);
            for (int i = 0; i < (int)resp.size(); i++) {
                if (resp[i].contains("candidate")) {
                    candidateList->append<LlmCandidate>(
                        Text(resp[i]["candidate"].get<std::string>()),
                        0, i);
                }
            }
            if (candidateList->totalSize() > 0) {
                panel.setCandidateList(std::move(candidateList));
            }
        } catch (const std::exception &) {
            // No predictions
        }
    }

    void updateConversionDisplay(InputContext *ic, LlmImeState *state) {
        auto &panel = ic->inputPanel();
        panel.reset();

        // Build preedit showing current selected conversion for each segment
        Text preedit;
        int activeStart = 0;   // byte offset of the active segment in the shown text: the cursor goes there
        int shownBytes = 0;
        for (int i = 0; i < (int)state->segments_.size(); i++) {
            auto &seg = state->segments_[i];
            std::string display = seg.candidates[seg.selectedIndex];
            if (i == state->activeSegment_) {
                activeStart = shownBytes;
                preedit.append(display,
                               TextFormatFlag::HighLight);
            } else {
                preedit.append(display,
                               TextFormatFlag::Underline);
            }
            shownBytes += (int)display.size();
        }
        preedit.setCursor(activeStart);
        // Set both client preedit and aux text for maximum compatibility
        panel.setClientPreedit(preedit);
        panel.setPreedit(preedit);

        // Show candidates for active segment
        auto &activeSeg = state->segments_[state->activeSegment_];
        auto candidateList = std::make_unique<CommonCandidateList>();
        candidateList->setPageSize(9);
        candidateList->setLayoutHint(CandidateLayoutHint::Vertical);
        for (int i = 0; i < (int)activeSeg.candidates.size(); i++) {
            candidateList->append<LlmCandidate>(
                Text(activeSeg.candidates[i]),
                state->activeSegment_, i);
        }
        if (activeSeg.selectedIndex < (int)candidateList->totalSize()) {
            candidateList->setGlobalCursorIndex(activeSeg.selectedIndex);
        }
        panel.setCandidateList(std::move(candidateList));

        ic->updatePreedit();
        ic->updateUserInterface(UserInterfaceComponent::InputPanel);
    }

    void resetState(InputContext *ic, LlmImeState *state) {
        state->buffer_.clear();
        state->segments_.clear();
        state->activeSegment_ = 0;
        state->converting_ = false;
        state->llmMode_ = false;
        state->fullHiragana_.clear();
        state->offered_.clear();

        auto &panel = ic->inputPanel();
        panel.reset();
        ic->updatePreedit();
        ic->updateUserInterface(UserInterfaceComponent::InputPanel);
    }

    static constexpr const char *kConfigFile = "conf/llm-ime.conf";

    // The cursor moves a while after a commit, when the application shows the committed text. A later move is the
    // user's (a click of the mouse), and the take-back would then delete other text.
    static constexpr std::chrono::milliseconds kCommitShown{500};

    // The last commit is forgotten when the input context loses the focus, and, in an application that does not
    // tell the text around the cursor (so that the take-back cannot check what it deletes), when the cursor moves
    // after the commit was shown.
    void watchCursor(Instance *instance) {
        eventHandlers_.push_back(instance->watchEvent(
            EventType::InputContextFocusOut, EventWatcherPhase::Default, [this](Event &event) {
                auto *ic = static_cast<InputContextEvent &>(event).inputContext();
                ic->propertyFor(&factory_)->lastCommit_.valid = false;
            }));
        eventHandlers_.push_back(instance->watchEvent(
            EventType::InputContextCursorRectChanged, EventWatcherPhase::Default, [this](Event &event) {
                auto *ic = static_cast<InputContextEvent &>(event).inputContext();
                auto &last = ic->propertyFor(&factory_)->lastCommit_;
                if (last.valid && !tellsSurroundingText(ic) &&
                    std::chrono::steady_clock::now() - last.at > kCommitShown) {
                    last.valid = false;
                }
            }));
    }

    // Runs `work` on another thread. The engine counts such work and waits for it when it is destroyed.
    void runInBackground(std::function<void()> work) {
        {
            std::lock_guard<std::mutex> lock(backgroundMutex_);
            backgroundRunning_++;
        }
        std::thread([this, work = std::move(work)]() {
            work();
            std::lock_guard<std::mutex> lock(backgroundMutex_);
            if (--backgroundRunning_ == 0) {
                backgroundDone_.notify_all();
            }
        }).detach();
    }

    // The keys of every operation in the chosen profile
    void applyKeys() {
        keys_.assign(actionInfos().size(), KeyList());
        for (size_t i = 0; i < actionInfos().size(); i++) {
            const auto &info = actionInfos()[i];
            keys_[static_cast<size_t>(info.action)] = *config_.profile == KeyProfile::Custom
                                                          ? config_.customKeys->keys(i)
                                                          : keyList(profileKeys(info, *config_.profile));
        }
    }

    bool is(const Key &key, Action action) const {
        size_t i = static_cast<size_t>(action);
        return i < keys_.size() && key.checkKeyList(keys_[i]);
    }

    // Which of F6 to F10 (in the chosen profile) the key is
    std::optional<Action> kindAction(const Key &key) const {
        for (Action a : {Action::ToHiragana, Action::ToKatakana, Action::ToHalfKatakana, Action::ToWideLatin,
                         Action::ToLatin}) {
            if (is(key, a)) {
                return a;
            }
        }
        return std::nullopt;
    }

    // Text of ASCII or full-width ASCII only
    static bool onlyLetters(const std::string &text) {
        for (char32_t c : llmime::conversion::toU32(text)) {
            if (!((c > 0x20 && c < 0x7F) || (c >= 0xFF01 && c <= 0xFF5E) || c == U' ' || c == 0x3000)) {
                return false;
            }
        }
        return !text.empty();
    }

    // The whole reading as one bunsetsu in another kind of characters, without asking the model: the conversion
    // starts with that one candidate, and Space then gives the other candidates of the reading.
    void startKindConversion(InputContext *ic, LlmImeState *state, Action kind) {
        std::string hiragana = romajiToHiragana(state->buffer_.userInput());
        state->fullHiragana_ = hiragana;
        state->textBefore_ = textBeforeCursor(ic, state);
        state->segments_.clear();
        Segment s;
        s.reading = hiragana;
        s.candidates.push_back(hiragana);
        state->segments_.push_back(std::move(s));
        state->activeSegment_ = 0;
        state->converting_ = true;
        state->conversionId_++;
        state->llmMode_ = false;
        rememberOffered(state);
        applyKind(state, 0, kind);
        updateConversionDisplay(ic, state);
        prefetchCandidates(ic, state);
    }

    // Puts the reading of bunsetsu `idx` in the kind of characters of `kind` among its candidates and selects it.
    // Pressing F9 or F10 again changes the letters: lower case, upper case, the first letter in upper case.
    void applyKind(LlmImeState *state, int idx, Action kind) {
        if (idx < 0 || idx >= (int)state->segments_.size()) return;
        auto &seg = state->segments_[idx];
        const std::string shown = seg.candidates[seg.selectedIndex];
        std::string text;
        if (kind == Action::ToHiragana) {
            text = seg.reading;
        } else if (kind == Action::ToKatakana) {
            text = hiraganaToKatakana(seg.reading);
        } else if (kind == Action::ToHalfKatakana) {
            text = toHalfKatakana(seg.reading);
        } else {
            // The letters as typed when the bunsetsu is the whole reading, else the kana written back in letters
            std::string letters = state->segments_.size() == 1 && seg.reading == state->fullHiragana_ &&
                                          state->buffer_.size() > 0
                                      ? state->buffer_.userInput()
                                      : kanaToRomaji(seg.reading);
            std::string lower = letters;
            for (char &c : lower) {
                if (c >= 'A' && c <= 'Z') c += 32;
            }
            if (kind == Action::ToLatin) {
                text = nextLatinForm(lower, shown);
            } else {
                // Which form of the letters is shown now, in full width
                std::string shownLetters;
                std::string form = lower;
                do {
                    if (toWideLatin(form) == shown) {
                        shownLetters = form;
                        break;
                    }
                    form = nextLatinForm(lower, form);
                } while (form != lower);
                text = toWideLatin(nextLatinForm(lower, shownLetters));
            }
        }
        auto at = std::find(seg.candidates.begin(), seg.candidates.end(), text);
        if (at == seg.candidates.end()) {
            seg.candidates.push_back(text);
            at = seg.candidates.end() - 1;
        }
        seg.selectedIndex = (int)(at - seg.candidates.begin());
    }

    Instance *instance_;
    SlmImeConfig config_;
    std::vector<KeyList> keys_;
    llm_ime::LocalConversion conversion_;
    FactoryFor<LlmImeState> factory_;
    EventDispatcher dispatcher_;
    std::vector<std::unique_ptr<HandlerTableEntry<EventHandler>>> eventHandlers_;
    std::mutex backgroundMutex_;
    std::condition_variable backgroundDone_;
    int backgroundRunning_ = 0;  // work running on other threads (runInBackground)
    std::vector<std::string> continuationCandidates_;  // current continuation candidates
    int continuationIndex_ = 0;    // currently highlighted continuation candidate
};

class LlmImeEngineFactory : public AddonFactory {
public:
    AddonInstance *create(AddonManager *manager) override {
        return new LlmImeEngine(manager->instance());
    }
};

} // namespace

FCITX_ADDON_FACTORY(LlmImeEngineFactory)
