package com.github.oogasawa.slmime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The predictions found by the reading typed so far, from sentences cut into words by hand as Sudachi cuts them,
 * and the user's own words told from the text of a model in the conversation logs.
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
        for (int i = 0; i < 30; i++) {
            long_.add(w("あ", "あ", "名詞"));
        }
        p.learn(long_, 1, NOW);
        String first = p.predict("ああああ", 1, NOW).get(0);
        assertEquals(Predictions.LONGEST, first.length());
    }

    @Test
    void aTextTooLongEndsAtTheEndOfABunsetsu() {
        Predictions p = new Predictions();
        // 修正を依頼しているドキュメントについて、何も治っていない。 is 29 characters; the text ends after 何も, the last
        // bunsetsu that ends within 24, not inside 治っている
        p.learn(List.of(w("修正", "しゅうせい", "名詞"), w("を", "を", "助詞"), w("依頼", "いらい", "名詞"),
                w("し", "し", "動詞"), w("て", "て", "助詞"), w("いる", "いる", "動詞"),
                w("ドキュメント", "どきゅめんと", "名詞"), w("について", "について", "助詞"), w("、", "", "補助記号"),
                w("何", "なに", "代名詞"), w("も", "も", "助詞"), w("治っ", "なおっ", "動詞"), w("て", "て", "助詞"),
                w("い", "い", "動詞"), w("ない", "ない", "助動詞"), w("。", "", "補助記号")), 1, NOW);
        assertEquals(List.of("修正を依頼しているドキュメントについて、何も"), p.predict("しゅうせいを", 5, NOW));
    }

    @Test
    void theMarksTypedAreTheirOwnReading() {
        Predictions p = new Predictions();
        p.learn(List.of(w("はい", "はい", "感動詞"), w("、", "", "補助記号"), w("それ", "それ", "代名詞"),
                w("で", "で", "助詞"), w("行き", "いき", "動詞"), w("ましょう", "ましょう", "助動詞"),
                w("。", "", "補助記号")), 1, NOW);
        assertEquals(List.of("はい、それで行きましょう。"), p.predict("はい、そ", 5, NOW));
    }

    @Test
    void aTurnGivesWhatTheUserWroteAndWhatTheModelAnswered() {
        List<ConversationLogs.Said> said = new ArrayList<>();
        ConversationLogs.split("QUESTION: どうなりましたか？\n\nANSWER: まだ学習の途中です。", 5, said);
        assertEquals(2, said.size());
        assertEquals("どうなりましたか？", said.get(0).text());
        assertTrue(said.get(0).byUser());
        assertEquals("まだ学習の途中です。", said.get(1).text());
        assertEquals(5, said.get(1).when());
    }
}
