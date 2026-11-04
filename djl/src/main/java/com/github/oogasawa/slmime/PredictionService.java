package com.github.oogasawa.slmime;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.pojoactor.core.ActorSystem;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.logging.Logger;

/**
 * The predictions of the server ({@code /api/predict}): the texts the user commits and those of the conversation
 * logs of chat-ui, learned by {@link Predictions}.
 *
 * <p>The texts are kept in a file of the server, {@code -Dpredict.file} (default
 * {@code $XDG_DATA_HOME/slm-ime/predictions.tsv}), read at start: once learned, a text does not depend on where it
 * came from. The conversation logs, under {@code -Dpredict.logs=DIR} if given, give the first texts and, whenever
 * they are there and have grown, what the user and the model wrote since ({@link ConversationLogs}); without them
 * the predictions go on with the texts kept and those the user commits. Every {@code -Dpredict.refresh.minutes}
 * minutes (default 60), on a virtual thread of its own, the logs are read, the lightest texts are dropped when more
 * than {@code -Dpredict.most} (default 200,000) are kept, and the file is written. Off unless
 * {@code -Dpredict.dict=PATH} (the {@code system.dic} of SudachiDict, which gives the readings) is given.</p>
 */
final class PredictionService {

    private static final Logger log = Logger.getLogger(PredictionService.class.getName());

    /** Each time a text is learned it counts the same, whoever wrote it: the user commits wrong conversions too. */
    static final double WEIGHT = 1;

    private final ActorRef<Readings> readings;
    private final ActorRef<Predictions> predictions;
    private final Path file;
    private final int most;

    private PredictionService(ActorRef<Readings> readings, ActorRef<Predictions> predictions, Path file, int most) {
        this.readings = readings;
        this.predictions = predictions;
        this.file = file;
        this.most = most;
    }

    /** The service the system properties ask for, or {@code null} when predictions are off. */
    static PredictionService fromProperties(ActorSystem system) {
        String dict = System.getProperty("predict.dict", "");
        if (dict.isBlank()) {
            return null;
        }
        if (!Files.isRegularFile(Paths.get(dict))) {
            log.warning("predictions are off: " + dict + " is not a file");
            return null;
        }
        try {
            Path file = Paths.get(System.getProperty("predict.file", dataDir().resolve("predictions.tsv").toString()));
            PredictionService service = new PredictionService(
                    system.actorOf("readings", new Readings(Paths.get(dict))),
                    system.actorOf("predictions", Predictions.load(file)),
                    file, Integer.getInteger("predict.most", 200_000));
            String logs = System.getProperty("predict.logs", "");
            ConversationLogs conversations = logs.isBlank() ? null : new ConversationLogs(Paths.get(logs));
            Path state = file.resolveSibling("prediction-logs.tsv");
            if (conversations != null) {
                conversations.loadState(state);
            }
            long minutes = Long.getLong("predict.refresh.minutes", 60);
            Thread.ofVirtual().name("prediction-logs")
                    .start(() -> service.keepReading(conversations, state, minutes));
            Runtime.getRuntime().addShutdownHook(new Thread(service::save));
            return service;
        } catch (Exception e) {
            log.warning("predictions are off: " + e.getMessage());
            return null;
        }
    }

    private static Path dataDir() {
        String data = System.getenv("XDG_DATA_HOME");
        if (data == null || data.isBlank()) {
            data = System.getProperty("user.home") + "/.local/share";
        }
        return Paths.get(data, "slm-ime");
    }

    private void keepReading(ConversationLogs conversations, Path state, long minutes) {
        while (true) {
            long start = System.currentTimeMillis();
            int learned = 0;
            if (conversations != null) {
                List<ConversationLogs.Said> said = conversations.newTexts();
                for (ConversationLogs.Said s : said) {
                    learn(s.text(), s.when());
                }
                learned = said.size();
                try {
                    conversations.saveState(state);
                } catch (IOException e) {
                    log.warning("cannot write " + state + ": " + e.getMessage());
                }
            }
            int size = save();
            log.info("predictions: learned " + learned + " texts of the conversation logs in "
                    + (System.currentTimeMillis() - start) + " ms, " + size + " kept");
            try {
                Thread.sleep(minutes * 60_000);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    /** Drops the lightest texts if too many are kept, and writes the file; how many are kept. */
    private int save() {
        return Actors.ask(predictions, p -> {
            p.keepAtMost(most, System.currentTimeMillis());
            try {
                p.save(file);
            } catch (IOException e) {
                log.warning("cannot write " + file + ": " + e.getMessage());
            }
            return p.size();
        });
    }

    private void learn(String text, long when) {
        List<Readings.Word> words = Actors.ask(readings, r -> r.words(text));
        predictions.tell(p -> p.learn(words, WEIGHT, when));
    }

    /** {@code [{"candidate":..},..]}, the predictions for the reading typed so far. */
    String predict(String typed, int limit) {
        JsonArray out = new JsonArray();
        for (String text : Actors.ask(predictions, p -> p.predict(typed, limit, System.currentTimeMillis()))) {
            JsonObject candidate = new JsonObject();
            candidate.addProperty("candidate", text);
            out.add(candidate);
        }
        return out.toString();
    }

    /** Learns the text the engine reports as committed ({@code "committed"} of {@code /api/record}). */
    void learnCommitted(String recordBody) {
        try {
            JsonObject request = JsonParser.parseString(recordBody).getAsJsonObject();
            if (request.has("committed") && request.get("committed").isJsonPrimitive()) {
                String text = request.get("committed").getAsString();
                if (!text.isBlank()) {
                    Thread.ofVirtual().start(() -> learn(text, System.currentTimeMillis()));
                }
            }
        } catch (RuntimeException e) {
            // A body that is not what the engine sends teaches nothing.
        }
    }
}
