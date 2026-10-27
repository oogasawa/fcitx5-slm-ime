package com.github.oogasawa.slmime;

import java.util.ArrayList;
import java.util.List;

/**
 * Puts the texts that the user chose before into the model's answer, where the answer contains the reading of
 * an earlier choice.
 *
 * <p>{@link UserHistory} is looked up by the whole reading of a bunsetsu. The model cuts the typed text into
 * bunsetsu in its own way, so the reading the user once corrected (でぷろい) is often a part of a longer bunsetsu
 * (でぷろいされたかな), or is split by a bunsetsu boundary (で|ぷろいされたかな). This class finds the stretches of
 * the typed text that are the reading of a choice, finds the part of the model's output that comes from each
 * stretch, and replaces that part with the chosen text. Bunsetsu that the stretch runs across are joined.</p>
 *
 * <p>The output is cut into pieces (a run of kanji, and every other character alone), and the reading of each
 * piece is found with {@link ReadingAligner}. A stretch is applied only when its two ends fall on the ends of
 * pieces, which is not so when an end lies inside a run of kanji; then the model's output is left as it is,
 * because the part that comes from the stretch cannot be told.</p>
 */
final class HistorySubstitution {

    /** Readings shorter than this many characters are not looked for inside a longer reading. */
    static final int SHORTEST_READING = 3;

    /**
     * @param texts the bunsetsu after the substitution
     * @param originals the same bunsetsu as the model wrote them (joined where the substitution joined bunsetsu)
     * @param readings the reading of each bunsetsu
     */
    record Result(List<String> texts, List<String> originals, List<String> readings) {
    }

    private HistorySubstitution() {
    }

    /**
     * @param reading the typed text
     * @param segments the bunsetsu of the model's output, without the separator
     */
    static Result apply(String reading, List<String> segments, UserHistory history) {
        List<UserHistory.Match> matches = history.matchesIn(reading, SHORTEST_READING);
        if (matches.isEmpty()) {
            return new Result(segments, segments, ReadingAligner.readings(reading, segments));
        }
        List<String> pieces = new ArrayList<>();
        List<Integer> segmentOf = new ArrayList<>();
        for (int s = 0; s < segments.size(); s++) {
            for (String piece : pieces(segments.get(s))) {
                pieces.add(piece);
                segmentOf.add(s);
            }
        }
        List<String> pieceReadings = ReadingAligner.readings(reading, pieces);
        int[] begin = new int[pieces.size() + 1];
        for (int i = 0; i < pieces.size(); i++) {
            begin[i + 1] = begin[i] + pieceReadings.get(i).length();
        }

        boolean[] joinedWithNext = new boolean[segments.size()];
        List<String> unitTexts = new ArrayList<>();
        List<String> unitReadings = new ArrayList<>();
        List<Integer> unitSegment = new ArrayList<>();
        int piece = 0;
        int applied = 0;
        while (piece < pieces.size()) {
            UserHistory.Match match = matchStartingAt(matches, begin[piece]);
            int last = match == null ? -1 : pieceEndingAt(begin, match.end(), piece);
            if (last >= 0) {
                for (int s = segmentOf.get(piece); s < segmentOf.get(last); s++) {
                    joinedWithNext[s] = true;
                }
                unitTexts.add(match.chosen());
                unitReadings.add(reading.substring(match.start(), match.end()));
                unitSegment.add(segmentOf.get(piece));
                piece = last + 1;
                applied++;
            } else {
                unitTexts.add(pieces.get(piece));
                unitReadings.add(pieceReadings.get(piece));
                unitSegment.add(segmentOf.get(piece));
                piece++;
            }
        }
        if (applied == 0) {
            return new Result(segments, segments, ReadingAligner.readings(reading, segments));
        }

        int[] groupOf = new int[segments.size()];
        int groups = 0;
        for (int s = 0; s < segments.size(); s++) {
            if (s > 0 && joinedWithNext[s - 1]) {
                groupOf[s] = groupOf[s - 1];
            } else {
                groupOf[s] = groups++;
            }
        }
        String[] texts = new String[groups];
        String[] originals = new String[groups];
        String[] readings = new String[groups];
        java.util.Arrays.fill(texts, "");
        java.util.Arrays.fill(originals, "");
        java.util.Arrays.fill(readings, "");
        for (int s = 0; s < segments.size(); s++) {
            originals[groupOf[s]] += segments.get(s);
        }
        for (int u = 0; u < unitTexts.size(); u++) {
            int g = groupOf[unitSegment.get(u)];
            texts[g] += unitTexts.get(u);
            readings[g] += unitReadings.get(u);
        }
        return new Result(List.of(texts), List.of(originals), List.of(readings));
    }

    private static UserHistory.Match matchStartingAt(List<UserHistory.Match> matches, int start) {
        for (UserHistory.Match m : matches) {
            if (m.start() == start) {
                return m;
            }
        }
        return null;
    }

    /** The index of the piece, from {@code from} on, whose reading ends at {@code end}; -1 if no piece ends there. */
    private static int pieceEndingAt(int[] begin, int end, int from) {
        for (int i = from; i + 1 < begin.length; i++) {
            if (begin[i + 1] == end) {
                return i;
            }
            if (begin[i + 1] > end) {
                return -1;
            }
        }
        return -1;
    }

    /** The text cut into the units whose reading is told apart: a run of kanji, and every other character alone. */
    static List<String> pieces(String text) {
        List<String> pieces = new ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            int j = i + 1;
            if (ReadingAligner.isKanji(text.charAt(i))) {
                while (j < text.length() && ReadingAligner.isKanji(text.charAt(j))) {
                    j++;
                }
            }
            pieces.add(text.substring(i, j));
            i = j;
        }
        return pieces;
    }
}
