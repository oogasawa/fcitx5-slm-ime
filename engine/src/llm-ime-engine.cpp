#include "http-client.h"
#include "corrections.h"
#include "romaji.h"
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
#include <memory>
#include <set>
#include <string>
#include <thread>
#include <vector>

namespace {

using namespace fcitx;
using namespace llmime;
using json = nlohmann::json;

// Segment from the daemon's /api/segment-convert response
struct Segment {
    std::string reading;
    std::vector<std::string> candidates;
    int selectedIndex = 0;
    bool llmFetched = false;
};

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
    // Whether current conversion was initiated by LLM (Ctrl+Enter)
    bool llmMode_ = false;
    // Full hiragana string (for re-segmentation)
    std::string fullHiragana_;
    // Text before the cursor when the conversion started (sent with every request of this conversion)
    std::string textBefore_;
    // What the server offered when the conversion started: each bunsetsu with its first candidate
    std::vector<Choice> offered_;
};

class LlmImeEngine : public InputMethodEngineV2 {
public:
    LlmImeEngine(Instance *instance)
        : instance_(instance),
          httpClient_("http://localhost:8190"),
          predictClient_("http://localhost:8190"),
          factory_([this](InputContext &ic) {
              return new LlmImeState();
          }) {
        instance->inputContextManager().registerProperty("llmImeState",
                                                          &factory_);
        dispatcher_.attach(&instance->eventLoop());
    }

