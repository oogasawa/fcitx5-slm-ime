package com.github.oogasawa.slmime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.scivicslab.pojoactor.core.ActorRef;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** {@code -Dhistory.mode}: what each mode uses of the user's earlier choices, and that {@code off} keeps nothing. */
class HistoryModeTest {

    private static final String MOU = "{\"corrections\":[{\"reading\":\"もう\",\"chosen\":\"望\",\"turnedDown\":\"もう\"}]}";

    @Test
    void theValuesOfTheOptionNameTheModes() {
        assertEquals(HistoryMode.SUBSTITUTE, HistoryMode.of("substitute"));
        assertEquals(HistoryMode.HINTS, HistoryMode.of("hints"));
        assertEquals(HistoryMode.OFF, HistoryMode.of("off"));
        assertEquals(HistoryMode.BOTH, HistoryMode.of("both"));
        assertEquals(HistoryMode.BOTH, HistoryMode.of("anything else"), "an unknown word has always meant both");
    }

    @Test
    void whatEachModeUses() {
        assertTrue(!HistoryMode.SUBSTITUTE.hints() && HistoryMode.SUBSTITUTE.substitution() && HistoryMode.SUBSTITUTE.used());
        assertTrue(HistoryMode.HINTS.hints() && !HistoryMode.HINTS.substitution() && HistoryMode.HINTS.used());
        assertTrue(HistoryMode.BOTH.hints() && HistoryMode.BOTH.substitution() && HistoryMode.BOTH.used());
        assertFalse(HistoryMode.OFF.hints());
        assertFalse(HistoryMode.OFF.substitution());
        assertFalse(HistoryMode.OFF.used());
    }

    private static ImeApi api(UserHistory history, HistoryMode mode, List<List<DictionaryHints.Hint>> hintsSeen) {
        ImeApi.Converter model = new ImeApi.Converter() {
            @Override public String convert(String kana, String context) {
                return kana;
            }
            @Override public String convert(String kana, String context, List<DictionaryHints.Hint> choices) {
                hintsSeen.add(choices);
                return kana;
            }
        };
        return new ImeApi(model, CandidateSource.NONE, new ActorRef<>("history", history), ImeApi.Scorer.NONE, 0, mode);
    }

    private static String firstCandidate(ImeApi api, String reading) throws Exception {
        JsonObject response = JsonParser.parseString(
                api.segmentCandidates("{\"input\":\"" + reading + "\"}")).getAsJsonObject();
        return response.getAsJsonArray("candidates").get(0).getAsString();
    }

    /** The control: in the default mode one choice made by mistake takes the first place at once. */
    @Test
    void inTheDefaultModeAChoiceIsLearnedAndPutFirst() throws Exception {
        UserHistory history = UserHistory.inMemory();
        ImeApi api = api(history, HistoryMode.SUBSTITUTE, new ArrayList<>());
        assertEquals("もう", firstCandidate(api, "もう"));
        api.record(MOU);
        api.awaitLearning();
        assertEquals("望", history.preferred("もう").orElse(""));
        assertEquals("望", firstCandidate(api, "もう"));
    }

    @Test
    void offLearnsNothingFromWhatTheEngineReports() throws Exception {
        UserHistory history = UserHistory.inMemory();
        ImeApi api = api(history, HistoryMode.OFF, new ArrayList<>());
        assertEquals("{}", api.record(MOU));
        api.awaitLearning();
        assertTrue(history.preferred("もう").isEmpty(), "nothing is kept");
        assertEquals("もう", firstCandidate(api, "もう"));
    }

    @Test
    void offDoesNotPutAnEarlierChoiceFirstEither() throws Exception {
        UserHistory history = UserHistory.inMemory();
        history.learn("もう", "望");
        assertEquals("望", firstCandidate(api(history, HistoryMode.SUBSTITUTE, new ArrayList<>()), "もう"),
                "the control: the earlier choice comes first when the history is used");
        assertEquals("もう", firstCandidate(api(history, HistoryMode.OFF, new ArrayList<>()), "もう"));
    }

    @Test
    void offGivesTheModelNoHints() throws Exception {
        UserHistory history = UserHistory.inMemory();
        history.learn("かんじをかく", "漢字を書く");
        List<List<DictionaryHints.Hint>> seen = new ArrayList<>();
        api(history, HistoryMode.HINTS, seen).segmentConvert("{\"input\":\"かんじをかく\"}");
        assertEquals(1, seen.size());
        assertFalse(seen.get(0).isEmpty(), "the control: hints mode gives the earlier choice to the model");

        seen.clear();
        api(history, HistoryMode.OFF, seen).segmentConvert("{\"input\":\"かんじをかく\"}");
        assertEquals(1, seen.size());
        assertTrue(seen.get(0).isEmpty());
    }
}
