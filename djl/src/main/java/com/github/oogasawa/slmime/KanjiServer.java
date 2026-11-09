package com.github.oogasawa.slmime;

import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.pojoactor.core.ActorSystem;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

/**
 * The conversion server of the fcitx5 engine: the HTTP requests of the engine, answered by {@link ImeApi} with the
 * kana-kanji model.
 *
 * <p>The parts that the requests share are actors, each taking its messages one at a time in the order they came:
 * the model ({@link KanjiModel}, whose predictors cannot be used by two threads at once), the history of the user's
 * choices ({@link UserHistory}) and the dictionary candidates of Anthy. The requests themselves run each on a
 * virtual thread of its own, so that learning a choice does not wait for a conversion.</p>
 *
 * GET /api/convert?kana=...
 * Response: {"kanji":"機械学習","segments":["機械学習"]}
 */
public class KanjiServer {

    private static final Logger log = Logger.getLogger(KanjiServer.class.getName());

    private final ActorSystem system = new ActorSystem("slm-ime-server");
    private final ActorRef<KanjiModel> model;
    private final ActorRef<UserHistory> history;
    private final ActorRef<CandidateSource> anthy;
    private final BunsetsuSegmenter segmenter = new BunsetsuSegmenter();
    /** The requests of the fcitx5 engine, answered with this model. */
    private final ImeApi ime;
    /** The predictions ({@code /api/predict}), or {@code null} when they are off (see {@link PredictionService}). */
    private final PredictionService predictions;
    /** Where a reviewer of the history meets the server, or {@code null} when it is off (see {@link HistoryReview}). */
    private final HistoryReview review;

    /** The candidates the user chose: {@code -Dhistory.file=PATH} (default {@code user_history.tsv} in the
     *  working directory); an empty value keeps the history in memory only. */
    private static UserHistory openHistory() {
        String path = System.getProperty("history.file", "user_history.tsv");
        return path.isEmpty() ? UserHistory.inMemory() : UserHistory.inFile(java.nio.file.Paths.get(path));
    }

    public KanjiServer(String modelDir, String tokenizerPath) throws Exception {
        model = system.actorOf("model", new KanjiModel(modelDir, tokenizerPath));
        history = system.actorOf("history", openHistory());
        anthy = system.actorOf("anthy", AnthyCandidates.loadOrNone(DictionaryLevel.fromProperty()));
        ime = new ImeApi(new ImeApi.Converter() {
                    @Override
                    public String convert(String kana, String context) {
                        return Actors.ask(model, m -> m.translate(kana, context));
                    }

                    @Override
                    public String convert(String kana, String context, List<DictionaryHints.Hint> choices) {
                        return Actors.ask(model, m -> m.translate(kana, context, choices));
                    }
                },
                (reading, limit) -> Actors.ask(anthy, a -> a.candidates(reading, limit)),
                history,
                KanjiModel.MOST_SCORED > 0 ? (reading, context, texts) -> Actors.ask(model, m -> m.scores(reading, context, texts))
                        : ImeApi.Scorer.NONE,
                KanjiModel.SOURCE_WEIGHT);
        predictions = PredictionService.fromProperties(system);
        review = HistoryReview.fromProperties(history);
    }

