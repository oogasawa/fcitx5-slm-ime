package com.github.oogasawa.slmime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HistoryReviewTest {

    @Test
    void aChoiceMadeOnceIsForgotten_oneMadeTwiceStays() {
        UserHistory history = UserHistory.inMemory();
        history.learn("もう", "望");
        history.learn("いる", "要る");
        history.learn("いる", "要る");

        assertTrue(history.forgetIfOnce("もう", "望"));
        assertFalse(history.forgetIfOnce("いる", "要る"));

        assertEquals(Optional.empty(), history.preferred("もう"));
        assertEquals(Optional.of("要る"), history.preferred("いる"));
    }

    @Test
    void aForgottenChoiceComesBackWhenTheUserMakesItAgain() {
        UserHistory history = UserHistory.inMemory();
        history.learn("いましんやなので", "今深夜なので");
        history.forgetIfOnce("いましんやなので", "今深夜なので");

        history.learn("いましんやなので", "今深夜なので");

        assertEquals(Optional.of("今深夜なので"), history.preferred("いましんやなので"));
    }

    @Test
    void forgettingLeavesTheOtherChoicesOfTheReadingAndTheFile(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("user_history.tsv");
        UserHistory history = UserHistory.inFile(file);
        history.learn("いると", "要ると");
        history.learn("いると", "要ると");
        history.learn("いると", "居ると");

        history.forgetIfOnce("いると", "居ると");

        assertEquals(Optional.of("要ると"), UserHistory.inFile(file).preferred("いると"));
        assertFalse(Files.readString(file).contains("居ると"));
    }

    @Test
    void anUnknownChoiceIsNotForgotten() {
        assertFalse(UserHistory.inMemory().forgetIfOnce("どこ", "何処"));
    }

    @Test
    void theChoicesOfARecordAreNotedWithTheirText(@TempDir Path dir) throws Exception {
        HistoryReview review = new HistoryReview(dir);

        review.noteRecord("{\"input\":\"はいどんどんいこう\",\"output\":\"はいどんどん行こう\","
                + "\"corrections\":[{\"reading\":\"いこう\",\"chosen\":\"行こう\",\"turnedDown\":\"以降\"},"
                + "{\"reading\":\"\",\"chosen\":\"x\"}]}");
        review.noteRecord("{\"input\":\"あ\",\"output\":\"あ\"}");   // no corrections: nothing noted
        review.noteRecord("not json");

        List<String> lines = Files.readAllLines(dir.resolve("choices.jsonl"), StandardCharsets.UTF_8);
        assertEquals(1, lines.size());
        JsonObject line = JsonParser.parseString(lines.get(0)).getAsJsonObject();
        assertEquals("いこう", line.get("reading").getAsString());
        assertEquals("行こう", line.get("chosen").getAsString());
        assertEquals("はいどんどんいこう", line.get("input").getAsString());
        assertEquals("はいどんどん行こう", line.get("output").getAsString());
        assertTrue(line.get("time").getAsLong() > 0);
    }

    @Test
    void theSuspectsAreReadFromTheirFilesWhichAreDeleted(@TempDir Path dir) throws Exception {
        Path inbox = Files.createDirectories(dir.resolve("suspects"));
        Files.writeString(inbox.resolve("a.jsonl"), "{\"reading\":\"もう\",\"chosen\":\"望\"}\nbroken\n\n"
                + "{\"reading\":\"こう\"}\n{\"reading\":\"し\",\"chosen\":\"四\"}\n");
        Files.writeString(inbox.resolve("b.jsonl.tmp"), "{\"reading\":\"そう\",\"chosen\":\"宗\"}\n");

        List<HistoryReview.Choice> suspects = new HistoryReview(dir).takeSuspects();

        assertEquals(List.of(new HistoryReview.Choice("もう", "望"), new HistoryReview.Choice("し", "四")), suspects);
        assertFalse(Files.exists(inbox.resolve("a.jsonl")));
        assertTrue(Files.exists(inbox.resolve("b.jsonl.tmp")));   // still being written: left for the next round
        assertEquals(List.of(), new HistoryReview(dir).takeSuspects());
    }
}
