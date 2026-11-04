package com.github.oogasawa.slmime;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;

/**
 * Texts the user may be about to type, found by the reading typed so far: the rest of a sentence the user wrote
 * before or read in an answer of a model (おせわに to お世話になっております。).
 *
 * <p>Each sentence learned gives one text from the head of each bunsetsu in it to the end of the sentence, at
 * most {@link #LONGEST} characters, kept by its reading. A reading typed so far finds the texts whose readings begin
 * with it. Each text has a weight, the number of times it was learned, which halves every
 * {@link #HALF_LIFE_DAYS} days after it was last learned; the heaviest come first. Not shared between threads: an actor of the server holds it.</p>
 */
final class Predictions {

    /** The longest text of a prediction, in characters. */
    static final int LONGEST = 24;
    /** A prediction adds at least this many kana to what was typed. */
    static final int FEWEST_MORE = 2;
    static final double HALF_LIFE_DAYS = 90;
    private static final double DAY_MS = 24 * 60 * 60 * 1000.0;

    /** Words that do not begin a bunsetsu. */
    private static final Set<String> NOT_HEADS = Set.of("助詞", "助動詞", "接尾辞", "補助記号", "空白");
    /** Marks typed as they are, which are their own reading. */
    private static final String TYPED_MARKS = "、。，．！？「」『』（）・〜…";
    /** Marks of Markdown and of code, which the answers of a model hold and no one types in a sentence: a text ends
     *  before them (確認してほしい点** of a heading in bold). */
    private static final String NOT_TYPED = "*#`|[]{}<>_=\\";
    /** Marks that end a sentence. */
    private static final String ENDS = "。！？!?";

    private static final class Seen {
        double weight;
        long when;
    }

    private final TreeMap<String, Map<String, Seen>> byReading = new TreeMap<>();
    private int texts;

    /** Learns the texts of a sentence or more, cut into words with their readings. */
    void learn(List<Readings.Word> words, double weight, long when) {
        for (int start = 0; start < words.size(); start++) {
            if (!isHead(words, start)) {
                continue;
            }
            StringBuilder text = new StringBuilder();
            StringBuilder reading = new StringBuilder();
            // where the text and its reading may end: at the end of the sentence or before the head of a bunsetsu
            int textEnd = 0;
            int readingEnd = 0;
            for (int i = start; i < words.size(); i++) {
                Readings.Word w = words.get(i);
                String r = readingOf(w);
                // A word without a reading in kana (letters, a line break) ends the text before it; past the longest,
                // the text ends at the last bunsetsu that ends before it (not in a word: 何も治っ of 何も治っていない)
                boolean tooLong = text.length() + w.surface().length() > LONGEST;
                if (r.isEmpty() || tooLong) {
                    if (!tooLong || textEnd == 0) {
                        textEnd = text.length();
                        readingEnd = reading.length();
                    }
                    break;
                }
                text.append(w.surface());
                reading.append(r);
                if (ENDS.contains(w.surface())) {
                    textEnd = text.length();
                    readingEnd = reading.length();
                    break;
                }
                if (i + 1 == words.size() || isHead(words, i + 1)) {
                    textEnd = text.length();
                    readingEnd = reading.length();
                }
            }
            String t = text.substring(0, textEnd).strip();
            String r = reading.substring(0, readingEnd).strip();
            if (!t.isEmpty() && r.length() > FEWEST_MORE + 1) {
                add(r, t, weight, when);
            }
        }
    }

    private void add(String reading, String text, double weight, long when) {
        Seen seen = byReading.computeIfAbsent(reading, r -> new HashMap<>()).computeIfAbsent(text, t -> {
            texts++;
            return new Seen();
        });
        seen.weight = decayed(seen, when) + weight;
        seen.when = Math.max(seen.when, when);
    }

    /** The heaviest texts whose readings begin with {@code typed} and are at least {@link #FEWEST_MORE} longer. */
    List<String> predict(String typed, int limit, long now) {
        if (typed.isEmpty()) {
            return List.of();
        }
        NavigableMap<String, Map<String, Seen>> found =
                byReading.subMap(typed, true, typed + Character.MAX_VALUE, false);
        record Scored(String text, double weight) {
        }
        List<Scored> scored = new ArrayList<>();
        for (var entry : found.entrySet()) {
            if (entry.getKey().length() < typed.length() + FEWEST_MORE) {
                continue;
            }
            for (var t : entry.getValue().entrySet()) {
                scored.add(new Scored(t.getKey(), decayed(t.getValue(), now)));
            }
        }
        scored.sort(Comparator.comparingDouble(Scored::weight).reversed()
                .thenComparingInt(s -> s.text().length()));
        Set<String> out = new LinkedHashSet<>();
        for (Scored s : scored) {
            if (out.size() == limit) {
                break;
            }
            out.add(s.text());
        }
        return new ArrayList<>(out);
    }

    /** How many texts are kept. */
    int size() {
        return texts;
    }

    private static double decayed(Seen seen, long now) {
        if (seen.weight == 0) {
            return 0;
        }
        double days = Math.max(0, now - seen.when) / DAY_MS;
        return seen.weight * Math.pow(0.5, days / HALF_LIFE_DAYS);
    }

    private static boolean isHead(List<Readings.Word> words, int i) {
        Readings.Word w = words.get(i);
        if (NOT_HEADS.contains(w.partOfSpeech()) || readingOf(w).isEmpty()) {
            return false;
        }
        return i == 0 || !words.get(i - 1).partOfSpeech().equals("接頭辞");
    }

    /** The reading of a word as the user types it: a typed mark is its own reading, kana are their own. */
    static String readingOf(Readings.Word w) {
        if (w.surface().chars().anyMatch(c -> NOT_TYPED.indexOf(c) >= 0)) {
            return "";
        }
        if (w.surface().length() == 1 && TYPED_MARKS.contains(w.surface())) {
            return w.surface();
        }
        // Other marks and spaces are not typed as their names (Sudachi reads some, such as キゴウ)
        if (w.partOfSpeech().equals("補助記号") || w.partOfSpeech().equals("空白")) {
            return "";
        }
        if (!w.reading().isEmpty()) {
            return w.reading();
        }
        return Readings.hiragana(w.surface());
    }
}
