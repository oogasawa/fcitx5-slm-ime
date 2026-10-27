package com.github.oogasawa.slmime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class ReadingAlignerTest {

    @Test
    void kanaOfTheOutputFixTheBoundaries() {
        assertEquals(List.of("きかいがくしゅう", "の", "あるごりずむ"),
                ReadingAligner.readings("きかいがくしゅうのあるごりずむ", List.of("機械学習", "の", "アルゴリズム")));
    }

    @Test
    void particlesAfterNounsAreKeptWithTheirSegment() {
        assertEquals(List.of("げんだいの", "ふぉんとでも、"),
                ReadingAligner.readings("げんだいのふぉんとでも、", List.of("現代の", "フォントでも、")));
    }

    @Test
    void digitsAndBracketsMatchThemselves() {
        assertEquals(List.of("2022ねん（れいわ4ねん）", "8がつ"),
                ReadingAligner.readings("2022ねん（れいわ4ねん）8がつ", List.of("2022年（令和4年）", "8月")));
    }

    @Test
    void okuriganaSeparatesKanjiRuns() {
        assertEquals(List.of("おこなわれた", "しけん"),
                ReadingAligner.readings("おこなわれたしけん", List.of("行われた", "試験")));
    }

    @Test
    void aSingleSegmentTakesTheWholeReading() {
        assertEquals(List.of("きかいがくしゅう"), ReadingAligner.readings("きかいがくしゅう", List.of("機械学習")));
    }

    @Test
    void theReadingsAlwaysJoinBackToTheTypedText() {
        String typed = "ええとこれはまったくちがうよみのもじれつ";
        List<String> pieces = ReadingAligner.readings(typed, List.of("全然", "別の", "文章", "です", "ね"));
        assertEquals(typed, String.join("", pieces));
        assertEquals(5, pieces.size());
    }

    @Test
    void whenNothingFitsTheReadingIsCutInProportion() {
        List<String> pieces = ReadingAligner.readings("あいうえおかきくけこ", List.of("ABCDE", "FGHIJ"));
        assertEquals(List.of("あいうえお", "かきくけこ"), pieces);
    }
}
