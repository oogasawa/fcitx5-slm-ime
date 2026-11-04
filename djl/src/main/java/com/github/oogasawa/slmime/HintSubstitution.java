package com.github.oogasawa.slmime;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Puts the words of the dictionary into the model's output where the model got them wrong. The model reads the
 * words as hints ({@link DictionaryHints}) and copies most of them, but it cannot write a character it was never
 * trained to write (諛 of 阿諛追従): it wrote アユ追唱 with the hint あゆついしょう=阿諛追従 in front of it.
 *
 * <p>A hint replaces bunsetsu of the output when its reading starts at the start of a bunsetsu and ends in the same
 * or a later bunsetsu with nothing after it but kana that end that bunsetsu's text, and when
 * <ul>
 * <li>the model did not write a word of the dictionary for that reading itself (リンパ節腫大 stays, though the
 *     dictionary has ＬＮ腫大 for the same reading),</li>
 * <li>the text it replaces does not end in kana: the model wrote that kana as the ending of a word, and the hint
 *     stands across a boundary (影響か|ら with えいきょうか=影響下),</li>
 * <li>the reading of the hint may follow one of a few words at the start of the bunsetsu ({@link #PREFIXES}: 右,
 *     左, 両), which are then written with it,</li>
 * <li>the text it replaces does not begin with a kana that the word does not begin with: the model wrote ご了承
 *     so on purpose, and the dictionary's 御了承 is not what the user means,</li>
 * <li>the text it replaces is as long as the word, and what follows it is what follows the reading: the model
 *     wrote another word for it (完成昏睡|に対しては for 肝性昏睡, 多発間|農用 for 多発肝膿瘍), not a longer word
 *     that holds the reading (人民委員部).</li>
 * </ul>
 * On the medical case and radiology reports of the Real-MedNLP Test Collection, with the Manbyo dictionary, the
 * medical words that the training text never holds were written right 68.1 % of the time with the rule and 56.7 %
 * with the hints alone (47.7 % with the released model), and the words it holds 79.5 % and 77.9 %; on 5000 rows of
 * ordinary text it changed two outputs.</p>
 */
public final class HintSubstitution {

    private HintSubstitution() {
    }

    /**
     * @param reading the typed reading
     * @param output the model's output, with '|' between the bunsetsu
     * @param hints the hints the model read, the longest readings first
     * @return the output with the words of the dictionary put in
     */
    public static String apply(String reading, String output, List<DictionaryHints.Hint> hints) {
        if (hints.isEmpty() || output.replace("|", "").isEmpty()) {
            return output;
        }
        List<String> texts = new ArrayList<>(Arrays.asList(output.split("\\|", -1)));
        List<String> readings = new ArrayList<>(ReadingAligner.readings(reading, texts));
        for (DictionaryHints.Hint hint : hints) {
            int start = 0;
            for (int i = 0; i < texts.size(); i++) {
                if (reading.startsWith(hint.reading(), start)) {
                    replace(reading, texts, readings, i, start, hint, hints);
                } else {
                    for (DictionaryHints.Hint prefix : PREFIXES) {
                        if (reading.startsWith(prefix.reading(), start)
                                && reading.startsWith(hint.reading(), start + prefix.reading().length())) {
                            replace(reading, texts, readings, i, start, joined(prefix, hint), withPrefix(prefix, hints));
                        }
                    }
                }
                start += readings.get(i).length();
            }
        }
        return String.join("|", texts);
    }

    /**
     * The words a bunsetsu may begin with before a word of the dictionary: right, left and both sides, which the
     * medical text puts in front of the names of parts of the body (うかようむきはい, 右下葉無気肺, which the dictionary
     * has as 下葉無気肺). A short list, so that a hint is not taken where a kana of another word comes before it.
     */
    static final List<DictionaryHints.Hint> PREFIXES = List.of(
        new DictionaryHints.Hint("う", "右"), new DictionaryHints.Hint("さ", "左"), new DictionaryHints.Hint("りょう", "両"));

    private static DictionaryHints.Hint joined(DictionaryHints.Hint prefix, DictionaryHints.Hint hint) {
        return new DictionaryHints.Hint(prefix.reading() + hint.reading(), prefix.word() + hint.word());
    }

    private static List<DictionaryHints.Hint> withPrefix(DictionaryHints.Hint prefix, List<DictionaryHints.Hint> hints) {
        return hints.stream().map(h -> joined(prefix, h)).toList();
    }

    /** Replaces bunsetsu i and those after it that the hint covers, if the rules of the class let it. */
    private static void replace(String reading, List<String> texts, List<String> readings, int i, int start,
                                DictionaryHints.Hint hint, List<DictionaryHints.Hint> hints) {
        int end = start + hint.reading().length();
        int at = start;
        for (int j = i; j < texts.size(); j++) {
            at += readings.get(j).length();
            if (at < end) {
                continue;
            }
            // The text the hint replaces is as long as its word; what follows it in bunsetsu j must be what follows
            // the reading of the hint: nothing, or text that begins with the same kana (完成昏睡|に対しては)
            String tail = reading.substring(end, at);
            String joined = String.join("", texts.subList(i, j + 1));
            String word = hint.word();
            int length = word.codePointCount(0, word.length());
            if (joined.codePointCount(0, joined.length()) < length) {
                return;
            }
            int cut = joined.offsetByCodePoints(0, length);
            String part = joined.substring(0, cut);
            String rest = joined.substring(cut);
            boolean follows = rest.isEmpty() ? tail.isEmpty()
                : isKana(rest.substring(0, 1)) && tail.startsWith(rest.substring(0, 1));
            boolean written = hints.stream().anyMatch(h -> h.reading().equals(hint.reading()) && h.word().equals(part));
            if (!follows || written || isKana(part.substring(part.length() - 1)) || keepsItsKana(part, word)) {
                return;
            }
            for (int k = j; k > i; k--) {
                texts.remove(k);
                readings.remove(k);
            }
            texts.set(i, word + rest);
            readings.set(i, reading.substring(start, at));
            return;
        }
    }

    /**
     * Whether the model began the text with a kana that the word writes otherwise: the honorific of ご了承 is meant
     * so, not 御了承. A kana inside the text (羽化用向き肺 for 下葉無気肺) is a part of a wrong conversion.
     */
    private static boolean keepsItsKana(String part, String word) {
        return isKana(part.substring(0, 1)) && !word.startsWith(part.substring(0, 1));
    }

    /** Whether the text is hiragana only (or empty). */
    private static boolean isKana(String text) {
        return text.chars().allMatch(c -> (c >= 0x3041 && c <= 0x309f) || c == 'ー');
    }
}
