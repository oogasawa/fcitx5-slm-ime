package com.github.oogasawa.slmime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * The words of a dictionary that a typed reading may hold, which a model trained with hints (prep/add_hints.py)
 * reads in front of its source as {@code reading=word reading=word ... SEP}, and copies where they fit. The words
 * are those whose reading is a stretch of the typed reading at least {@code shortest} kana long and that are at least
 * {@code fewestChars} characters long, the longest readings first, at most {@code most} of them: the same rules as
 * prep/add_hints.py, so that the model gets the form it was trained on.
 *
 * <p>Loaded once and not changed after, so that any thread may read it.</p>
 */
public final class DictionaryHints {

    /** One word of the dictionary found in a reading. */
    /**
     * A hint: {@code mark} is = for a word of the dictionary, which the model copies, and ~ for a text the user chose
     * before, which the model takes only where the text around it fits (prep/add_hints.py --history-table).
     */
    public record Hint(String reading, String word, char mark) {
        public Hint(String reading, String word) {
            this(reading, word, '=');
        }

        @Override
        public String toString() {
            return reading + mark + word;
        }
    }

    private final Map<String, List<String>> words;
    /** The lengths of the readings of the words, in code points, the longest first. */
    private final int[] lengths;
    private final int most;

    private DictionaryHints(Map<String, List<String>> words, int shortest, int most) {
        this.words = words;
        TreeSet<Integer> found = new TreeSet<>();
        for (String reading : words.keySet()) {
            int n = reading.codePointCount(0, reading.length());
            if (n >= shortest) {
                found.add(n);
            }
        }
        this.lengths = found.descendingSet().stream().mapToInt(Integer::intValue).toArray();
        this.most = most;
    }

    /**
     * Reads a table of reading TAB word [TAB kind] lines (prep/hard_words.py), keeping the words of at least
     * {@code fewestChars} characters in the order of the file.
     */
    public static DictionaryHints load(Path table, int shortest, int fewestChars, int most) throws IOException {
        Map<String, List<String>> words = new HashMap<>();
        for (String line : Files.readAllLines(table, StandardCharsets.UTF_8)) {
            String[] fields = line.split("\t");
            if (fields.length < 2) {
                continue;
            }
            if (fields[1].codePointCount(0, fields[1].length()) >= fewestChars) {
                words.computeIfAbsent(fields[0], r -> new ArrayList<>()).add(fields[1]);
            }
        }
        return new DictionaryHints(words, shortest, most);
    }

    /** With the limits that the model of version 3 was trained with: readings of 6 kana, words of 3, 4 hints. */
    public static DictionaryHints load(Path table) throws IOException {
        return load(table, 6, 3, 4);
    }

    /** The words whose readings are stretches of the reading, the longest readings first. */
    public List<Hint> of(String reading) {
        int[] points = reading.codePoints().toArray();
        List<Hint> found = new ArrayList<>();
        for (int n : lengths) {
            for (int i = 0; i + n <= points.length; i++) {
                String stretch = new String(points, i, n);
                for (String word : words.getOrDefault(stretch, List.of())) {
                    if (found.stream().noneMatch(h -> h.word().equals(word))) {
                        found.add(new Hint(stretch, word));
                        if (found.size() == most) {
                            return found;
                        }
                    }
                }
            }
        }
        return found;
    }
}
