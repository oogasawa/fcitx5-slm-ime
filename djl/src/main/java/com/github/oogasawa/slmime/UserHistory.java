package com.github.oogasawa.slmime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * The candidates that the user chose in place of the first one, kept by reading.
 *
 * <p>When the user picks another candidate for a bunsetsu, the pair (reading of the bunsetsu, chosen text) is
 * learned. The next time the same reading comes up, the chosen text is put before the model's answer. The model
 * itself is not changed. The choice made most often wins; among equal counts, the latest one wins.
 * The history is a text file with one {@code reading TAB chosen text} per line, appended to at every choice
 * and read again at start-up; deleting the file forgets everything.</p>
 */
public final class UserHistory {

    private static final Logger log = Logger.getLogger(UserHistory.class.getName());
    private static final int LONGEST = 64;

    private final Path file;
    /** reading to (chosen text to count); the iteration order is the order of the latest choice. */
    private final Map<String, LinkedHashMap<String, Integer>> choices = new HashMap<>();

    private UserHistory(Path file) {
        this.file = file;
    }

    /** A history that is lost at exit. */
    public static UserHistory inMemory() {
        return new UserHistory(null);
    }

    /** A history kept in {@code file}; the lines already in it are read now. */
    public static UserHistory inFile(Path file) {
        UserHistory history = new UserHistory(file);
        if (Files.exists(file)) {
            try {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (String line : lines) {
                    int tab = line.indexOf('\t');
                    if (tab > 0) {
                        history.remember(line.substring(0, tab), line.substring(tab + 1));
                    }
                }
            } catch (IOException e) {
                log.warning("The history " + file + " cannot be read: " + e.getMessage());
            }
        }
        return history;
    }

    /** Records that the user chose {@code chosen} for the bunsetsu typed as {@code reading}. */
    public synchronized void learn(String reading, String chosen) {
        if (!acceptable(reading) || !acceptable(chosen)) {
            return;
        }
        remember(reading, chosen);
        if (file != null) {
            try {
                Files.writeString(file, reading + "\t" + chosen + "\n", StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                log.warning("The history " + file + " cannot be written: " + e.getMessage());
            }
        }
    }

    /** The text the user chose most often for {@code reading}, if any. */
    public synchronized Optional<String> preferred(String reading) {
        LinkedHashMap<String, Integer> candidates = choices.get(reading);
        if (candidates == null) {
            return Optional.empty();
        }
        String best = null;
        int bestCount = 0;
        for (Map.Entry<String, Integer> e : candidates.entrySet()) {
            if (e.getValue() >= bestCount) {
                best = e.getKey();
                bestCount = e.getValue();
            }
        }
        return Optional.ofNullable(best);
    }

    /** A stretch of a typed reading, {@code start} inclusive to {@code end} exclusive, for which the user chose a text. */
    public record Match(int start, int end, String chosen) {
    }

    /**
     * The stretches of {@code reading} that are the reading of an earlier choice. A longer reading is taken before
     * a shorter one, and a stretch never overlaps one already taken. Readings shorter than {@code shortest}
     * characters are left out: a short reading such as め occurs inside many words that the user never meant.
     *
     * @return the matches in the order of their position in {@code reading}
     */
    public synchronized List<Match> matchesIn(String reading, int shortest) {
        List<Match> found = new ArrayList<>();
        for (String key : choices.keySet()) {
            if (key.length() < shortest) {
                continue;
            }
            String chosen = preferred(key).orElse(null);
            for (int at = reading.indexOf(key); at >= 0; at = reading.indexOf(key, at + 1)) {
                found.add(new Match(at, at + key.length(), chosen));
            }
        }
        found.sort(Comparator.comparingInt((Match m) -> m.end() - m.start()).reversed()
                .thenComparingInt(Match::start));
        List<Match> taken = new ArrayList<>();
        for (Match candidate : found) {
            boolean overlaps = taken.stream().anyMatch(t -> candidate.start() < t.end() && t.start() < candidate.end());
            if (!overlaps) {
                taken.add(candidate);
            }
        }
        taken.sort(Comparator.comparingInt(Match::start));
        return taken;
    }

    /** The number of readings that have a choice. */
    public synchronized int size() {
        return choices.size();
    }

    private void remember(String reading, String chosen) {
        LinkedHashMap<String, Integer> candidates = choices.computeIfAbsent(reading, r -> new LinkedHashMap<>());
        int count = candidates.getOrDefault(chosen, 0) + 1;
        candidates.remove(chosen);
        candidates.put(chosen, count);
    }

    private static boolean acceptable(String text) {
        return text != null && !text.isEmpty() && text.length() <= LONGEST
                && text.indexOf('\t') < 0 && text.indexOf('\n') < 0 && text.indexOf('\r') < 0;
    }
}
