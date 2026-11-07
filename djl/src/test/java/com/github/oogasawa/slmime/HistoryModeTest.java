package com.github.oogasawa.slmime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.scivicslab.pojoactor.core.ActorRef;
import org.junit.jupiter.api.Test;

/** {@code -Dhistory.mode}: the history is used and added to, or ({@code off}) neither. */
class HistoryModeTest {

    private static final String MOU = "{\"corrections\":[{\"reading\":\"もう\",\"chosen\":\"望\",\"turnedDown\":\"もう\"}]}";

    @Test
    void theValuesOfTheOptionNameTheModes() {
        assertEquals(HistoryMode.OFF, HistoryMode.of("off"));
        assertEquals(HistoryMode.SUBSTITUTE, HistoryMode.of("substitute"));
        assertEquals(HistoryMode.SUBSTITUTE, HistoryMode.of("anything else"), "this version has always used the history");
        assertTrue(HistoryMode.SUBSTITUTE.used());
        assertFalse(HistoryMode.OFF.used());
    }

    private static ImeApi api(UserHistory history, HistoryMode mode) {
        return new ImeApi((kana, context) -> kana, CandidateSource.NONE, new ActorRef<>("history", history),
                ImeApi.Scorer.NONE, 0, mode);
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
        ImeApi api = api(history, HistoryMode.SUBSTITUTE);
        assertEquals("もう", firstCandidate(api, "もう"));
        api.record(MOU);
        api.awaitLearning();
        assertEquals("望", history.preferred("もう").orElse(""));
        assertEquals("望", firstCandidate(api, "もう"));
    }

    @Test
    void offLearnsNothingFromWhatTheEngineReports() throws Exception {
        UserHistory history = UserHistory.inMemory();
        ImeApi api = api(history, HistoryMode.OFF);
        assertEquals("{}", api.record(MOU));
        api.awaitLearning();
        assertTrue(history.preferred("もう").isEmpty(), "nothing is kept");
        assertEquals("もう", firstCandidate(api, "もう"));
    }

    @Test
    void offDoesNotPutAnEarlierChoiceFirst() throws Exception {
        UserHistory history = UserHistory.inMemory();
        history.learn("もう", "望");
        assertEquals("望", firstCandidate(api(history, HistoryMode.SUBSTITUTE), "もう"),
                "the control: the earlier choice comes first when the history is used");
        assertEquals("もう", firstCandidate(api(history, HistoryMode.OFF), "もう"));
    }

    /** The history put into the answer of the model, where its reading is a part of what was typed. */
    @Test
    void offDoesNotPutAnEarlierChoiceIntoTheAnswer() throws Exception {
        UserHistory history = UserHistory.inMemory();
        history.learn("かんじ", "感じ");
        ImeApi.Converter model = (kana, context) -> "漢字|を|書く";
        ImeApi used = new ImeApi(model, CandidateSource.NONE, new ActorRef<>("history", history), ImeApi.Scorer.NONE, 0,
                HistoryMode.SUBSTITUTE);
        ImeApi off = new ImeApi(model, CandidateSource.NONE, new ActorRef<>("history", history), ImeApi.Scorer.NONE, 0,
                HistoryMode.OFF);
        String request = "{\"input\":\"かんじをかく\"}";
        assertEquals("感じを書く", JsonParser.parseString(used.convert(request)).getAsJsonObject().get("output").getAsString(),
                "the control: the earlier choice is put into the answer");
        assertEquals("漢字を書く", JsonParser.parseString(off.convert(request)).getAsJsonObject().get("output").getAsString());
    }
}
