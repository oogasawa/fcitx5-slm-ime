package com.github.oogasawa.slmime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** These need libanthy on the machine; they are skipped where it is not installed. */
class AnthyCandidatesTest {

    private static AnthyCandidates anthyOrSkip() {
        try {
            return new AnthyCandidates();
        } catch (UnsupportedOperationException | LinkageError e) {
            Assumptions.assumeTrue(false, "libanthy is not installed: " + e.getMessage());
            return null;
        }
    }

    @Test
    void aReadingOfSeveralWordsIsOffered_asOneBunsetsu() {
        List<String> candidates = anthyOrSkip().candidates("きょうは", 8);
        assertEquals("今日は", candidates.get(0));
        assertTrue(candidates.contains("きょうは"));
    }

    @Test
    void theFirstCandidateIsAnthysOwnConversionOfSeveralWords() {
        assertTrue(anthyOrSkip().candidates("いいてんきですね", 8).get(0).contains("天気"));
    }

    @Test
    void theLimitIsKept() {
        assertTrue(anthyOrSkip().candidates("きょうは", 3).size() <= 3);
    }

    @Test
    void aLongReadingStaysOneBunsetsu() {
        List<String> candidates = anthyOrSkip().candidates("きかいがくしゅうのあるごりずむ", 5);
        assertTrue(candidates.stream().allMatch(c -> c.length() >= 5), candidates.toString());
    }

    @Test
    void anEmptyReadingGivesNothing() {
        assertEquals(List.of(), anthyOrSkip().candidates("", 5));
    }
}
