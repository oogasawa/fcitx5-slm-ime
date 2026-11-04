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

    /**
     * The source of a model trained with hints (prep/add_hints.py): {@code reading=word ... SEP <text before the
     * cursor> SEP <reading> EOS}, the text before the cursor cut from the left so that the source before EOS stays
     * within {@code maxLen} characters. Without hints, or when the hints and the reading alone are too long, the
     * source is the one of {@link #of(CharTokenizer, String, String, boolean, int)} with context.
     */
    public static List<Integer> withHints(CharTokenizer tokenizer, String context, String reading, int contextChars,
                                          List<DictionaryHints.Hint> hints, int maxLen) {
        String text = text(context, reading, contextChars, hints, maxLen);
        List<Integer> ids = new ArrayList<>();
        for (int p : text.codePoints().toArray()) {
            ids.addAll(p == SEP_CHAR ? List.of(SEP_ID) : tokenizer.encodeChars(Character.toString(p)));
        }
        ids.add(EOS_ID);
        return ids;
    }

    /** The character that stands for {@code [SEP]} in the training rows. */
    static final int SEP_CHAR = 0x1f;

    /** The source of {@link #withHints} as text, with {@link #SEP_CHAR} for SEP, as the training rows have it. */
    static String text(String context, String reading, int contextChars, List<DictionaryHints.Hint> hints,
                       int maxLen) {
        String sep = Character.toString(SEP_CHAR);
        String before = tail(context, contextChars);
        String plain = before + sep + reading;
        if (hints.isEmpty()) {
            return plain;
        }
        StringBuilder head = new StringBuilder();
        for (DictionaryHints.Hint h : hints) {
            head.append(head.length() == 0 ? "" : " ").append(h);
        }
        head.append(sep);
        int headLen = head.codePointCount(0, head.length());
        int room = maxLen - headLen - 1 - reading.codePointCount(0, reading.length());
        if (room < 0) {
            return plain;
        }
        return head + tail(before, room) + sep + reading;
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
