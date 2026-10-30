package com.github.oogasawa.slmime;

import ai.djl.Device;
import ai.djl.inference.Predictor;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.DataType;
import ai.djl.ndarray.types.Shape;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.translate.TranslateException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * HTTP server wrapping the M-512-12 kana-kanji inference model.
 *
 * GET /api/convert?kana=...
 * Response: {"kanji":"機械学習","segments":["機械学習"]}
 *
 * Models are loaded once at startup and kept in memory.
 */
public class KanjiServer {

    private static final Logger log = Logger.getLogger(KanjiServer.class.getName());

    private static final int BOS_ID = 1;
    private static final int EOS_ID = 2;
    private static final int PAD_ID = 0;
    /** Stop when the EOS score comes within this distance of the best score; 0 (the default) turns the rule off.
     *  The rule was made for an older model and ends the output of the current model too early. */
    private static final float EOS_PROX = Float.parseFloat(System.getProperty("stop.eosProx", "0"));
    /** Stop when the last four characters repeat a pair; off by default, like the rule above. */
    private static final boolean STOP_ON_REPEAT = Boolean.parseBoolean(System.getProperty("stop.repeat", "false"));

    private final CharTokenizer tokenizer;
    /** The token of '|', which the model writes between the bunsetsu. */
    private final int barId;
    private final ZooModel<NDList, NDList> encModel;
    private final ZooModel<NDList, NDList> decModel;
    private final Predictor<NDList, NDList> encoder;
    private final Predictor<NDList, NDList> decoder;
    /** The decoder with a key-value cache: crosskv_*.pt and step_*.pt; both are null when the model directory has
     *  no such files or {@code -Ddecoder.cache=false}, and then the whole prefix goes through {@link #decoder}. */
    private final Predictor<NDList, NDList> crossKv;
    private final Predictor<NDList, NDList> step;
    private final NDManager manager;
    private final BunsetsuSegmenter segmenter = new BunsetsuSegmenter();
    /** How many candidates of a bunsetsu from Anthy the model puts in order ({@code -Dcandidates.scored=N});
     *  the others follow them in Anthy's order, and 0 leaves all in Anthy's order. */
    private static final int MOST_SCORED = Integer.getInteger("candidates.scored", 20);
    /** How much Anthy's order counts against the scores of the model ({@code -Dcandidates.sourceWeight=W}). With 3,
     *  the right text of the bunsetsu the model got wrong came higher than with the scores alone or Anthy's order
     *  alone, in both the Wikipedia and the FineWeb test rows (386 bunsetsu); with the scores alone it came lower on
     *  FineWeb, where the model gave the kana spellings too much. */
    private static final double SOURCE_WEIGHT = Double.parseDouble(System.getProperty("candidates.sourceWeight", "3"));
    /** The requests of the fcitx5 engine, answered with this model. */
    private final ImeApi ime = new ImeApi(this::translate, AnthyCandidates.loadOrNone(), openHistory(),
            MOST_SCORED > 0 ? this::scores : ImeApi.Scorer.NONE, SOURCE_WEIGHT);

    /** The candidates the user chose: {@code -Dhistory.file=PATH} (default {@code user_history.tsv} in the
     *  working directory); an empty value keeps the history in memory only. */
    private static UserHistory openHistory() {
        String path = System.getProperty("history.file", "user_history.tsv");
        return path.isEmpty() ? UserHistory.inMemory() : UserHistory.inFile(java.nio.file.Paths.get(path));
    }

