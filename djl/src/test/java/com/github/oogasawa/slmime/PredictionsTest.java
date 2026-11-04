package com.github.oogasawa.slmime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The predictions found by the reading typed so far, from sentences cut into words by hand as Sudachi cuts them.
 */
class PredictionsTest {

    private static final long DAY = 24L * 60 * 60 * 1000;
    private static final long NOW = 400 * DAY;

    private static Readings.Word w(String surface, String reading, String pos) {
        return new Readings.Word(surface, reading, pos);
    }

    /** お世話になっております。 */
    private static List<Readings.Word> osewa() {
        return List.of(w("お", "お", "接頭辞"), w("世話", "せわ", "名詞"), w("に", "に", "助詞"),
                w("なっ", "なっ", "動詞"), w("て", "て", "助詞"), w("おり", "おり", "動詞"),
                w("ます", "ます", "助動詞"), w("。", "", "補助記号"));
    }

    @Test
    void theRestOfASentenceIsFoundByTheReadingTyped() {
        Predictions p = new Predictions();
        p.learn(osewa(), 1, NOW);
        assertEquals(List.of("お世話になっております。"), p.predict("おせわに", 5, NOW));
        // a bunsetsu inside the sentence begins a text too
        assertEquals(List.of("なっております。"), p.predict("なって", 5, NOW));
        // not a word after a prefix, a particle or an auxiliary
        assertEquals(List.of(), p.predict("せわに", 5, NOW));
        assertEquals(List.of(), p.predict("ます", 5, NOW));
    }

    @Test
    void aPredictionAddsAtLeastTwoKana() {
        Predictions p = new Predictions();
        p.learn(osewa(), 1, NOW);
        assertEquals(List.of(), p.predict("おせわになっております", 5, NOW));
        assertEquals(List.of("お世話になっております。"), p.predict("おせわになっておりま", 5, NOW));
    }

    @Test
    void theHeavierAndTheNewerComeFirst() {
        Predictions p = new Predictions();
        List<Readings.Word> kanji = List.of(w("漢字", "かんじ", "名詞"), w("を", "を", "助詞"),
                w("書く", "かく", "動詞"));
        List<Readings.Word> feeling = List.of(w("感じ", "かんじ", "名詞"), w("が", "が", "助詞"),
                w("する", "する", "動詞"));
        p.learn(kanji, 1, NOW);
        p.learn(feeling, 1, NOW);
        p.learn(feeling, 1, NOW);
        assertEquals(List.of("感じがする", "漢字を書く"), p.predict("かんじ", 5, NOW));
        // learned again, much later, it weighs more than the other, whose weight has halved twice
        p.learn(kanji, 1, NOW + 180 * DAY);
        assertEquals(List.of("漢字を書く", "感じがする"), p.predict("かんじ", 5, NOW + 180 * DAY));
        assertEquals(List.of("漢字を書く"), p.predict("かんじ", 1, NOW + 180 * DAY));
    }

