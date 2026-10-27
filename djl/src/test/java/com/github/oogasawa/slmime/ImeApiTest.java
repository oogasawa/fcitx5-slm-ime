package com.github.oogasawa.slmime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import org.junit.jupiter.api.Test;

class ImeApiTest {

    private final ImeApi api = new ImeApi((kana, context) ->
            kana.equals("きかいがくしゅうのあるごりずむ") ? "機械学習|の|アルゴリズム" : kana, CandidateSource.NONE);

    @Test
    void segmentConvertReturnsEachBunsetsuWithItsReading() {
        JsonObject response = parse(call(() -> api.segmentConvert(
                "{\"input\":\"きかいがくしゅうのあるごりずむ\",\"n\":5,\"context\":\"論文で使う\"}")));
        var segments = response.getAsJsonArray("segments");
        assertEquals(3, segments.size());
        assertEquals("きかいがくしゅう", segments.get(0).getAsJsonObject().get("reading").getAsString());
        assertEquals("機械学習",
                segments.get(0).getAsJsonObject().getAsJsonArray("candidates").get(0).getAsString());
        assertEquals("あるごりずむ", segments.get(2).getAsJsonObject().get("reading").getAsString());
    }

    @Test
    void convertJoinsTheBunsetsuWithoutTheSeparator() {
        JsonObject response = parse(call(() -> api.convert("{\"input\":\"きかいがくしゅうのあるごりずむ\"}")));
        assertEquals("機械学習のアルゴリズム", response.get("output").getAsString());
        assertEquals("機械学習のアルゴリズム", response.getAsJsonArray("candidates").get(0).getAsString());
    }

    @Test
    void anEmptyOutputFallsBackToTheTypedText() {
        ImeApi empty = new ImeApi((kana, context) -> "", CandidateSource.NONE);
        JsonObject response = parse(call(() -> empty.segmentConvert("{\"input\":\"あ\"}")));
        assertEquals("あ", response.getAsJsonArray("segments").get(0).getAsJsonObject().get("reading").getAsString());
    }

    @Test
    void theModelsAnswerComesFirstAndTheDictionaryCandidatesFollowWithoutDuplicates() {
        ImeApi withAnthy = new ImeApi((kana, context) -> "機械学習|の|アルゴリズム",
                (reading, limit) -> reading.equals("きかいがくしゅう")
                        ? List.of("機械学習", "器械学習", "機会学習", "きかいがくしゅう") : List.of());
        JsonObject response = parse(call(() -> withAnthy.segmentConvert(
                "{\"input\":\"きかいがくしゅうのあるごりずむ\",\"n\":5}")));
        var first = response.getAsJsonArray("segments").get(0).getAsJsonObject().getAsJsonArray("candidates");
        assertEquals(List.of("機械学習", "器械学習", "機会学習", "きかいがくしゅう"),
                first.asList().stream().map(e -> e.getAsString()).toList());
    }

    @Test
    void segmentCandidatesOfOneReadingAddTheDictionaryCandidates() {
        ImeApi withAnthy = new ImeApi((kana, context) -> "アルゴリズム",
                (reading, limit) -> List.of("アルゴリズム", "有る語里図無"));
        JsonObject response = parse(call(() -> withAnthy.segmentCandidates("{\"input\":\"あるごりずむ\"}")));
        assertEquals(List.of("アルゴリズム", "有る語里図無"),
                response.getAsJsonArray("candidates").asList().stream().map(e -> e.getAsString()).toList());
    }

    private static List<String> candidatesOf(JsonObject segment) {
        return segment.getAsJsonArray("candidates").asList().stream().map(e -> e.getAsString()).toList();
    }

    @Test
    void aChoiceForPartOfABunsetsuIsPutIntoTheModelsAnswer() {
        UserHistory history = UserHistory.inMemory();
        history.learn("でぷろい", "デプロイ");
        ImeApi withHistory = new ImeApi((kana, context) -> "でプロイされましたか", CandidateSource.NONE, history);
        var segments = parse(call(() -> withHistory.segmentConvert("{\"input\":\"でぷろいされましたか\"}")))
                .getAsJsonArray("segments");
        assertEquals(1, segments.size());
        assertEquals("でぷろいされましたか", segments.get(0).getAsJsonObject().get("reading").getAsString());
        assertEquals(List.of("デプロイされましたか", "でプロイされましたか"), candidatesOf(segments.get(0).getAsJsonObject()));
    }

