package com.github.oogasawa.slmime;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Finds the reading (the hiragana the user typed) of each bunsetsu in the model's output.
 *
 * <p>The model turns "きかいがくしゅうのあるごりずむ" into "機械学習|の|アルゴリズム" but does not say which part
 * of the typed text each bunsetsu comes from. An editor that lets the user pick another candidate for one
 * bunsetsu needs that reading. The kana in the output (の, アルゴリズム) occur in the typed text, so they fix
 * the positions; a run of kanji takes as many typed characters as are left between them. The cheapest such
 * alignment is found by dynamic programming. When no alignment is cheap enough (the typed text and the output
 * disagree), the typed text is cut in proportion to the length of the output pieces.</p>
 */
public final class ReadingAligner {

    private static final double INFINITY = Double.MAX_VALUE / 4;
    private static final double KANA_MISMATCH = 4.0;
    private static final double OTHER_MISMATCH = 1.0;
    private static final double LONGEST_PLAUSIBLE_PER_KANJI = 5;
    private static final double PREFERRED_PER_KANJI = 2.2;
    private static final double SPREAD_COST = 0.3;
    private static final double GIVE_UP_PER_TOKEN = 1.5;

    private ReadingAligner() {
    }

    /**
     * Cuts {@code reading} into one piece per element of {@code segments}, in order, so that the pieces joined
     * give back {@code reading}.
     *
     * @param reading the typed text, hiragana for the most part
     * @param segments the bunsetsu of the model's output, without the separator
     * @return the reading of each segment
     */
    public static List<String> readings(String reading, List<String> segments) {
        if (segments.isEmpty()) {
            return List.of();
        }
        if (segments.size() == 1) {
            return List.of(reading);
        }
        List<Token> tokens = tokenize(segments);
        List<Integer> ends = align(reading, tokens);
        if (ends == null) {
            return proportional(reading, segments);
        }
        List<String> result = new ArrayList<>();
        int segmentStart = 0;
        int tokenIndex = 0;
        for (int s = 0; s < segments.size(); s++) {
            int lastTokenOfSegment = tokenIndex;
            while (lastTokenOfSegment < tokens.size() && tokens.get(lastTokenOfSegment).segment == s) {
                lastTokenOfSegment++;
            }
            int end = lastTokenOfSegment > tokenIndex ? ends.get(lastTokenOfSegment - 1) : segmentStart;
            result.add(reading.substring(segmentStart, end));
            segmentStart = end;
            tokenIndex = lastTokenOfSegment;
        }
        return result;
    }

    private enum Kind { KANA, KANJI, OTHER }

    private record Token(Kind kind, String text, int segment) {
        int length() {
            return text.length();
        }
    }

    private static List<Token> tokenize(List<String> segments) {
        List<Token> tokens = new ArrayList<>();
        for (int s = 0; s < segments.size(); s++) {
            String text = segments.get(s);
            int i = 0;
            while (i < text.length()) {
                char c = text.charAt(i);
                if (isKanji(c)) {
                    int j = i;
                    while (j < text.length() && isKanji(text.charAt(j))) {
                        j++;
                    }
                    tokens.add(new Token(Kind.KANJI, text.substring(i, j), s));
                    i = j;
                } else {
                    tokens.add(new Token(isKana(c) ? Kind.KANA : Kind.OTHER, String.valueOf(c), s));
                    i++;
                }
            }
        }
        return tokens;
    }

    /** The cheapest alignment of the tokens with the reading: where every token ends, and what it costs. */
    private record Alignment(List<Integer> ends, double cost) {
    }

    /** Returns, for every token, the reading position at which it ends; null if no alignment is cheap enough. */
    private static List<Integer> align(String reading, List<Token> tokens) {
        Alignment alignment = cheapest(reading, tokens);
        return alignment == null || alignment.cost > GIVE_UP_PER_TOKEN * tokens.size() ? null : alignment.ends;
    }

