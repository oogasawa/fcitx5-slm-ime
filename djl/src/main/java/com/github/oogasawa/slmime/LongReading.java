package com.github.oogasawa.slmime;

/**
 * Where a long reading is cut for the model. The source (the text before the cursor, SEP, the reading, EOS) must fit
 * in the positions the model was exported with; a reading longer than {@link #LONGEST_PIECE} characters is converted
 * in pieces, each after the text before it and the conversion of the pieces before it (as the engine of version 2).
 */
final class LongReading {

    /** The longest reading the model converts in one run. */
    static final int LONGEST_PIECE = 64;
    /** A piece is cut after a punctuation mark when there is one in the last characters it may take. */
    static final int LOOK_BACK_FOR_PUNCTUATION = 32;
    private static final String MARKS = "、。，．！？!?・」』）";

    private LongReading() {
    }

    /** Where the piece of {@code reading} (code points) that starts at {@code start} ends. */
    static int pieceEnd(int[] reading, int start) {
        int end = Math.min(reading.length, start + LONGEST_PIECE);
        if (end == reading.length) {
            return end;
        }
        for (int at = end; at > start + LONGEST_PIECE - LOOK_BACK_FOR_PUNCTUATION; at--) {
            if (MARKS.indexOf(reading[at - 1]) >= 0) {
                return at;
            }
        }
        return end;
    }
}