    @Test
    void aChoiceThatRunsAcrossABunsetsuBoundaryJoinsTheBunsetsu() {
        UserHistory history = UserHistory.inMemory();
        history.learn("でぷろい", "デプロイ");
        ImeApi withHistory = new ImeApi((kana, context) -> "で|プロイされたかな", CandidateSource.NONE, history);
        var segments = parse(call(() -> withHistory.segmentConvert("{\"input\":\"でぷろいされたかな\"}")))
                .getAsJsonArray("segments");
        assertEquals(1, segments.size());
        assertEquals("でぷろいされたかな", segments.get(0).getAsJsonObject().get("reading").getAsString());
        assertEquals(List.of("デプロイされたかな", "でプロイされたかな"), candidatesOf(segments.get(0).getAsJsonObject()));
    }

    @Test
    void theBunsetsuNextToTheChoiceAreKept() {
        UserHistory history = UserHistory.inMemory();
        history.learn("でぷろい", "デプロイ");
        ImeApi withHistory = new ImeApi((kana, context) -> "新しいモデル|がでプロイ|された", CandidateSource.NONE, history);
        var segments = parse(call(() -> withHistory.segmentConvert("{\"input\":\"あたらしいもでるがでぷろいされた\"}")))
                .getAsJsonArray("segments");
        assertEquals(3, segments.size());
        assertEquals("新しいモデル", candidatesOf(segments.get(0).getAsJsonObject()).get(0));
        assertEquals("がデプロイ", candidatesOf(segments.get(1).getAsJsonObject()).get(0));
        assertEquals("がでぷろい", segments.get(1).getAsJsonObject().get("reading").getAsString());
        assertEquals("された", candidatesOf(segments.get(2).getAsJsonObject()).get(0));
    }

    @Test
    void aShortReadingIsNotLookedForInsideALongerOne() {
        UserHistory history = UserHistory.inMemory();
        history.learn("め", "目");
        ImeApi withHistory = new ImeApi((kana, context) -> "初めまして", CandidateSource.NONE, history);
        var segments = parse(call(() -> withHistory.segmentConvert("{\"input\":\"はじめまして\"}")))
                .getAsJsonArray("segments");
        assertEquals("初めまして", candidatesOf(segments.get(0).getAsJsonObject()).get(0));
    }

    @Test
    void aChoiceWhoseEndLiesInsideARunOfKanjiIsLeftOut() {
        UserHistory history = UserHistory.inMemory();
        history.learn("きかいが", "機会が");
        ImeApi withHistory = new ImeApi((kana, context) -> "機械学習", CandidateSource.NONE, history);
        var segments = parse(call(() -> withHistory.segmentConvert("{\"input\":\"きかいがくしゅう\"}")))
                .getAsJsonArray("segments");
        assertEquals("機械学習", candidatesOf(segments.get(0).getAsJsonObject()).get(0));
    }

    @Test
    void segmentCandidatesAndConvertAlsoPutTheChoiceIn() {
        UserHistory history = UserHistory.inMemory();
        history.learn("でぷろい", "デプロイ");
        ImeApi withHistory = new ImeApi((kana, context) -> "で|プロイされたかな", CandidateSource.NONE, history);
        JsonObject candidates = parse(call(() -> withHistory.segmentCandidates("{\"input\":\"でぷろいされたかな\"}")));
        assertEquals("デプロイされたかな", candidates.getAsJsonArray("candidates").get(0).getAsString());
        JsonObject converted = parse(call(() -> withHistory.convert("{\"input\":\"でぷろいされたかな\"}")));
        assertEquals("デプロイされたかな", converted.get("output").getAsString());
    }

    @Test
    void thePassiveRequestsGetEmptyAnswers() {
        assertEquals("{}", api.record("{}"));
        assertEquals("[]", api.predict());
        assertTrue(parse(api.continuation("{}")).getAsJsonArray("candidates").isEmpty());
    }

    private interface Call {
        String run() throws Exception;
    }

    private static String call(Call c) {
        try {
            return c.run();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static JsonObject parse(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }
}