    /**
     * What it costs to explain {@code text} as the conversion of the whole of {@code reading}: 0 when every kana of the
     * text is where the reading has it and every run of kanji takes about as many characters as it usually does.
     * Characters of the reading that the text does not account for make the cost high, or infinite.
     */
    public static double alignmentCost(String reading, String text) {
        Alignment alignment = cheapest(reading, tokenize(List.of(text)));
        return alignment == null ? Double.POSITIVE_INFINITY : alignment.cost;
    }

    private static Alignment cheapest(String reading, List<Token> tokens) {
        int m = reading.length();
        int n = tokens.size();
        double[][] cost = new double[n + 1][m + 1];
        int[][] from = new int[n + 1][m + 1];
        for (double[] row : cost) {
            Arrays.fill(row, INFINITY);
        }
        cost[0][0] = 0;
        for (int t = 0; t < n; t++) {
            Token token = tokens.get(t);
            for (int i = 0; i <= m; i++) {
                double here = cost[t][i];
                if (here >= INFINITY) {
                    continue;
                }
                if (token.kind == Kind.KANJI) {
                    int longest = (int) Math.min(m - i, LONGEST_PLAUSIBLE_PER_KANJI * token.length());
                    for (int k = token.length(); k <= longest; k++) {
                        double c = here + SPREAD_COST * Math.abs(k - PREFERRED_PER_KANJI * token.length());
                        if (c < cost[t + 1][i + k]) {
                            cost[t + 1][i + k] = c;
                            from[t + 1][i + k] = i;
                        }
                    }
                } else if (i < m) {
                    double step = token.kind == Kind.KANA
                            ? (sameKana(token.text.charAt(0), reading.charAt(i)) ? 0 : KANA_MISMATCH)
                            : (sameOther(token.text.charAt(0), reading.charAt(i)) ? 0 : OTHER_MISMATCH);
                    if (here + step < cost[t + 1][i + 1]) {
                        cost[t + 1][i + 1] = here + step;
                        from[t + 1][i + 1] = i;
                    }
                }
            }
        }
        if (cost[n][m] >= INFINITY) {
            return null;
        }
        Integer[] ends = new Integer[n];
        int i = m;
        for (int t = n; t >= 1; t--) {
            ends[t - 1] = i;
            i = from[t][i];
        }
        return new Alignment(Arrays.asList(ends), cost[n][m]);
    }

    private static List<String> proportional(String reading, List<String> segments) {
        int total = 0;
        for (String s : segments) {
            total += Math.max(1, s.length());
        }
        List<String> result = new ArrayList<>();
        int taken = 0;
        int cumulative = 0;
        for (int s = 0; s < segments.size(); s++) {
            cumulative += Math.max(1, segments.get(s).length());
            int end = s == segments.size() - 1 ? reading.length()
                    : (int) Math.round((double) reading.length() * cumulative / total);
            end = Math.max(taken, Math.min(reading.length(), end));
            result.add(reading.substring(taken, end));
            taken = end;
        }
        return result;
    }

    static boolean isKanji(char c) {
        return (c >= '一' && c <= '鿿') || (c >= '㐀' && c <= '䶿') || c == '々' || c == '〆'
                || (c >= '豈' && c <= '﫿');
    }

    private static boolean isKana(char c) {
        return (c >= 'ぁ' && c <= 'ゖ') || (c >= 'ァ' && c <= 'ヺ') || c == 'ー' || c == 'ヽ'
                || c == 'ヾ' || c == 'ゝ' || c == 'ゞ';
    }

    private static char toHiragana(char c) {
        return c >= 'ァ' && c <= 'ヶ' ? (char) (c - 0x60) : c;
    }

    private static boolean sameKana(char output, char typed) {
        return toHiragana(output) == toHiragana(typed);
    }

    private static boolean sameOther(char output, char typed) {
        return java.text.Normalizer.normalize(String.valueOf(output), java.text.Normalizer.Form.NFKC)
                .equals(java.text.Normalizer.normalize(String.valueOf(typed), java.text.Normalizer.Form.NFKC));
    }
}
