package com.github.oogasawa.slmime;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Where other programs put texts for the predictions to learn: a directory, by default
 * {@code $XDG_DATA_HOME/slm-ime/learn}, of files {@code *.jsonl} with one text per line,
 * {@code {"text": "...", "time": <milliseconds since 1970>}} ({@code time} may be left out: then the time the file
 * is read). The server reads the files in the order of their names and deletes each one it has read; a line that
 * is not such an object is skipped.
 *
 * <p>A program writes a file under another name (such as {@code .jsonl.tmp}) and then renames it, so that the
 * server never reads half a file. The server knows nothing of where the texts come from.</p>
 */
final class LearnInbox {

    private static final Logger log = Logger.getLogger(LearnInbox.class.getName());

    /** A text to learn, and when it was written. */
    record Text(String text, long when) {
    }

    private final Path dir;

    LearnInbox(Path dir) {
        this.dir = dir;
    }

    /** The texts of the files in the directory, which are deleted. */
    List<Text> take() {
        List<Text> texts = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return texts;
        }
        List<Path> files;
        try (Stream<Path> found = Files.list(dir)) {
            files = found.filter(p -> p.getFileName().toString().endsWith(".jsonl") && Files.isRegularFile(p))
                    .sorted().toList();
        } catch (IOException e) {
            log.warning("cannot list " + dir + ": " + e.getMessage());
            return texts;
        }
        for (Path file : files) {
            try {
                long now = System.currentTimeMillis();
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    Text t = parse(line, now);
                    if (t != null) {
                        texts.add(t);
                    }
                }
                Files.delete(file);
            } catch (IOException e) {
                log.warning("cannot read " + file + ": " + e.getMessage());
            }
        }
        return texts;
    }

    static Text parse(String line, long now) {
        if (line.isBlank()) {
            return null;
        }
        try {
            JsonObject o = JsonParser.parseString(line).getAsJsonObject();
            if (!o.has("text") || !o.get("text").isJsonPrimitive()) {
                return null;
            }
            String text = o.get("text").getAsString();
            long when = o.has("time") && o.get("time").isJsonPrimitive() ? o.get("time").getAsLong() : now;
            return text.isBlank() ? null : new Text(text, when);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
