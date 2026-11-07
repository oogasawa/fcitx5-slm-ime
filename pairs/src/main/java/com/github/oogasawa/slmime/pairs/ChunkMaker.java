package com.github.oogasawa.slmime.pairs;

import com.scivicslab.pojoactor.core.ActorRef;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * Makes the pairs of one chunk after another, with its own connection to the language model. The steps of a chunk are
 * those of the workflow kana-kanji-pairs.yaml that made the pairs of the releases: for every page, the language model
 * cuts its sentences into phrasal units (bunsetsu), then writes the reading of every unit; a page whose step fails is
 * skipped. The pairs are written to a file of their own and get their name only when the chunk is done and gave at
 * least one pair, so that a chunk cut short is made again.
 */
public class ChunkMaker {

    private static final int GIVE_UP_AFTER = 3;  // failures in a row: the server is not answering

    private final String url;
    private final String model;
    private final Path outDir;
    private final String tag;

    public ChunkMaker(String url, String model, Path outDir, String tag) {
        this.url = url;
        this.model = model;
        this.outDir = outDir;
        this.tag = tag;
    }

    /** Takes chunks from the list until it is empty, or until three chunks in a row failed; the chunks it made. */
    public int drain(ActorRef<ChunkList> chunks) {
        int made = 0;
        int failedInARow = 0;
        log("start " + url + " " + model);
        while (failedInARow < GIVE_UP_AFTER) {
            Optional<Path> next = chunks.ask(ChunkList::next).join();
            if (next.isEmpty()) {
                break;
            }
            if (make(next.get())) {
                made++;
                failedInARow = 0;
            } else {
                failedInARow++;
            }
        }
        log(failedInARow < GIVE_UP_AFTER ? "end made=" + made : "abort: " + GIVE_UP_AFTER + " chunks in a row failed");
        return made;
    }

    /** The pairs of one chunk; false when none were made. */
    public boolean make(Path chunk) {
        Path out = MakePairs.pairsOf(chunk, outDir);
        Path part = out.resolveSibling(out.getFileName() + "." + tag + ".tmp");
        log("START " + chunk.getFileName());
        try {
            Files.deleteIfExists(part);
            boolean whole = pairs(chunk, part);
            long lines;
            try (var stream = Files.lines(part)) {
                lines = stream.count();
            }
            if (whole && lines > 1) {
                Files.move(part, out, StandardCopyOption.ATOMIC_MOVE);
                log("OK " + chunk.getFileName() + " rows=" + lines);
                return true;
            }
            Files.deleteIfExists(part);
            log("FAIL " + chunk.getFileName() + " lines=" + lines);
        } catch (IOException | RuntimeException e) {
            log("FAIL " + chunk.getFileName() + " " + e);
            try {
                Files.deleteIfExists(part);
            } catch (IOException ignored) {
                // left for the next run, which writes it again
            }
        }
        return false;
    }

    // The workflow's states, in order. False where the workflow ends at its catch-all transition.
    private boolean pairs(Path chunk, Path part) {
        VllmClient llm = new VllmClient(url, model);
        OcrPages pages = new OcrPages();
        PairWriter writer = new PairWriter();
        if (!pages.loadFile(chunk.toString()).isSuccess() || !writer.openOutput(part.toString()).isSuccess()) {
            writer.closeOutput();
            return false;
        }
        try {
            while (pages.nextPage().isSuccess()) {
                ActionResult text = pages.getPageText();
                if (!text.isSuccess()) {
                    return false;
                }
                ActionResult segmented = llm.segment(text.getResult());
                if (!segmented.isSuccess()) {
                    continue;  // step 1 failed: the page is skipped
                }
                ActionResult info = pages.getPageInfo();
                if (!info.isSuccess() || !writer.setPageInfo(info.getResult()).isSuccess()) {
                    return false;
                }
                ActionResult readings = llm.toHiragana(segmented.getResult());
                if (readings.isSuccess()) {
                    writer.writePairs(readings.getResult());  // step 2 failed: the page is skipped
                }
            }
            return true;
        } finally {
            writer.closeOutput();
        }
    }

    private void log(String message) {
        System.out.println("[" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + "] ["
                + tag + "] " + message);
    }
}
