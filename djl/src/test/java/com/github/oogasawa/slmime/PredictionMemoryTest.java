package com.github.oogasawa.slmime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The memory of the predictions: kept in a file of its own, trimmed to the heaviest, and fed from the conversation
 * logs, read in copies, from where they were read last.
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

    private static void write(Path db, String... messages) throws Exception {
        String name = db.toString();
        String url = "jdbc:h2:" + name.substring(0, name.length() - ".mv.db".length());
        try (Connection c = new org.h2.Driver().connect(url, new Properties()); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS LOGS (ID BIGINT AUTO_INCREMENT PRIMARY KEY, TIMESTAMP TIMESTAMP, "
                    + "LABEL VARCHAR(100), MESSAGE CLOB)");
            for (String m : messages) {
                s.execute("INSERT INTO LOGS (TIMESTAMP, LABEL, MESSAGE) VALUES (CURRENT_TIMESTAMP, "
                        + "'turn1/conversation', '" + m + "')");
            }
            s.execute("INSERT INTO LOGS (TIMESTAMP, LABEL, MESSAGE) VALUES (CURRENT_TIMESTAMP, 'turn1/step1/llm', "
                    + "'QUESTION: not a turn')");
        }
    }

    @Test
    void theLogsAreReadInCopiesFromWhereTheyWereReadLast() throws Exception {
        Path logs = Files.createDirectories(dir.resolve("works"));
        Path db = logs.resolve("chat-ui-iolog-28010.mv.db");
        write(db, "QUESTION: どうなりましたか？\nANSWER: まだ学習の途中です。");
        long modified = Files.getLastModifiedTime(db).toMillis();
        ConversationLogs conversations = new ConversationLogs(logs);
        List<ConversationLogs.Said> first = conversations.newTexts();
        assertEquals(2, first.size());
        assertEquals("どうなりましたか？", first.get(0).text());
        // the file itself was not opened: not changed, and no lock or trace file next to it
        assertEquals(modified, Files.getLastModifiedTime(db).toMillis());
        try (var left = Files.list(logs)) {
            assertEquals(List.of(db), left.toList());
        }
        // unchanged: nothing more
        assertEquals(0, conversations.newTexts().size());
        // a turn more, and the same turn in another file (the merged one): only the new turn
        write(db, "QUESTION: 続きをお願いします\nANSWER: はい。");
        write(logs.resolve("chat-ui-iolog-all.mv.db"), "QUESTION: どうなりましたか？\nANSWER: まだ学習の途中です。");
        List<ConversationLogs.Said> next = conversations.newTexts();
        assertEquals(2, next.size());
        assertEquals("続きをお願いします", next.get(0).text());
        // after a restart, with the state saved, nothing is read again
        Path state = dir.resolve("prediction-logs.tsv");
        conversations.saveState(state);
        ConversationLogs again = new ConversationLogs(logs);
        again.loadState(state);
        assertTrue(again.newTexts().isEmpty());
        // without the logs, nothing, and no error
        assertTrue(new ConversationLogs(dir.resolve("gone")).newTexts().isEmpty());
    }
}