    void keyEvent(const InputMethodEntry & /*entry*/, KeyEvent &event) override {
        auto *ic = event.inputContext();
        auto *state = ic->propertyFor(&factory_);

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

        // Ctrl+Enter with input -> LLM full-sentence conversion
        if (key.check(Key(FcitxKey_Return, KeyState::Ctrl)) && state->buffer_.size() > 0) {
            startLlmConversion(ic, state);
            event.filterAndAccept();
            return;
        }

        // Space with input -> start conversion (Mozc)
        if (key.check(Key(FcitxKey_space)) && state->buffer_.size() > 0) {
            startConversion(ic, state);
            event.filterAndAccept();
            return;
        }

        // Enter -> commit raw hiragana
        if (key.check(Key(FcitxKey_Return)) && state->buffer_.size() > 0) {
            std::string hiragana = romajiToHiragana(state->buffer_.userInput());
            ic->commitString(hiragana);
            resetState(ic, state);
            event.filterAndAccept();
            return;
        }

        // Escape -> cancel input
        if (key.check(Key(FcitxKey_Escape)) && state->buffer_.size() > 0) {
            resetState(ic, state);
            event.filterAndAccept();
            return;
        }

        // Cursor keys edit the reading before the conversion: one kana left or right, the ends, and the kana after the cursor
        if (state->buffer_.size() > 0) {
            const std::string input = state->buffer_.userInput();
            const size_t cursor = state->buffer_.cursor();
            bool handled = true;
            if (key.check(Key(FcitxKey_Left))) {
                state->buffer_.setCursor(movedByOneKana(input, cursor, -1));
            } else if (key.check(Key(FcitxKey_Right))) {
                state->buffer_.setCursor(movedByOneKana(input, cursor, +1));
            } else if (key.check(Key(FcitxKey_Home))) {
                state->buffer_.setCursor(0);
            } else if (key.check(Key(FcitxKey_End))) {
                state->buffer_.setCursor(input.size());
            } else if (key.check(Key(FcitxKey_Delete))) {
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

        // Backspace
        if (key.check(Key(FcitxKey_BackSpace)) && state->buffer_.size() > 0) {
            state->buffer_.backspace();
            updatePreedit(ic, state);
            event.filterAndAccept();
            return;
        }

        // Printable ASCII -> add to buffer
        if (key.isSimple() && !key.hasModifier()) {
            auto ch = Key::keySymToUnicode(key.sym());
            if (ch > 0 && ch < 128) {
                state->buffer_.type(ch);
                updatePreedit(ic, state);
                event.filterAndAccept();
                return;
            }
        }
    }

    void handleConvertingKey(InputContext *ic, LlmImeState *state,
                             KeyEvent &event) {
        if (event.isRelease()) return;
        auto key = event.key();

        // Ctrl+Enter -> LLM full-sentence re-conversion (must be before Enter)
        if (key.check(Key(FcitxKey_Return, KeyState::Ctrl))) {
            startLlmConversion(ic, state);
            event.filterAndAccept();
            return;
        }

        // Enter -> commit conversion
        if (key.check(Key(FcitxKey_Return))) {
            commitConversion(ic, state);
            event.filterAndAccept();
            return;
        }

        // Space -> next candidate, or switch to Mozc if LLM candidates exhausted
        if (key.check(Key(FcitxKey_space))) {
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

        // Escape -> back to input mode
        if (key.check(Key(FcitxKey_Escape))) {
            state->converting_ = false;
            updatePreedit(ic, state);
            event.filterAndAccept();
            return;
        }

        // Shift+Right -> extend active segment (shrink next)
        if (key.check(Key(FcitxKey_Right, KeyState::Shift))) {
            resizeSegment(ic, state, +1);
            event.filterAndAccept();
            return;
        }

        // Shift+Left -> shrink active segment (extend next)
        if (key.check(Key(FcitxKey_Left, KeyState::Shift))) {
            resizeSegment(ic, state, -1);
            event.filterAndAccept();
            return;
        }

        // Right arrow -> next segment
        if (key.check(Key(FcitxKey_Right))) {
            if (state->activeSegment_ < (int)state->segments_.size() - 1) {
                state->activeSegment_++;
                updateConversionDisplay(ic, state);
            }
            event.filterAndAccept();
            return;
        }

        // Left arrow -> previous segment
        if (key.check(Key(FcitxKey_Left))) {
            if (state->activeSegment_ > 0) {
                state->activeSegment_--;
                updateConversionDisplay(ic, state);
            }
            event.filterAndAccept();
            return;
        }

        // Down arrow -> next candidate for current segment
        if (key.check(Key(FcitxKey_Down))) {
            auto &seg = state->segments_[state->activeSegment_];
            if (seg.selectedIndex < (int)seg.candidates.size() - 1) {
                seg.selectedIndex++;
            }
            updateConversionDisplay(ic, state);
            event.filterAndAccept();
            return;
        }

        // Up arrow -> previous candidate
        if (key.check(Key(FcitxKey_Up))) {
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
                // Re-convert the modified next segment
                reconvertSegment(state, idx + 1);
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
                reconvertSegment(state, idx + 1);
            } else {
                // Create new segment after current
                Segment newSeg;
                newSeg.reading = movedChar;
                newSeg.candidates.push_back(movedChar);
                state->segments_.insert(
                    state->segments_.begin() + idx + 1, std::move(newSeg));
                reconvertSegment(state, idx + 1);
            }
        }

        // Re-convert the active segment with its new reading
        reconvertSegment(state, idx);
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
            json resp = httpClient_.post("/api/segment-candidates", req);

            if (resp.contains("candidates")) {
                std::set<std::string> existing(seg.candidates.begin(),
                                                seg.candidates.end());
                for (auto &c : resp["candidates"]) {
                    std::string val = c.get<std::string>();
                    if (existing.find(val) == existing.end()) {
                        seg.candidates.push_back(val);
                        existing.insert(val);
                    }
                }
            }
        } catch (const std::exception &) {
            // LLM fetch failed; keep existing Mozc candidates
        }
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
            json resp = httpClient_.post("/api/segment-candidates", req);

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

    void startConversion(InputContext *ic, LlmImeState *state) {
        std::string hiragana = romajiToHiragana(state->buffer_.userInput());
        state->fullHiragana_ = hiragana;

        try {
            std::string context = getSurroundingContext(ic);
            state->textBefore_ = context;
            json req = {{"input", hiragana}, {"n", 5}};
            if (!context.empty()) {
                req["context"] = context;
            }
            json resp = httpClient_.post("/api/segment-convert", req);

            state->segments_.clear();
            state->activeSegment_ = 0;
            state->converting_ = true;
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
        } catch (const std::exception &e) {
            // On error, just commit the hiragana
            ic->commitString(hiragana);
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
            std::string context = getSurroundingContext(ic);
            state->textBefore_ = context;
            json req = {{"input", hiragana}};
            if (!context.empty()) {
                req["context"] = context;
            }
            json resp = httpClient_.post("/api/convert", req);

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
        ic->commitString(result);

        // Record what the user changed asynchronously (fire and forget). "corrections" lists the segments the user
        // changed: a candidate other than the first was taken, or a border between segments was moved (the first
        // candidate of the new reading is then taken as it is). The reading itself may be what the user took
        // (the result equals the hiragana): that is a change as well, so the result is not compared with the reading.
        try {
            std::vector<Choice> committed;
            for (auto &seg : state->segments_) {
                committed.push_back({seg.reading, seg.candidates[seg.selectedIndex]});
            }
            json corrections = json::array();
            for (auto &changed : changedChoices(state->offered_, committed)) {
                corrections.push_back({{"reading", changed.reading}, {"chosen", changed.text}});
            }
            if (!corrections.empty()) {
                json req = {{"input", hiragana}, {"output", result}, {"corrections", corrections}};
                httpClient_.post("/api/record", req);
            }
        } catch (...) {
            // Ignore record failures
        }

        // Track committed text for continuation
        committedContext_ += result;
        // Keep last 500 chars
        if (committedContext_.size() > 500) {
            committedContext_ = committedContext_.substr(
                committedContext_.size() - 500);
        }

        resetState(ic, state);
    }

    // Select a continuation candidate and commit it
    bool selectContinuation(InputContext *ic, int index) {
        if (index < 0 || index >= (int)continuationCandidates_.size()) return false;

        std::string text = continuationCandidates_[index];
        ic->commitString(text);

        // Update context
        committedContext_ += text;
        if (committedContext_.size() > 500) {
            committedContext_ = committedContext_.substr(
                committedContext_.size() - 500);
        }

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
        std::string context = committedContext_;
        if (context.empty()) return;

        // Show loading indicator
        auto &panel = ic->inputPanel();
        Text aux;
        aux.append("[LLM thinking...]", TextFormatFlag::DontCommit);
        panel.setAuxDown(aux);
        ic->updateUserInterface(UserInterfaceComponent::InputPanel);

        // Run in background thread
        std::thread([this, ic, context]() {
            try {
                json req = {{"context", context}, {"n", 5}};
                json resp = predictClient_.post("/api/continue", req);

                // Schedule UI update on main thread
                dispatcher_.schedule(
                    [this, ic, resp = std::move(resp)]() {
                        showContinuationCandidates(ic, resp);
                    });
            } catch (const std::exception &) {
                dispatcher_.schedule(
                    [ic]() {
                        auto &panel = ic->inputPanel();
                        panel.reset();
                        ic->updateUserInterface(
                            UserInterfaceComponent::InputPanel);
                    });
            }
        }).detach();
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

            // Fetch predictions from predict daemon (non-blocking best-effort)
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

    // Query predict daemon for prefix-matched candidates and show them
    void fetchPredictions(InputContext *ic, LlmImeState * /*state*/,
                          const std::string &hiragana) {
        try {
            std::string query = "/api/predict?prefix=" + urlEncode(hiragana) +
                                "&limit=5";

            json resp = predictClient_.get(query);
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
            // Predict daemon unavailable — silently ignore
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

    Instance *instance_;
    llm_ime::HttpClient httpClient_;
    llm_ime::HttpClient predictClient_;
    FactoryFor<LlmImeState> factory_;
    EventDispatcher dispatcher_;
    std::string committedContext_;  // rolling context for continuation
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
