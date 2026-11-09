package com.github.oogasawa.slmime;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import java.util.stream.Stream;

import com.scivicslab.pojoactor.core.ActorRef;

/**
 * Where a reviewer of the history, a program of its own, meets the server: a directory, by default
 * {@code $XDG_DATA_HOME/slm-ime/review} ({@code -Dhistory.review}; empty for none).
 *
 * <p>The server writes there, in {@code choices.jsonl}, one line for every choice the history learns, with the whole
 * text it was chosen in: {@code {"reading":..,"chosen":..,"input":<reading of the text>,"output":<the text as
 * committed>,"time":<milliseconds since 1970>}}. A reviewer renames the file before it reads it, so the server starts
 * a new one.</p>
 *
 * <p>The reviewer puts the choices it suspects to be slips (a candidate taken by mistake) in files
 * {@code suspects/*.jsonl}, one {@code {"reading":..,"chosen":..}} per line, written under another name and then
 * renamed. At start and every {@code -Dhistory.review.minutes} minutes (default 60) the server reads them, deletes
 * them, and forgets each choice that the user made only once ({@link UserHistory#forgetIfOnce}): one made again, or
 * made again later, stays. The server knows nothing of how the reviewer judges.</p>
 */
final class HistoryReview {

    private static final Logger log = Logger.getLogger(HistoryReview.class.getName());

    /** A choice of the history: the reading of a bunsetsu and the text chosen for it. */
    record Choice(String reading, String chosen) {
    }

    private final Path dir;

    HistoryReview(Path dir) {
        this.dir = dir;
    }

    /**
     * The review the system properties ask for, reading the suspects into {@code history} on a virtual thread of its
     * own, or {@code null} when the history is not added to or the review is off.
     */
    static HistoryReview fromProperties(ActorRef<UserHistory> history) {
        if (!HistoryMode.fromProperty().used()) {
            return null;
        }
        String path = System.getProperty("history.review", defaultDir().toString());
        if (path.isBlank()) {
            return null;
        }
        HistoryReview review = new HistoryReview(Paths.get(path));
        long minutes = Long.getLong("history.review.minutes", 60);
        Thread.ofVirtual().name("history-review").start(() -> review.keepReading(history, minutes));
        return review;
    }

    private static Path defaultDir() {
        String data = System.getenv("XDG_DATA_HOME");
        if (data == null || data.isBlank()) {
            data = System.getProperty("user.home") + "/.local/share";
        }
        return Paths.get(data, "slm-ime", "review");
    }

    /**
     * Notes the choices of a {@code /api/record} body ({@code {"input":..,"output":..,"corrections":[{"reading":..,
     * "chosen":..},..]}}) in {@code choices.jsonl}. A body that cannot be read, or a correction without a reading or
     * a chosen text, is skipped.
     */
    void noteRecord(String body) {
        StringBuilder lines = new StringBuilder();
        try {
            JsonObject request = JsonParser.parseString(body).getAsJsonObject();
            if (!request.has("corrections") || !request.get("corrections").isJsonArray()) {
                return;
            }
            String input = text(request, "input");
            String output = text(request, "output");
            long now = System.currentTimeMillis();
            for (JsonElement element : request.getAsJsonArray("corrections")) {
                JsonObject correction = element.getAsJsonObject();
                String reading = text(correction, "reading");
                String chosen = text(correction, "chosen");
                if (reading.isEmpty() || chosen.isEmpty()) {
                    continue;
                }
                JsonObject line = new JsonObject();
                line.addProperty("reading", reading);
                line.addProperty("chosen", chosen);
                line.addProperty("input", input);
                line.addProperty("output", output);
                line.addProperty("time", now);
                lines.append(line).append('\n');
            }
        } catch (RuntimeException e) {
            return;   // a body that is not what the engine sends
        }
        if (lines.isEmpty()) {
            return;
        }
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("choices.jsonl"), lines, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warning("cannot write the choices to review in " + dir + ": " + e.getMessage());
        }
    }

    /** The suspected choices of the files in {@code suspects/}, which are deleted. */
    List<Choice> takeSuspects() {
        List<Choice> suspects = new ArrayList<>();
        Path inbox = dir.resolve("suspects");
        if (!Files.isDirectory(inbox)) {
            return suspects;
        }
        List<Path> files;
        try (Stream<Path> found = Files.list(inbox)) {
            files = found.filter(p -> p.getFileName().toString().endsWith(".jsonl") && Files.isRegularFile(p))
                    .sorted().toList();
        } catch (IOException e) {
            log.warning("cannot list " + inbox + ": " + e.getMessage());
            return suspects;
        }
        for (Path file : files) {
            try {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    Choice c = parseChoice(line);
                    if (c != null) {
                        suspects.add(c);
                    }
                }
                Files.delete(file);
            } catch (IOException e) {
                log.warning("cannot read " + file + ": " + e.getMessage());
            }
        }
        return suspects;
    }

    static Choice parseChoice(String line) {
        if (line.isBlank()) {
            return null;
        }
        try {
            JsonObject o = JsonParser.parseString(line).getAsJsonObject();
            String reading = text(o, "reading");
            String chosen = text(o, "chosen");
            return reading.isEmpty() || chosen.isEmpty() ? null : new Choice(reading, chosen);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void keepReading(ActorRef<UserHistory> history, long minutes) {
        while (true) {
            List<Choice> suspects = takeSuspects();
            if (!suspects.isEmpty()) {
                int forgotten = Actors.ask(history, h -> {
                    int n = 0;
                    for (Choice c : suspects) {
                        n += h.forgetIfOnce(c.reading(), c.chosen()) ? 1 : 0;
                    }
                    return n;
                });
                log.info("history review: " + suspects.size() + " suspected choices, " + forgotten
                        + " made only once forgotten");
            }
            try {
                Thread.sleep(minutes * 60_000);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private static String text(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : "";
    }
}