    public void startAndBlock(int port) throws IOException {
        // The text the user types goes through this server, so it listens on this machine only unless told otherwise.
        String bind = System.getProperty("bind.address", "127.0.0.1");
        // Only the user's own engine may use it: it sends the token written here (AccessGuard)
        AccessGuard guard = new AccessGuard(AccessGuard.writeNewToken(tokenFile()), port);
        HttpServer server = HttpServer.create(new InetSocketAddress(bind, port), 0);
        // Every request on a thread of its own: the requests wait for each other only where they share a part
        // (the model, the history, Anthy), each of which takes them one at a time.
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/api/convert", this::handleConvert).getFilters().add(guard);
        server.createContext("/api/health", ex -> respond(ex, 200, "{\"status\":\"ok\"}"));
        server.createContext("/api/segment-convert", ex -> answer(ex, ime::segmentConvert)).getFilters().add(guard);
        server.createContext("/api/segment-candidates", ex -> answer(ex, ime::segmentCandidates))
            .getFilters().add(guard);
        server.createContext("/api/record", ex -> answer(ex, body -> {
            if (predictions != null) {
                predictions.learnCommitted(body);
            }
            if (review != null) {
                review.noteRecord(body);
            }
            return ime.record(body);
        })).getFilters().add(guard);
        server.createContext("/api/continue", ex -> answer(ex, body -> predictions != null
                ? predictions.continuations(body) : ime.continuation(body))).getFilters().add(guard);
        server.createContext("/api/predict", this::handlePredict).getFilters().add(guard);
        server.start();
        log.info("kana-kanji-server listening on :" + port);
        // block forever
        try {
            Thread.currentThread().join();
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    /** {@code GET /api/predict?prefix=<reading typed so far>&limit=<n>}: {@code [{"candidate":..},..]}. */
    private void handlePredict(HttpExchange ex) throws IOException {
        String query = ex.getRequestURI().getRawQuery();
        String prefix = extractParam(query, "prefix");
        if (predictions == null || prefix == null || prefix.isBlank()) {
            respond(ex, 200, ime.predict());
            return;
        }
        int limit = 5;
        try {
            limit = Math.max(1, Math.min(20, Integer.parseInt(extractParam(query, "limit"))));
        } catch (RuntimeException e) {
            // the default
        }
        try {
            respond(ex, 200, predictions.predict(prefix, limit));
        } catch (Exception e) {
            log.warning("Predict error: " + e.getMessage());
            respond(ex, 200, "[]");
        }
    }

    private void handleConvert(HttpExchange ex) throws IOException {
        if ("POST".equals(ex.getRequestMethod())) {
            answer(ex, ime::convert); // the engine's JSON request
            return;
        }
        if (!"GET".equals(ex.getRequestMethod())) {
            respond(ex, 405, "{\"error\":\"method not allowed\"}");
            return;
        }
        String query = ex.getRequestURI().getRawQuery(); // extractParam decodes it; getQuery() would decode twice
        String kana = extractParam(query, "kana");
        if (kana == null || kana.isBlank()) {
            respond(ex, 400, "{\"error\":\"kana parameter required\"}");
            return;
        }
        try {
            long start = System.currentTimeMillis();
            String kanji = Actors.ask(model, m -> m.translate(kana));
            List<String> segments = segmenter.segment(kanji);
            long ms = System.currentTimeMillis() - start;
            String json = buildJson(String.join("", segments), segments, ms);
            respond(ex, 200, json);
        } catch (Exception e) {
            log.warning("Convert error: " + e.getMessage());
            respond(ex, 500, "{\"error\":\"" + escape(e.getMessage()) + "\"}");
        }
    }

    /**
     * The file of the token the engine must send: {@code -Dtoken.file=PATH}, by default
     * {@code $XDG_DATA_HOME/slm-ime/server-token}, where the engine reads it.
     */
    private static java.nio.file.Path tokenFile() {
        String path = System.getProperty("token.file");
        if (path != null && !path.isBlank()) {
            return Paths.get(path);
        }
        String data = System.getenv("XDG_DATA_HOME");
        if (data == null || data.isBlank()) {
            data = System.getProperty("user.home") + "/.local/share";
        }
        return Paths.get(data, "slm-ime", "server-token");
    }

    /** A request and a response that are both JSON text. */
    @FunctionalInterface
    private interface JsonHandler {
        String handle(String requestBody) throws Exception;
    }

    private void answer(HttpExchange ex, JsonHandler handler) throws IOException {
        try {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            respond(ex, 200, handler.handle(body));
        } catch (Exception e) {
            log.warning("Request error: " + e.getMessage());
            respond(ex, 500, "{\"error\":\"" + escape(String.valueOf(e.getMessage())) + "\"}");
        }
    }

    private static String buildJson(String kanji, List<String> segments, long ms) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"kanji\":\"").append(escape(kanji)).append("\",");
        sb.append("\"segments\":[");
        for (int i = 0; i < segments.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(escape(segments.get(i))).append("\"");
        }
        sb.append("],");
        sb.append("\"ms\":").append(ms);
        sb.append("}");
        return sb.toString();
    }

    private static String extractParam(String query, String key) {
        if (query == null) return null;
        for (String part : query.split("&")) {
            int eq = part.indexOf('=');
            if (eq < 0) continue;
            String k = URLDecoder.decode(part.substring(0, eq), StandardCharsets.UTF_8);
            if (k.equals(key)) {
                return URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static void respond(HttpExchange ex, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
