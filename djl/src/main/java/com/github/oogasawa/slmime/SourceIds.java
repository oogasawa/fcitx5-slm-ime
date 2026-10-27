package com.github.oogasawa.slmime;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the token ids that the model reads for one conversion.
 *
 * <p>A model trained without context reads {@code <reading> EOS}. A model trained with context reads
 * {@code <text before the cursor> SEP <reading> EOS}, where the text before the cursor is cut to its last
 * characters and may be empty. The two must not be mixed up: a model gets the form it was trained on.</p>
 */
public final class SourceIds {

    /** Token id of {@code [SEP]}, fixed by the tokenizer. */
    public static final int SEP_ID = 4;
    /** Token id of {@code [EOS]}. */
    public static final int EOS_ID = 2;

    private SourceIds() {
    }

    /**
     * @param tokenizer the vocabulary of the model
     * @param context the text before the cursor, or null/empty
     * @param reading the typed hiragana
     * @param useContext whether the model was trained with context
     * @param contextChars how many characters of the end of {@code context} the model was trained to read
     */
    public static List<Integer> of(CharTokenizer tokenizer, String context, String reading,
                                   boolean useContext, int contextChars) {
        List<Integer> ids = new ArrayList<>();
        if (useContext) {
            ids.addAll(tokenizer.encodeChars(tail(context, contextChars)));
            ids.add(SEP_ID);
        }
        ids.addAll(tokenizer.encodeChars(reading));
        ids.add(EOS_ID);
        return ids;
    }

    /** The last {@code count} characters (code points) of {@code text}. */
    static String tail(String text, int count) {
        if (text == null || text.isEmpty() || count <= 0) {
            return "";
        }
        int points = text.codePointCount(0, text.length());
        if (points <= count) {
            return text;
        }
        return text.substring(text.offsetByCodePoints(0, points - count));
    }
}
