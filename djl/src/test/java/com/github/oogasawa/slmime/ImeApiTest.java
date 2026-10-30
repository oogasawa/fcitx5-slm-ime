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

    @Test
    void theDictionaryCandidatesOfOneBunsetsuAreOrderedByTheScoresOfTheModelAfterTheTextBeforeIt() {
        List<String> contexts = new java.util.ArrayList<>();
        ImeApi scored = new ImeApi((kana, context) -> "天気が",
                (reading, limit) -> List.of("天気が", "転記が", "転機が", "てんきが"),
                UserHistory.inMemory(),
                (reading, context, texts) -> {
                    contexts.add(context);
                    return texts.stream().mapToDouble(t -> t.equals("転機が") ? 0 : t.equals("てんきが") ? -1 : -5).toArray();
                });
        JsonObject response = parse(call(() -> scored.segmentCandidates(
                "{\"input\":\"てんきが\",\"context\":\"昨日は雨。今日は\"}")));
        // the model's own text stays first; the others follow their scores
        assertEquals(List.of("天気が", "転機が", "てんきが", "転記が"),
                response.getAsJsonArray("candidates").asList().stream().map(e -> e.getAsString()).toList());
        assertEquals(List.of("昨日は雨。今日は"), contexts);

        // the conversion of a whole text does not score: each bunsetsu would cost a run of the model
        contexts.clear();
        call(() -> scored.segmentConvert("{\"input\":\"てんきが\",\"context\":\"昨日は雨。\"}"));
        assertEquals(List.of(), contexts);
    }

    @Test
    void withoutAScorerTheDictionaryCandidatesKeepTheirOrder() {
        ImeApi unscored = new ImeApi((kana, context) -> "天気が",
                (reading, limit) -> List.of("天気が", "転記が", "転機が"));
        JsonObject response = parse(call(() -> unscored.segmentCandidates("{\"input\":\"てんきが\"}")));
        assertEquals(List.of("天気が", "転記が", "転機が"),
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

    private static String outputOf(ImeApi api, String reading) {
        return parse(call(() -> api.segmentCandidates("{\"input\":\"" + reading + "\"}"))).get("output").getAsString();
    }

    @Test
    void theCharactersAtTheEndOfAReadingThatTheModelLeftOutAreCopiedBack() {
        assertEquals("精度がよ", outputOf(new ImeApi((k, c) -> "精度が", CandidateSource.NONE), "せいどがよ"));
        assertEquals("機械学習のあ", outputOf(new ImeApi((k, c) -> "機械学習の", CandidateSource.NONE), "きかいがくしゅうのあ"));
        assertEquals("モデルをで", outputOf(new ImeApi((k, c) -> "モデルを", CandidateSource.NONE), "もでるをで"));
        assertEquals("をでプロイしました",
                outputOf(new ImeApi((k, c) -> "をでプロイしまし", CandidateSource.NONE), "をでぷろいしました"));
    }

    @Test
    void anAnswerThatFollowsTheWholeReadingIsLeftAlone() {
        assertEquals("精度が", outputOf(new ImeApi((k, c) -> "精度が", CandidateSource.NONE), "せいどが"));
        assertEquals("倍は", outputOf(new ImeApi((k, c) -> "倍は", CandidateSource.NONE), "ばいは"));
        assertEquals("アルゴリズム", outputOf(new ImeApi((k, c) -> "アルゴリズム", CandidateSource.NONE), "あるごりずむ"));
        assertEquals("社会保障", outputOf(new ImeApi((k, c) -> "社会保障", CandidateSource.NONE), "しゃかいほしょう"));
    }

    @Test
    void anAnswerWithLettersIsNotChecked() {
        assertEquals("AI", outputOf(new ImeApi((k, c) -> "AI", CandidateSource.NONE), "えーあい"));
        assertEquals("3.5倍", outputOf(new ImeApi((k, c) -> "3.5倍", CandidateSource.NONE), "さんてんごばい"));
    }

    @Test
    void theBunsetsuOfAWholeConversionTogetherCoverTheWholeReading() {
        ImeApi api = new ImeApi((k, c) -> "精度が", CandidateSource.NONE);     // the model dropped the よ
        var segments = parse(call(() -> api.segmentConvert("{\"input\":\"せいどがよ\"}"))).getAsJsonArray("segments");
        StringBuilder shown = new StringBuilder();
        StringBuilder readings = new StringBuilder();
        for (var segment : segments) {
            shown.append(candidatesOf(segment.getAsJsonObject()).get(0));
            readings.append(segment.getAsJsonObject().get("reading").getAsString());
        }
        assertEquals("精度がよ", shown.toString());
        assertEquals("せいどがよ", readings.toString());
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
