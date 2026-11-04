package com.github.oogasawa.slmime;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
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
 * {@link #HALF_LIFE_DAYS} days after it was last learned; the heaviest come first. A text the user takes is
 * committed and so learned again. When more than a given number are kept, the lightest are dropped
 * ({@link #keepAtMost}): those learned once long ago and not taken since.</p>
 *
 * <p>The same texts are kept a second time by the {@link #KEY_CHARS} characters written before them, so that the
 * text after what was just committed can be offered ({@link #continuations}): committing a prediction and then the
 * continuations one after another, the user types a sentence written before with a few kana and Tab after Tab.</p>
 *
 * <p>The texts are saved in a file of their own ({@link #save}), one {@code reading TAB text TAB weight TAB
 * time} per line and one {@code after TAB characters before TAB text TAB weight TAB time} per continuation, read at
 * start ({@link #load}), so that they do not depend on where they came from. Not shared between threads: an actor
 * of the server holds it.</p>
 */
final class Predictions {

    /** The longest text of a prediction, in characters. */
    static final int LONGEST = 40;
    /** A continuation is found by this many characters before it (spaces and line breaks left out). */
    static final int KEY_CHARS = 6;
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
    private final Map<String, Map<String, Seen>> byBefore = new HashMap<>();
    private int continuations;

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
            String key = keyOf(before(words, start));
            if (isUseful(t) && key.codePointCount(0, key.length()) == KEY_CHARS) {
                Seen seen = byBefore.computeIfAbsent(key, k -> new HashMap<>()).computeIfAbsent(t, x -> {
                    continuations++;
                    return new Seen();
                });
                seen.weight = decayed(seen, when) + weight;
                seen.when = Math.max(seen.when, when);
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

    /** How many continuations are kept. */
    int continuationCount() {
        return continuations;
    }

    /** The heaviest texts that came after the end of {@code context} (its last {@link #KEY_CHARS} characters). */
    List<String> continuations(String context, int limit, long now) {
        Map<String, Seen> found = byBefore.get(keyOf(context));
        if (found == null) {
            return List.of();
        }
        return found.entrySet().stream()
                .filter(e -> isUseful(e.getKey()))
                .sorted(Comparator.<Map.Entry<String, Seen>>comparingDouble(e -> -decayed(e.getValue(), now))
                        .thenComparingInt(e -> e.getKey().length()))
                .limit(limit).map(Map.Entry::getKey).toList();
    }

    /** A continuation worth offering: three characters or more, with kana or kanji in them (not the 2 or The that is
     *  left of a sentence cut before a number or a word in letters). */
    static boolean isUseful(String text) {
        return text.length() >= 3 && text.chars().anyMatch(c -> c >= 0x3041 && c <= 0x9FFF);
    }

    /** The text of the words before {@code start}, at most a few more characters than a key needs. */
    private static String before(List<Readings.Word> words, int start) {
        StringBuilder b = new StringBuilder();
        for (int i = start - 1; i >= 0 && b.length() < KEY_CHARS * 2; i--) {
            b.insert(0, words.get(i).surface());
        }
        return b.toString();
    }

    /** The last {@link #KEY_CHARS} characters of a text, spaces and line breaks left out; shorter when it is.
     *  Characters, not UTF-16 units: half of a character outside the BMP (an emoji) cannot be written in UTF-8. */
    static String keyOf(String text) {
        int[] kept = text.codePoints().filter(c -> !Character.isWhitespace(c) && c != 0x3000).toArray();
        int from = Math.max(0, kept.length - KEY_CHARS);
        return new String(kept, from, kept.length - from);
    }

    /**
     * Drops the lightest texts, as they weigh at {@code now}, when more than {@code most} are kept, down to nine
     * tenths of it, so that this is not done again at every text learned after.
     */
    void keepAtMost(int most, long now) {
        keepContinuationsAtMost(most, now);
        if (texts <= most) {
            return;
        }
        record Kept(String reading, String text, double weight) {
        }
        List<Kept> all = new ArrayList<>(texts);
        for (var r : byReading.entrySet()) {
            for (var t : r.getValue().entrySet()) {
                all.add(new Kept(r.getKey(), t.getKey(), decayed(t.getValue(), now)));
            }
        }
        all.sort(Comparator.comparingDouble(Kept::weight));
        int drop = texts - most * 9 / 10;
        for (int i = 0; i < drop; i++) {
            Kept k = all.get(i);
            Map<String, Seen> same = byReading.get(k.reading());
            same.remove(k.text());
            if (same.isEmpty()) {
                byReading.remove(k.reading());
            }
            texts--;
        }
    }

    private void keepContinuationsAtMost(int most, long now) {
        if (continuations <= most) {
            return;
        }
        record Kept(String key, String text, double weight) {
        }
        List<Kept> all = new ArrayList<>(continuations);
        for (var k : byBefore.entrySet()) {
            for (var t : k.getValue().entrySet()) {
                all.add(new Kept(k.getKey(), t.getKey(), decayed(t.getValue(), now)));
            }
        }
        all.sort(Comparator.comparingDouble(Kept::weight));
        int drop = continuations - most * 9 / 10;
        for (int i = 0; i < drop; i++) {
            Kept k = all.get(i);
            Map<String, Seen> same = byBefore.get(k.key());
            same.remove(k.text());
            if (same.isEmpty()) {
                byBefore.remove(k.key());
            }
            continuations--;
        }
    }

    /** Writes the texts to {@code file}, readable by the user only, replacing it at once when written. */
    void save(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.deleteIfExists(tmp);
        Files.createFile(tmp, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        // a writer that puts ? for what UTF-8 cannot hold, rather than failing the whole file
        try (BufferedWriter out = new BufferedWriter(
                new java.io.OutputStreamWriter(Files.newOutputStream(tmp), StandardCharsets.UTF_8))) {
            for (var r : byReading.entrySet()) {
                for (var t : r.getValue().entrySet()) {
                    out.write(r.getKey() + "\t" + t.getKey() + "\t" + t.getValue().weight + "\t" + t.getValue().when
                            + "\n");
                }
            }
            for (var k : byBefore.entrySet()) {
                for (var t : k.getValue().entrySet()) {
                    out.write("after\t" + k.getKey() + "\t" + t.getKey() + "\t" + t.getValue().weight + "\t"
                            + t.getValue().when + "\n");
                }
            }
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** The texts saved in {@code file}; none when there is no such file. Lines that cannot be read are skipped. */
    static Predictions load(Path file) throws IOException {
        Predictions p = new Predictions();
        if (!Files.isRegularFile(file)) {
            return p;
        }
        // read leniently, so that a broken byte loses a line and not the whole memory
        for (String line : new String(Files.readAllBytes(file), StandardCharsets.UTF_8).split("\n")) {
            String[] f = line.split("\t");
            if (f.length == 5 && f[0].equals("after") && !f[1].isEmpty() && !f[2].isEmpty()) {
                try {
                    Seen seen = new Seen();
                    seen.weight = Double.parseDouble(f[3]);
                    seen.when = Long.parseLong(f[4]);
                    if (p.byBefore.computeIfAbsent(f[1], k -> new HashMap<>()).put(f[2], seen) == null) {
                        p.continuations++;
                    }
                } catch (NumberFormatException e) {
                    // skipped
                }
                continue;
            }
            if (f.length != 4 || f[0].isEmpty() || f[1].isEmpty()) {
                continue;
            }
            try {
                Seen seen = new Seen();
                seen.weight = Double.parseDouble(f[2]);
                seen.when = Long.parseLong(f[3]);
                if (p.byReading.computeIfAbsent(f[0], r -> new HashMap<>()).put(f[1], seen) == null) {
                    p.texts++;
                }
            } catch (NumberFormatException e) {
                // skipped
            }
        }
        return p;
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
