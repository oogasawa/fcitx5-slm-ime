package com.github.oogasawa.slmime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class LongReadingTest {

    private static int[] cps(String s) {
        return s.codePoints().toArray();
    }

    @Test
    void aShortReadingIsOnePiece() {
        assertEquals(10, LongReading.pieceEnd(cps("あいうえおかきくけこ"), 0));
    }

    @Test
    void aLongReadingIsCutAfterTheLastPunctuationWithinReach() {
        String reading = "あ".repeat(50) + "、" + "い".repeat(40);
        assertEquals(51, LongReading.pieceEnd(cps(reading), 0));
        assertEquals(91, LongReading.pieceEnd(cps(reading), 51));
    }

    @Test
    void withoutPunctuationAPieceIsTheLongestPiece() {
        assertEquals(LongReading.LONGEST_PIECE, LongReading.pieceEnd(cps("う".repeat(100)), 0));
    }

    @Test
    void punctuationTooEarlyIsNotUsed() {
        String reading = "え".repeat(10) + "。" + "お".repeat(100);
        assertEquals(LongReading.LONGEST_PIECE, LongReading.pieceEnd(cps(reading), 0));
    }
}