    public KanjiServer(String modelDir, String tokenizerPath) throws Exception {
        log.info("Loading tokenizer...");
        tokenizer = CharTokenizer.load(tokenizerPath);
        barId = tokenizer.encodeChars("|").get(0);
        log.info("Tokenizer loaded: vocab_size=" + tokenizer.vocabSize());

        Criteria<NDList, NDList> encCrit = Criteria.builder()
            .setTypes(NDList.class, NDList.class)
            .optModelPath(Paths.get(modelDir))
            .optModelName("encoder_m512-12")
            .optEngine("PyTorch")
            .optDevice(Device.cpu())
            .build();

        Criteria<NDList, NDList> decCrit = Criteria.builder()
            .setTypes(NDList.class, NDList.class)
            .optModelPath(Paths.get(modelDir))
            .optModelName("decoder_m512-12")
            .optEngine("PyTorch")
            .optDevice(Device.cpu())
            .build();

        log.info("Loading models from: " + modelDir);
        encModel = encCrit.loadModel();
        decModel = decCrit.loadModel();
        encoder = encModel.newPredictor();
        decoder = decModel.newPredictor();
        boolean cached = Boolean.parseBoolean(System.getProperty("decoder.cache", "true"))
            && Files.exists(Paths.get(modelDir, "step_m512-12.pt")) && Files.exists(Paths.get(modelDir, "crosskv_m512-12.pt"));
        if (cached) {
            crossKv = loadPredictor(modelDir, "crosskv_m512-12");
            step = loadPredictor(modelDir, "step_m512-12");
        } else {
            crossKv = null;
            step = null;
        }
        log.info("Decoder key-value cache: " + (cached ? "on" : "off"));
        manager = NDManager.newBaseManager(Device.cpu());
        log.info("Models ready.");
    }

    private static Predictor<NDList, NDList> loadPredictor(String modelDir, String name) throws Exception {
        return Criteria.<NDList, NDList>builder()
            .setTypes(NDList.class, NDList.class)
            .optModelPath(Paths.get(modelDir))
            .optModelName(name)
            .optEngine("PyTorch")
            .optDevice(Device.cpu())
            .build().loadModel().newPredictor();
    }

