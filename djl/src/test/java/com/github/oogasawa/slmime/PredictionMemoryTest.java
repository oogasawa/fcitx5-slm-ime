package com.github.oogasawa.slmime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The memory of the predictions: kept in a file of its own, trimmed to the heaviest, and fed with the texts other
 * programs put in the inbox.
 */
class PredictionMemoryTest {

    private static final long DAY = 24L * 60 * 60 * 1000;
    private static final long NOW = 400 * DAY;

    @TempDir
    Path dir;

    private static List<Readings.Word> words(String reading, String text) {
        return List.of(new Readings.Word(text, reading, "名詞"));
    }

    @Test
    void theLightestAreDroppedWhenTooManyAreKept() {
        Predictions p = new Predictions();
        // learned twice now, once now, once long ago
        p.learn(words("かんじを", "漢字を"), 1, NOW);
        p.learn(words("かんじを", "漢字を"), 1, NOW);
        p.learn(words("かんじが", "感じが"), 1, NOW);
        p.learn(words("かんしょう", "鑑賞"), 1, NOW - 360 * DAY);
        p.keepAtMost(10, NOW);
        assertEquals(3, p.size());
        p.keepAtMost(2, NOW);
        // down to nine tenths of 2, one: the heaviest
        assertEquals(1, p.size());
        assertEquals(List.of("漢字を"), p.predict("かん", 5, NOW));
    }

    @Test
    void theTextsAreSavedAndReadBack() throws Exception {
        Predictions p = new Predictions();
        p.learn(words("おせわになっております", "お世話になっております"), 1, NOW);
        p.learn(words("かんじを", "漢字を"), 1, NOW);
        Path file = dir.resolve("slm-ime/predictions.tsv");
        p.save(file);
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
        Predictions back = Predictions.load(file);
        assertEquals(2, back.size());
        assertEquals(List.of("お世話になっております"), back.predict("おせわに", 5, NOW));
        assertEquals(0, Predictions.load(dir.resolve("none.tsv")).size());
    }

    @Test
    void theTextsOfTheInboxAreTakenOnce() throws Exception {
        Path inbox = Files.createDirectories(dir.resolve("learn"));
        Files.writeString(inbox.resolve("b.jsonl"), "{\"text\": \"続きをお願いします\", \"time\": 7}\n");
        Files.writeString(inbox.resolve("a.jsonl"), "{\"text\": \"どうなりましたか？\", \"time\": 5}\n"
                + "not json\n{\"no\": \"text\"}\n\n{\"text\": \"時刻なし\"}\n");
        // half written: not taken yet
        Files.writeString(inbox.resolve("c.jsonl.tmp"), "{\"text\": \"書きかけ\"}\n");
        List<LearnInbox.Text> texts = new LearnInbox(inbox).take();
        assertEquals(List.of("どうなりましたか？", "時刻なし", "続きをお願いします"),
                texts.stream().map(LearnInbox.Text::text).toList());
        assertEquals(5, texts.get(0).when());
        assertTrue(texts.get(1).when() > NOW);
        // the files read are gone, the one being written is left
        try (var left = Files.list(inbox)) {
            assertEquals(List.of(inbox.resolve("c.jsonl.tmp")), left.toList());
        }
        assertTrue(new LearnInbox(inbox).take().isEmpty());
        assertTrue(new LearnInbox(dir.resolve("gone")).take().isEmpty());
    }
}
