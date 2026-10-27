package com.github.oogasawa.slmime;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The requests that the fcitx5 engine ({@code fcitx5-llm-ime}) sends to its conversion daemon, answered with
 * the kana-kanji model.
 *
 * <p>The engine asks for the conversion of a typed hiragana string in four ways. It asks for the typed text
 * cut into bunsetsu, each with its reading and its candidates ({@link #segmentConvert}); for the candidates of
 * one bunsetsu whose reading it knows ({@link #segmentCandidates}); and for one candidate for the whole text
 * ({@link #convert}). It also reports what the user chose ({@link #record}) and asks for predictions
 * ({@link #predict}, {@link #continuation}); the model has nothing to answer to those two, so they get empty
 * answers.</p>
 *
 * <p>Every method takes the request body and returns the response body, both JSON text. The model is reached
 * through a {@link Converter}, so this class needs neither the model nor a server to be tested.</p>
 */
public final class ImeApi {

    /** Converts typed hiragana to kanji-kana text with {@code |} between bunsetsu, given the text before the cursor. */
    @FunctionalInterface
    public interface Converter {
        String convert(String kana, String context) throws Exception;
    }

    /** The user can pick among at least this many candidates for a bunsetsu, whatever the engine asked for. */
    private static final int AT_LEAST_CANDIDATES = 8;

    private final Converter converter;
    private final CandidateSource alternatives;
    private final UserHistory history;

    /**
     * @param converter the model
     * @param alternatives where the candidates after the model's own come from
     */
    public ImeApi(Converter converter, CandidateSource alternatives) {
        this(converter, alternatives, UserHistory.inMemory());
    }

    /**
     * @param converter the model
     * @param alternatives where the candidates after the model's own come from
     * @param history the candidates the user chose before, which come before the model's own
     */
    public ImeApi(Converter converter, CandidateSource alternatives, UserHistory history) {
        this.converter = converter;
        this.alternatives = alternatives;
        this.history = history;
    }

    /** {@code {"input":..,"context":..}} to {@code {"segments":[{"reading":..,"candidates":[..]},..]}}. */
    public String segmentConvert(String body) throws Exception {
        JsonObject request = JsonParser.parseString(body).getAsJsonObject();
        String input = text(request, "input");
        HistorySubstitution.Result answer = HistorySubstitution.apply(input,
                segmentsOf(input, converter.convert(input, text(request, "context"))), history);
        JsonArray array = new JsonArray();
        for (int i = 0; i < answer.texts().size(); i++) {
            JsonObject segment = new JsonObject();
            segment.addProperty("reading", answer.readings().get(i));
            segment.add("candidates", strings(withAlternatives(answer.texts().get(i), answer.originals().get(i),
                    answer.readings().get(i), wanted(request))));
            array.add(segment);
        }
        JsonObject response = new JsonObject();
        response.add("segments", array);
        return response.toString();
    }

    /** {@code {"input":reading,"context":..}} to {@code {"candidates":[..]}}, the candidates of one bunsetsu. */
    public String segmentCandidates(String body) throws Exception {
        JsonObject request = JsonParser.parseString(body).getAsJsonObject();
        String input = text(request, "input");
        HistorySubstitution.Result answer = HistorySubstitution.apply(input,
                segmentsOf(input, converter.convert(input, text(request, "context"))), history);
        String own = String.join("", answer.texts());
        JsonObject response = new JsonObject();
        response.add("candidates", strings(withAlternatives(own, String.join("", answer.originals()), input,
                wanted(request))));
        response.addProperty("output", own);
        return response.toString();
    }

    /** {@code {"input":..,"context":..}} to {@code {"candidates":[..]}}, the candidates for the whole text. */
    public String convert(String body) throws Exception {
        return candidates(body);
    }

    /**
     * The choice of the user. {@code {"corrections":[{"reading":..,"chosen":..},..]}} lists the bunsetsu for
     * which the user took another candidate than the first; each is kept in the history. The model does not
     * learn from it. Anything else in the body, or a body that cannot be read, changes nothing.
     */
    public String record(String body) {
        try {
            JsonObject request = JsonParser.parseString(body).getAsJsonObject();
            if (request.has("corrections") && request.get("corrections").isJsonArray()) {
                for (var element : request.getAsJsonArray("corrections")) {
                    JsonObject correction = element.getAsJsonObject();
                    history.learn(text(correction, "reading"), text(correction, "chosen"));
                }
            }
        } catch (RuntimeException e) {
            // A body that is not what the engine sends is ignored.
        }
        return "{}";
    }

    /** Prefix predictions; none. */
    public String predict() {
        return "[]";
    }

    /** Continuations of the text before the cursor; none. */
    public String continuation(String body) {
        JsonObject response = new JsonObject();
        response.add("candidates", new JsonArray());
        return response.toString();
    }

    private String candidates(String body) throws Exception {
        JsonObject request = JsonParser.parseString(body).getAsJsonObject();
        String input = text(request, "input");
        String joined = String.join("", HistorySubstitution.apply(input,
                segmentsOf(input, converter.convert(input, text(request, "context"))), history).texts());
        JsonObject response = new JsonObject();
        response.add("candidates", strings(List.of(joined)));
        response.addProperty("output", joined);
        return response.toString();
    }

    /** What the user chose before for the whole reading first, then the model's answer with the user's earlier
     *  choices put in, then the model's answer as it was, then the alternatives from the dictionary, without
     *  duplicates. */
    private List<String> withAlternatives(String own, String original, String reading, int wanted) {
        Set<String> all = new LinkedHashSet<>();
        history.preferred(reading).ifPresent(all::add);
        all.add(own);
        all.add(original);
        all.addAll(alternatives.candidates(reading, wanted));
        return new ArrayList<>(all);
    }

    private static int wanted(JsonObject request) {
        int asked = request.has("n") && request.get("n").isJsonPrimitive() ? request.get("n").getAsInt() : 0;
        return Math.max(asked, AT_LEAST_CANDIDATES);
    }

    private static List<String> segmentsOf(String input, String modelOutput) {
        List<String> segments = new ArrayList<>();
        if (modelOutput != null) {
            for (String part : modelOutput.split("\\|", -1)) {
                if (!part.isEmpty()) {
                    segments.add(part);
                }
            }
        }
        if (segments.isEmpty()) {
            segments.add(input);
        }
        return segments;
    }

    private static String text(JsonObject object, String name) {
        return object.has(name) && !object.get(name).isJsonNull() ? object.get(name).getAsString() : "";
    }

    private static JsonArray strings(List<String> values) {
        JsonArray array = new JsonArray();
        values.forEach(array::add);
        return array;
    }
}