    @Test
    void aTextEndsBeforeAWordWithoutAReadingAndAtTheLongest() {
        Predictions p = new Predictions();
        p.learn(List.of(w("設定", "せってい", "名詞"), w("を", "を", "助詞"), w("pom.xml", "", "名詞"),
                w("に", "に", "助詞")), 1, NOW);
        assertEquals(List.of("設定を"), p.predict("せって", 5, NOW));
        // nor are the marks of Markdown typed
        p.learn(List.of(w("確認", "かくにん", "名詞"), w("し", "し", "動詞"), w("て", "て", "助詞"),
                w("ほしい", "ほしい", "形容詞"), w("点", "てん", "名詞"), w("**", "", "補助記号")), 1, NOW);
        assertEquals(List.of("確認してほしい点"), p.predict("かくにん", 5, NOW));
        List<Readings.Word> long_ = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            long_.add(w("あ", "あ", "名詞"));
        }
        p.learn(long_, 1, NOW);
        String first = p.predict("ああああ", 1, NOW).get(0);
        assertEquals(Predictions.LONGEST, first.length());
    }

    @Test
    void aTextTooLongEndsAtTheEndOfABunsetsu() {
        Predictions p = new Predictions();
        // 語句の thirteen times (39 characters) and then 終わり: past 40, the text ends after the last の, not inside
        // 終わり
        List<Readings.Word> words = new ArrayList<>();
        for (int i = 0; i < 13; i++) {
            words.add(w("語句", "ごく", "名詞"));
            words.add(w("の", "の", "助詞"));
        }
        words.add(w("終わり", "おわり", "名詞"));
        p.learn(words, 1, NOW);
        List<String> found = p.predict("ごくのごくの", 20, NOW);
        assertTrue(found.contains("語句の".repeat(13)), found.toString());
        assertTrue(found.stream().allMatch(t -> t.length() <= Predictions.LONGEST && !t.endsWith("終")));
    }

    @Test
    void theMarksTypedAreTheirOwnReading() {
        Predictions p = new Predictions();
        p.learn(List.of(w("はい", "はい", "感動詞"), w("、", "", "補助記号"), w("それ", "それ", "代名詞"),
                w("で", "で", "助詞"), w("行き", "いき", "動詞"), w("ましょう", "ましょう", "助動詞"),
                w("。", "", "補助記号")), 1, NOW);
        assertEquals(List.of("はい、それで行きましょう。"), p.predict("はい、そ", 5, NOW));
    }

    /** アンチパターンを読んで反省しなさい。次から気をつけて。 */
    private static List<Readings.Word> antiPattern() {
        return List.of(w("アンチパターン", "あんちぱたーん", "名詞"), w("を", "を", "助詞"), w("読ん", "よん", "動詞"),
                w("で", "で", "助詞"), w("反省", "はんせい", "名詞"), w("し", "し", "動詞"),
                w("なさい", "なさい", "助動詞"), w("。", "", "補助記号"), w("\n", "", "空白"), w("次", "つぎ", "名詞"),
                w("から", "から", "助詞"), w("気", "き", "名詞"), w("を", "を", "助詞"), w("つけ", "つけ", "動詞"),
                w("て", "て", "助詞"), w("。", "", "補助記号"));
    }

    @Test
    void theTextAfterWhatWasCommittedIsOffered() {
        Predictions p = new Predictions();
        p.learn(antiPattern(), 1, NOW);
        p.learn(antiPattern(), 1, NOW);
        p.learn(List.of(w("本", "ほん", "名詞"), w("を", "を", "助詞"), w("読ん", "よん", "動詞"), w("で", "で", "助詞"),
                w("寝る", "ねる", "動詞"), w("。", "", "補助記号")), 1, NOW);
        // a prediction first, then the rest Tab after Tab, the sentence learned twice before the other
        assertEquals(List.of("アンチパターンを読んで反省しなさい。"), p.predict("あんちぱ", 1, NOW));
        assertEquals(List.of("次から気をつけて。"), p.continuations("…アンチパターンを読んで反省しなさい。", 5, NOW));
        // in the middle of a sentence: what came after the same six characters (本を読んで is another text before)
        assertEquals(List.of("反省しなさい。"), p.continuations("パターンを読んで", 5, NOW));
        assertEquals(List.of(), p.continuations("を読んで", 5, NOW));
        // spaces and line breaks do not count; nothing after a text never seen
        assertEquals(List.of("次から気をつけて。"), p.continuations("反省しなさい。\n", 5, NOW));
        assertEquals(List.of(), p.continuations("まったく別の文", 5, NOW));
        // not a piece of letters left before a word without a reading
        assertTrue(!Predictions.isUseful("The") && !Predictions.isUseful("2") && Predictions.isUseful("pushして"));
    }

    @Test
    void theContinuationsAreSavedAndTrimmed(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws Exception {
        Predictions p = new Predictions();
        p.learn(antiPattern(), 1, NOW);
        java.nio.file.Path file = dir.resolve("predictions.tsv");
        p.save(file);
        Predictions back = Predictions.load(file);
        assertEquals(p.continuationCount(), back.continuationCount());
        assertEquals(List.of("次から気をつけて。"), back.continuations("反省しなさい。", 5, NOW));
        back.keepAtMost(1, NOW);
        assertTrue(back.continuationCount() <= 1);
    }

    @Test
    void aTextWithAnEmojiIsKeptAndSaved(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        Predictions p = new Predictions();
        // 🎉 is two UTF-16 units: a key cut between them could not be written
        // the six characters before 完成 are 🎉いうえおか, seven UTF-16 units
        p.learn(List.of(w("🎉", "", "補助記号"), w("いうえおか", "いうえおか", "名詞"), w("完成", "かんせい", "名詞"),
                w("です", "です", "助動詞"), w("。", "", "補助記号")), 1, NOW);
        assertEquals("🎉いうえおか", Predictions.keyOf("あ🎉いうえおか"));
        assertEquals(List.of("完成です。"), p.continuations("🎉いうえおか", 5, NOW));
        java.nio.file.Path file = dir.resolve("predictions.tsv");
        p.save(file);
        Predictions back = Predictions.load(file);
        assertEquals(p.size(), back.size());
        assertEquals(1, back.continuationCount());
        assertEquals(List.of("完成です。"), back.continuations("🎉いうえおか", 5, NOW));
        // a broken line is skipped, not the file
        java.nio.file.Files.write(file, new byte[] {(byte) 0xff, '\n'}, java.nio.file.StandardOpenOption.APPEND);
        assertEquals(p.size(), Predictions.load(file).size());
    }
}
