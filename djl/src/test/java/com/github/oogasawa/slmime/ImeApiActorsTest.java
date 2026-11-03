package com.github.oogasawa.slmime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.scivicslab.pojoactor.core.ActorRef;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The history as an actor: the requests do not wait for learning, and learning loses nothing. */
class ImeApiActorsTest {

    private static final String BAI = "{\"corrections\":[{\"reading\":\"ばい\",\"chosen\":\"倍\"}]}";

    @Test
    void recordAnswersAtOnceWhileTheHistoryIsBusy() {
        UserHistory plain = UserHistory.inMemory();
        ActorRef<UserHistory> history = new ActorRef<>("history", plain);
        ImeApi api = new ImeApi((kana, context) -> kana, CandidateSource.NONE, history, ImeApi.Scorer.NONE, 0);
        history.tell(h -> {
            try {
                Thread.sleep(2000);  // the history is busy, as with a slow disk
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        long start = System.nanoTime();
        assertEquals("{}", api.record(BAI));
        long ms = (System.nanoTime() - start) / 1_000_000;
        assertTrue(ms < 500, "record waited " + ms + " ms for the history");
        api.awaitLearning();
        assertEquals("倍", plain.preferred("ばい").orElse(""));
    }

    @Test
    void choicesRecordedFromManyThreadsAreAllLearnedAndKept(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("history.tsv");
        UserHistory plain = UserHistory.inFile(file);
        ImeApi api = new ImeApi((kana, context) -> kana, CandidateSource.NONE, new ActorRef<>("history", plain),
                ImeApi.Scorer.NONE, 0);
        ExecutorService threads = Executors.newFixedThreadPool(8);
        List<Future<?>> done = new ArrayList<>();
        for (int t = 0; t < 8; t++) {
            done.add(threads.submit(() -> {
                for (int i = 0; i < 50; i++) {
                    api.record(BAI);
                    api.segmentConvert("{\"input\":\"ばいは\"}");
                }
                return null;
            }));
        }
        for (Future<?> f : done) {
            f.get();
        }
        threads.shutdown();
        api.awaitLearning();
        assertEquals(400, Files.readAllLines(file).size());
        UserHistory reread = UserHistory.inFile(file);
        assertEquals("倍", reread.preferred("ばい").orElse(""));
    }
}
