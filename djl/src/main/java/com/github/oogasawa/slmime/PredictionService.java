package com.github.oogasawa.slmime;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.pojoactor.core.ActorSystem;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.logging.Logger;

/**
 * The predictions of the server ({@code /api/predict}): the texts of the conversation logs and those the user
 * commits, learned by {@link Predictions}.
 *
 * <p>Off unless both {@code -Dpredict.logs=DIR} (the directory under which the {@code chat-ui-iolog-*.mv.db} files
 * are, see {@link ConversationLogs}) and {@code -Dpredict.dict=PATH} (the {@code system.dic} of SudachiDict) are
 * given: the conversation logs hold what the user wrote, and are read only when the user says where they are.
 * The logs are read at start and every {@code -Dpredict.refresh.minutes} minutes (default 60) after, on a virtual
 * thread of its own, so that the server answers while they are read.</p>
 */
final class PredictionService {

    private static final Logger log = Logger.getLogger(PredictionService.class.getName());

    /** What the user wrote counts more than what a model answered, and what the user commits most. */
    static final double USER_WEIGHT = 2;
    static final double MODEL_WEIGHT = 1;
    static final double COMMITTED_WEIGHT = 3;

    private final ActorRef<Readings> readings;
    private final ActorRef<Predictions> predictions;

    private PredictionService(ActorRef<Readings> readings, ActorRef<Predictions> predictions) {
        this.readings = readings;
        this.predictions = predictions;
    }

    /** The service the system properties ask for, or {@code null} when predictions are off. */
    static PredictionService fromProperties(ActorSystem system) {
        String logs = System.getProperty("predict.logs", "");
        String dict = System.getProperty("predict.dict", "");
        if (logs.isBlank() || dict.isBlank()) {
            return null;
        }
        if (!Files.isDirectory(Paths.get(logs)) || !Files.isRegularFile(Paths.get(dict))) {
            log.warning("predictions are off: " + logs + " is not a directory or " + dict + " is not a file");
            return null;
        }
        try {
            PredictionService service = new PredictionService(
                    system.actorOf("readings", new Readings(Paths.get(dict))),
                    system.actorOf("predictions", new Predictions()));
            long minutes = Long.getLong("predict.refresh.minutes", 60);
            ConversationLogs conversations = new ConversationLogs(Paths.get(logs));
            Thread.ofVirtual().name("prediction-logs").start(() -> service.keepReading(conversations, minutes));
            return service;
        } catch (Exception e) {
            log.warning("predictions are off: " + e.getMessage());
            return null;
        }
    }

    private void keepReading(ConversationLogs conversations, long minutes) {
        while (true) {
            long start = System.currentTimeMillis();
            List<ConversationLogs.Said> said = conversations.newTexts();
            for (ConversationLogs.Said s : said) {
                learn(s.text(), s.byUser() ? USER_WEIGHT : MODEL_WEIGHT, s.when());
            }
            int size = Actors.ask(predictions, Predictions::size);
            log.info("predictions: learned " + said.size() + " texts of the conversation logs in "
                    + (System.currentTimeMillis() - start) + " ms, " + size + " kept");
            try {
                Thread.sleep(minutes * 60_000);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private void learn(String text, double weight, long when) {
        List<Readings.Word> words = Actors.ask(readings, r -> r.words(text));
        predictions.tell(p -> p.learn(words, weight, when));
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
                    Thread.ofVirtual().start(() -> learn(text, COMMITTED_WEIGHT, System.currentTimeMillis()));
                }
            }
        } catch (RuntimeException e) {
            // A body that is not what the engine sends teaches nothing.
        }
    }
}