    public void startAndBlock(int port) throws IOException {
        // The text the user types goes through this server, so it listens on this machine only unless told otherwise.
        String bind = System.getProperty("bind.address", "127.0.0.1");
        HttpServer server = HttpServer.create(new InetSocketAddress(bind, port), 0);
        server.createContext("/api/convert", this::handleConvert);
        server.createContext("/api/health", ex -> respond(ex, 200, "{\"status\":\"ok\"}"));
        server.createContext("/api/segment-convert", ex -> answer(ex, ime::segmentConvert));
        server.createContext("/api/segment-candidates", ex -> answer(ex, ime::segmentCandidates));
        server.createContext("/api/record", ex -> answer(ex, ime::record));
        server.createContext("/api/continue", ex -> answer(ex, ime::continuation));
        server.createContext("/api/predict", ex -> respond(ex, 200, ime.predict()));
        server.start();
        log.info("kana-kanji-server listening on :" + port);
        // block forever
        Object lock = new Object();
        synchronized (lock) {
            try { lock.wait(); } catch (InterruptedException ignored) {}
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
            String kanji = translate(kana);
            List<String> segments = segmenter.segment(kanji);
            long ms = System.currentTimeMillis() - start;
            String json = buildJson(String.join("", segments), segments, ms);
            respond(ex, 200, json);
        } catch (Exception e) {
            log.warning("Convert error: " + e.getMessage());
            respond(ex, 500, "{\"error\":\"" + escape(e.getMessage()) + "\"}");
        }
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

    /** Whether the loaded model was trained to read the text before the cursor ({@code -Dmodel.context=true}). */
    private static final boolean USE_CONTEXT = Boolean.parseBoolean(System.getProperty("model.context", "false"));
    /** How many characters before the cursor that model was trained to read. */
    private static final int CONTEXT_CHARS = Integer.getInteger("model.contextChars", 48);

    private String translate(String input) throws Exception {
        return translate(input, "");
    }

    /** Writes the reading and the text before the cursor of every request to the log ({@code -Dlog.context=true}). */
    private static final boolean LOG_CONTEXT = Boolean.parseBoolean(System.getProperty("log.context", "false"));

    private String translate(String input, String context) throws Exception {
        if (LOG_CONTEXT) {
            log.info("request input=[" + input + "] context(" + context.length() + " chars)=[" + context + "]");
        }
        List<Integer> srcIds = SourceIds.of(tokenizer, context, input, USE_CONTEXT, CONTEXT_CHARS);
        int srcLen = srcIds.size();
        // The text is about as long as the reading, whatever the text before the cursor. The '|' between the bunsetsu
        // are not counted: a reading of many bunsetsu with long kana tails would lose its last characters
        // (きょうは...ことにする gave ...ことにす).
        int readingLen = tokenizer.encodeChars(input).size() + 1;

        try (NDManager sub = manager.newSubManager()) {
            NDArray memory = encode(srcIds);

            List<Integer> outputIds = new ArrayList<>();
            List<Long> tgtList = new ArrayList<>();
            tgtList.add((long) BOS_ID);
            Stepper stepper = step != null ? new CachedStepper(sub, memory) : new FullPrefixStepper(sub, memory, srcLen);

            int textLen = 0;
            for (int stepNo = 0; stepNo < 2 * readingLen + 5; stepNo++) {
                NDArray lastLogits = stepper.logitsOfLast(tgtList);

                float eosLogit = lastLogits.getFloat(EOS_ID);
                float maxLogit = lastLogits.max().getFloat();
                long nextId = lastLogits.argMax().getLong();

                if (nextId == EOS_ID) break;
                if (EOS_PROX > 0 && !outputIds.isEmpty() && eosLogit >= maxLogit - EOS_PROX) break;
                int n = outputIds.size();
                if (STOP_ON_REPEAT && n >= 3 && (int) nextId == outputIds.get(n - 2) && outputIds.get(n - 1).equals(outputIds.get(n - 3)))
                    break;

                outputIds.add((int) nextId);
                tgtList.add(nextId);
                if (nextId != barId) textLen++;
                if (textLen >= readingLen) break;
            }
            return tokenizer.decode(outputIds);
        }
    }

    /**
     * The log probability that the model writes each text, and nothing more, for the reading after the text before
     * the cursor. The first {@link #MOST_SCORED} texts are scored; the others get negative infinity, so that they
     * stay after the scored ones in their order.
     */
    private double[] scores(String reading, String context, List<String> texts) throws Exception {
        double[] result = new double[texts.size()];
        java.util.Arrays.fill(result, Double.NEGATIVE_INFINITY);
        int count = Math.min(texts.size(), MOST_SCORED);
        List<List<Integer>> ids = new ArrayList<>();
        int longest = 0;
        for (int t = 0; t < count; t++) {
            ids.add(tokenizer.encodeChars(texts.get(t)));
            longest = Math.max(longest, ids.get(t).size());
        }
        // All the texts go through the decoder at once: BOS, the text, then padding to the longest text.
        int tgtLen = longest + 1;
        long[] tgtArr = new long[count * tgtLen];
        boolean[] tgtPadArr = new boolean[count * tgtLen];
        for (int t = 0; t < count; t++) {
            tgtArr[t * tgtLen] = BOS_ID;
            for (int i = 0; i < longest; i++) {
                boolean inText = i < ids.get(t).size();
                tgtArr[t * tgtLen + i + 1] = inText ? ids.get(t).get(i) : PAD_ID;
                tgtPadArr[t * tgtLen + i + 1] = !inText;
            }
        }
        List<Integer> srcIds = SourceIds.of(tokenizer, context, reading, USE_CONTEXT, CONTEXT_CHARS);
        int srcLen = srcIds.size();
        try (NDManager sub = manager.newSubManager()) {
            NDArray memory = encode(srcIds);
            boolean[] maskData = new boolean[tgtLen * tgtLen];
            for (int i = 0; i < tgtLen; i++)
                for (int j = i + 1; j < tgtLen; j++)
                    maskData[i * tgtLen + j] = true;
            NDArray memories = memory.repeat(0, count);
            memories.attach(sub);  // the kept encoder output lives in the base manager; its copies must not
            NDList out = decoder.predict(new NDList(
                    sub.create(tgtArr, new Shape(count, tgtLen)),
                    memories,
                    sub.create(maskData, new Shape(tgtLen, tgtLen)),
                    sub.create(tgtPadArr, new Shape(count, tgtLen)),
                    sub.zeros(new Shape(count, srcLen), DataType.BOOLEAN)));
            // (texts, positions, vocabulary), read out at once: one read per element is slow
            float[] logProbs = out.get(0).logSoftmax(2).toFloatArray();
            int vocab = tokenizer.vocabSize();
            for (int t = 0; t < count; t++) {
                List<Integer> text = ids.get(t);
                int base = t * tgtLen * vocab;
                double sum = logProbs[base + text.size() * vocab + EOS_ID];
                for (int i = 0; i < text.size(); i++) {
                    sum += logProbs[base + i * vocab + text.get(i)];
                }
                result[t] = sum;
            }
        }
        return result;
    }

    /** The source of the last encoder run, and its output. */
    private List<Integer> encodedSrc;
    private NDArray encodedMemory;

    /**
     * The output of the encoder for the source ids. The last one is kept: the candidates of a bunsetsu are scored
     * right after the bunsetsu is converted, with the same source. The requests come one at a time (the server has
     * no executor of its own), so the kept output needs no lock.
     */
    private NDArray encode(List<Integer> srcIds) throws TranslateException {
        if (srcIds.equals(encodedSrc)) {
            return encodedMemory;
        }
        int srcLen = srcIds.size();
        try (NDManager sub = manager.newSubManager()) {
            NDArray src = sub.create(srcIds.stream().mapToLong(Integer::longValue).toArray(), new Shape(1, srcLen));
            NDArray memory = encoder.predict(new NDList(src, sub.zeros(new Shape(1, srcLen), DataType.BOOLEAN))).get(0);
            memory.attach(manager);  // outlives this request
            if (encodedMemory != null) {
                encodedMemory.close();
            }
            encodedSrc = new ArrayList<>(srcIds);
            encodedMemory = memory;
            return memory;
        }
    }

    /** The scores of the next token at every position of the prefix (it starts with BOS): (positions, vocabulary). */
    private NDArray decodeAll(NDManager sub, NDArray memory, int srcLen, long[] prefix) throws TranslateException {
        int tgtLen = prefix.length;
        NDArray tgt = sub.create(prefix, new Shape(1, tgtLen));
        boolean[] maskData = new boolean[tgtLen * tgtLen];
        for (int i = 0; i < tgtLen; i++)
            for (int j = i + 1; j < tgtLen; j++)
                maskData[i * tgtLen + j] = true;
        NDArray tgtMask = sub.create(maskData, new Shape(tgtLen, tgtLen));
        NDArray tgtPad = sub.zeros(new Shape(1, tgtLen), DataType.BOOLEAN);
        NDArray memPad = sub.zeros(new Shape(1, srcLen), DataType.BOOLEAN);
        return decoder.predict(new NDList(tgt, memory, tgtMask, tgtPad, memPad)).get(0).squeeze(0);
    }

    /** The scores of the next token, given the output so far (it starts with BOS). */
    private interface Stepper {
        NDArray logitsOfLast(List<Long> prefix) throws TranslateException;
    }

    /** Runs the whole prefix through the decoder at every step. */
    private final class FullPrefixStepper implements Stepper {
        private final NDManager sub;
        private final NDArray memory;
        private final int srcLen;

        FullPrefixStepper(NDManager sub, NDArray memory, int srcLen) {
            this.sub = sub;
            this.memory = memory;
            this.srcLen = srcLen;
        }

        @Override
        public NDArray logitsOfLast(List<Long> prefix) throws TranslateException {
            return decodeAll(sub, memory, srcLen, prefix.stream().mapToLong(Long::longValue).toArray())
                    .get(prefix.size() - 1);
        }
    }

    /** Feeds only the newest token; the keys and values of the earlier positions are kept between the steps. */
    private final class CachedStepper implements Stepper {
        private final NDManager sub;
        private final NDArray crossK;
        private final NDArray crossV;
        private NDArray selfK;
        private NDArray selfV;

        CachedStepper(NDManager sub, NDArray memory) throws TranslateException {
            this.sub = sub;
            NDList cross = crossKv.predict(new NDList(memory));
            crossK = cross.get(0);
            crossV = cross.get(1);
            crossK.attach(sub);
            crossV.attach(sub);
            // (layers, 1, heads, 0, head_dim): the shape of the cross keys with an empty time axis
            long[] shape = crossK.getShape().getShape().clone();
            shape[3] = 0;
            selfK = sub.zeros(new Shape(shape));
            selfV = sub.zeros(new Shape(shape));
        }

        @Override
        public NDArray logitsOfLast(List<Long> prefix) throws TranslateException {
            int position = prefix.size() - 1;
            NDArray token = sub.create(new long[]{prefix.get(position)}, new Shape(1, 1));
            NDArray pos = sub.create(new long[]{position}, new Shape(1));
            NDList out = step.predict(new NDList(token, pos, selfK, selfV, crossK, crossV));
            NDArray logits = out.get(0).squeeze(0).get(0);
            NDArray newK = out.get(1);
            NDArray newV = out.get(2);
            newK.attach(sub);
            newV.attach(sub);
            selfK.close();
            selfV.close();
            selfK = newK;
            selfV = newV;
            return logits;
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
