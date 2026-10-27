package com.github.oogasawa.slmime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UserHistoryTest {

    @Test
    void aLearnedChoiceIsReturnedForTheSameReading() {
        UserHistory history = UserHistory.inMemory();
        assertEquals(Optional.empty(), history.preferred("ばい"));
        history.learn("ばい", "倍");
        assertEquals(Optional.of("倍"), history.preferred("ばい"));
        assertEquals(Optional.empty(), history.preferred("ばいは"));
    }

    @Test
    void theChoiceMadeMostOftenWinsAndTheLatestWinsATie() {
        UserHistory history = UserHistory.inMemory();
        history.learn("こうせい", "構成");
        history.learn("こうせい", "公正");
        assertEquals(Optional.of("公正"), history.preferred("こうせい"));
        history.learn("こうせい", "構成");
        assertEquals(Optional.of("構成"), history.preferred("こうせい"));
    }

    @Test
    void theStretchesOfAReadingThatAreEarlierChoicesAreFoundLongestFirstWithoutOverlap() {
        UserHistory history = UserHistory.inMemory();
        history.learn("でぷろい", "デプロイ");
        history.learn("ぷろいで", "プロイで");
        history.learn("されたかな", "されたかな");
        List<UserHistory.Match> matches = history.matchesIn("でぷろいされたかな", 3);
        assertEquals(List.of(new UserHistory.Match(0, 4, "デプロイ"), new UserHistory.Match(4, 9, "されたかな")), matches);
    }

    @Test
    void aReadingShorterThanTheLimitIsNotFound() {
        UserHistory history = UserHistory.inMemory();
        history.learn("め", "目");
        history.learn("ばい", "倍");
        assertTrue(history.matchesIn("はじめばいばい", 3).isEmpty());
    }

    @Test
    void theHistoryIsKeptInAFileAndReadBackAfterARestart(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("history.tsv");
        UserHistory first = UserHistory.inFile(file);
        first.learn("ばい", "倍");
        UserHistory second = UserHistory.inFile(file);
        assertEquals(Optional.of("倍"), second.preferred("ばい"));
        assertEquals(List.of("ばい\t倍"), Files.readAllLines(file));
    }

    @Test
    void emptyTextAndTextWithTabsOrNewlinesIsNotLearned() {
        UserHistory history = UserHistory.inMemory();
        history.learn("", "倍");
        history.learn("ばい", "");
        history.learn("ば\tい", "倍");
        history.learn("ばい", "倍\n");
        assertEquals(0, history.size());
    }

    @Test
    void recordLearnsOnlyTheCorrectionsAndThenTheChoiceComesFirst() throws Exception {
        UserHistory history = UserHistory.inMemory();
        ImeApi api = new ImeApi((kana, context) -> "バイ|は", (reading, limit) -> List.of("倍", "杯"), history);
        assertEquals("{}", api.record("{\"input\":\"ばいは\",\"output\":\"倍は\","
                + "\"corrections\":[{\"reading\":\"ばい\",\"chosen\":\"倍\"}]}"));
        JsonObject response = JsonParser.parseString(api.segmentConvert("{\"input\":\"ばいは\"}")).getAsJsonObject();
        var first = response.getAsJsonArray("segments").get(0).getAsJsonObject().getAsJsonArray("candidates");
        assertEquals(List.of("倍", "バイ", "杯"), first.asList().stream().map(e -> e.getAsString()).toList());
        assertTrue(history.size() == 1);
    }

    @Test
    void aRecordWithoutCorrectionsOrWithBrokenJsonChangesNothing() {
        UserHistory history = UserHistory.inMemory();
        ImeApi api = new ImeApi((kana, context) -> kana, CandidateSource.NONE, history);
        assertEquals("{}", api.record("{\"input\":\"あ\",\"output\":\"亜\"}"));
        assertEquals("{}", api.record("not json"));
        assertEquals(0, history.size());
    }
}
