// Copied from plugin-kana-kanji of Turing-workflow-plugins (https://github.com/scivicslab/Turing-workflow-plugins),
// commit 5fabd30, the version that made the training pairs of the releases (Apache License 2.0, the same author).
// Only the package, ActionResult (now in this package), the headers for a GPU broker (sent only when the system
// property pairs.background is true) and the URL and the model (arguments of the constructor, with no default
// address or model) were changed.
package com.github.oogasawa.slmime.pairs;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * vLLMサーバへの問い合わせ を保持する素のオブジェクト。アクターとして動かすのは VllmActor の役目であり、
 * 「アクターであること」はこのクラスの性質ではない（ActorSuffixAndOwnedActorRef_260722_oo01）。
 *
 * Actor that calls a vLLM server via OpenAI-compatible chat completions API.
 *
 * <p>Provides two-step kana-kanji pair generation:</p>
 * <ol>
 *   <li>{@code segment} - Step 1: merge OCR fragments into bunsetsu-segmented sentences</li>
 *   <li>{@code toHiragana} - Step 2: convert bunsetsu-segmented kanji text to hiragana</li>
 * </ol>
 *
 * <p>Supported actions:</p>
 * <ul>
 *   <li>{@code segment} - Step 1: segment OCR text into bunsetsu</li>
 *   <li>{@code toHiragana} - Step 2: convert segmented text to hiragana</li>
 * </ul>
 */
public class VllmClient {

    private static final Logger logger = Logger.getLogger(VllmClient.class.getName());
    private static final Duration TIMEOUT = Duration.ofMinutes(10);

    private static final String SEGMENT_PROMPT = """
            /no_think
            以下は専門書の1ページ分のOCRテキストです。視覚的な改行で断片化されています。

            手順：
            1. 断片を結合して意味のある文・句にまとめる
            2. 各文・句を文節単位で | で区切る
            3. ページ番号のみの行は除外する

            文節とは、自立語（名詞・動詞・形容詞など）1つと、それに続く付属語（助詞・助動詞）をまとめた単位です。
            「ね」を入れて切れるところが文節の切れ目です。単語ごとに切ってはいけません。

            出力形式（1文・句につき1行）：
            漢字文（文節を|で区切る）

            例：
            巾級数と|いうものは|この|種の|一般的な|方法の|一つで

            ルール：
            - 助詞・助動詞・句読点だけの区切りを作らない。前の文節に付ける（「現代の」「フォントでも、」）
            - 複合語は分けない（「変数宣言記号」は1つ）
            - 数式・アルファベット・数字・英語はそのまま残す
            - 説明・コメント不要。出力行のみ

            OCRテキスト:
            %s""";

    private static final String HIRAGANA_PROMPT = """
            /no_think
            以下は文節で区切られた日本語テキストです（文節の区切りは|）。
            各行について、漢字をひらがなに変換した読みを付けて出力してください。

            出力形式（1行につき1行）：
            ひらがな読み（文節を|で区切る）\t漢字文（文節を|で区切る）

            例（入力）：
            巾級数と|いうものは|この|種の|一般的な|方法の|一つで

            例（出力）：
            べききゅうすうと|いうものは|この|しゅの|いっぱんてきな|ほうほうの|ひとつで\t巾級数と|いうものは|この|種の|一般的な|方法の|一つで

            ルール：
            - 区切りの | は、入力と同じ位置に、同じ数だけ置く。増やしたり減らしたりしない
            - 左（ひらがな読み）と右（漢字文）の | の数は同じにする
            - 数式・アルファベット・数字・英語はひらがな読みなしでそのまま右側に残す
            - ひらがな・カタカナはそのままひらがなに変換する
            - 助詞の「は」「へ」「を」は発音（わ／え／お）ではなく表記どおり「は」「へ」「を」と書く
            - 説明・コメント不要。出力行のみ

            入力テキスト:
            %s""";

    private final String vllmUrl;
    private final String model;
    private String lastResponse = "";

    /**
     * @param url the chat completions URL of an OpenAI-compatible server
     * @param model the model that server serves; neither has a default, because a server address is the business of
     *              whoever runs the program
     * @throws IllegalArgumentException when one of them is missing
     */
    public VllmClient(String url, String model) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("URL is required");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("Model name is required");
        }
        this.vllmUrl = url;
        this.model = model;
    }

    /**
     * Step 1: Send OCR text to LLM for bunsetsu segmentation.
     * Returns the segmented kanji text (one sentence per line, bunsetsu separated by |).
     */
    public ActionResult segment(String ocrText) {
        String text = ocrText == null ? "" : ocrText;
        if (text.isBlank()) {
            return new ActionResult(false, "OCR text is required");
        }
        String prompt = SEGMENT_PROMPT.formatted(text);
        try {
            String response = fixLoneParticles(callVllm(prompt));
            this.lastResponse = response;
            logger.info("Step1 segmentation: " + response.lines().count() + " lines");
            return new ActionResult(true, response);
        } catch (Exception e) {
            logger.log(Level.WARNING, "vLLM call failed (segment)", e);
            return new ActionResult(false, "vLLM error: " + e.getMessage());
        }
    }

    /**
     * Step 2: Convert bunsetsu-segmented kanji text to hiragana/kanji pairs.
     *
     * <p>Processes input in batches of {@value #BATCH_SIZE} lines. For each batch,
     * validates that lines containing Japanese have hiragana readings. Lines that fail
     * validation are retried individually (up to {@value #MAX_LINE_RETRIES} times).
     * Purely ASCII/numeric lines require no hiragana and are passed through.</p>
     *
     * @return tab-separated hiragana-kanji pairs, one per line
     */
    public ActionResult toHiragana(String segmentedText) {
        String text = segmentedText == null ? "" : segmentedText;
        if (text.isBlank()) {
            return new ActionResult(false, "Segmented text is required");
        }
        List<String> inputLines = text.lines()
                .map(String::strip)
                .filter(s -> !s.isBlank())
                .toList();

        List<String> results = new ArrayList<>();
        try {
            for (int i = 0; i < inputLines.size(); i += BATCH_SIZE) {
                List<String> batch = inputLines.subList(i, Math.min(i + BATCH_SIZE, inputLines.size()));
                List<String> batchResults = processBatch(batch);
                results.addAll(batchResults);
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "vLLM call failed (toHiragana)", e);
            return new ActionResult(false, "vLLM error: " + e.getMessage());
        }

        String combined = String.join("\n", results);
        logger.info("Step2 hiragana: " + results.size() + " lines total");
        return new ActionResult(true, combined);
    }

    /**
     * Lines that were split into words (a segment of only particles or punctuation) are asked once more,
     * one line at a time. A line that is still split that way is kept, so the page is not lost.
     */
    private String fixLoneParticles(String response) {
        List<String> fixed = new ArrayList<>();
        for (String line : response.lines().toList()) {
            if (!BunsetsuCheck.hasLoneParticle(line)) {
                fixed.add(line);
                continue;
            }
            String again = line;
            try {
                String reply = callVllm(SEGMENT_PROMPT.formatted(line.replace("|", "")));
                List<String> replyLines = reply.lines().map(String::strip).filter(l -> !l.isBlank()).toList();
                if (replyLines.size() == 1 && !BunsetsuCheck.hasLoneParticle(replyLines.get(0))) {
                    again = replyLines.get(0);
                } else {
                    logger.info("Kept a line split into words: " + line);
                }
            } catch (Exception e) {
                logger.log(Level.WARNING, "vLLM call failed (re-segment), keeping the line", e);
            }
            fixed.add(again);
        }
        return String.join("\n", fixed);
    }

    private static final int BATCH_SIZE = 20;
    private static final int MAX_LINE_RETRIES = 2;

    /**
     * Processes a batch of segmented lines: calls LLM only for Japanese lines,
     * passes through non-Japanese lines (formulas, ASCII) as-is, validates
     * output, and retries individual failing Japanese lines.
     */
    private List<String> processBatch(List<String> inputLines) throws Exception {
        // Separate Japanese lines from pass-through lines
        List<Integer> japaneseIndices = new ArrayList<>();
        List<String> japaneseLines = new ArrayList<>();
        String[] results = new String[inputLines.size()];

        for (int i = 0; i < inputLines.size(); i++) {
            String line = inputLines.get(i);
            if (containsJapanese(line)) {
                japaneseIndices.add(i);
                japaneseLines.add(line);
            } else {
                // Non-Japanese: pass through as kanji-only pair
                results[i] = "\t" + line;
            }
        }

        if (!japaneseLines.isEmpty()) {
            String batchInput = String.join("\n", japaneseLines);
            String response = callVllm(HIRAGANA_PROMPT.formatted(batchInput));

            List<String> responseLines = response.lines()
                    .map(String::strip)
                    .filter(l -> !l.isBlank())
                    .toList();

            // Align response lines to Japanese input lines by index
            for (int j = 0; j < japaneseLines.size(); j++) {
                String inputLine = japaneseLines.get(j);
                String responseLine = j < responseLines.size() ? responseLines.get(j) : "";
                int origIndex = japaneseIndices.get(j);

                if (isAcceptable(inputLine, responseLine)) {
                    results[origIndex] = responseLine.isEmpty() ? "\t" + inputLine : responseLine;
                } else {
                    results[origIndex] = retryLine(inputLine);
                }
            }
        }

        List<String> out = new ArrayList<>(java.util.Arrays.asList(results));
        logger.info("Batch of " + inputLines.size() + " lines processed ("
                + japaneseLines.size() + " Japanese, "
                + (inputLines.size() - japaneseLines.size()) + " pass-through)");
        return out;
    }

    /**
     * Checks whether a response line is acceptable for the given input line.
     * Lines with Japanese kanji must have a hiragana reading in the left column.
     * Purely ASCII/numeric lines are always acceptable.
     */
    private boolean isAcceptable(String inputLine, String responseLine) {
        if (!containsJapanese(inputLine)) {
            return true; // No Japanese → no reading required
        }
        if (responseLine.isBlank() || !responseLine.contains("\t")) {
            return false;
        }
        String[] columns = responseLine.split("\t", 2);
        return containsHiragana(columns[0]) && BunsetsuCheck.sameBoundaryCount(columns[0], columns[1]);
    }

    /**
     * Retries a single input line up to MAX_LINE_RETRIES times.
     * Returns the best result (or the input line as fallback).
     */
    private String retryLine(String inputLine) throws Exception {
        for (int attempt = 1; attempt <= MAX_LINE_RETRIES; attempt++) {
            logger.info("Retrying line (attempt " + attempt + "): " + inputLine);
            String response = callVllm(HIRAGANA_PROMPT.formatted(inputLine));
            String line = response.lines()
                    .map(String::strip)
                    .filter(l -> !l.isBlank())
                    .findFirst()
                    .orElse("");
            if (isAcceptable(inputLine, line)) {
                return line;
            }
        }
        // Give up: return input as kanji-only pair (empty hiragana)
        logger.warning("Giving up on line after " + MAX_LINE_RETRIES + " retries: " + inputLine);
        return "\t" + inputLine;
    }

    private static boolean containsJapanese(String text) {
        for (char c : text.toCharArray()) {
            if ((c >= '\u3041' && c <= '\u3096') // hiragana
                    || (c >= '\u30A1' && c <= '\u30F6') // katakana
                    || (c >= '\u4E00' && c <= '\u9FFF') // CJK unified ideographs
                    || (c >= '\u3400' && c <= '\u4DBF')) { // CJK extension A
                return true;
            }
        }
        return false;
    }

    private static boolean containsHiragana(String text) {
        for (char c : text.toCharArray()) {
            if (c >= '\u3041' && c <= '\u3096') return true;
        }
        return false;
    }

    private String callVllm(String prompt) throws Exception {
        String body = """
                {
                    "model": "%s",
                    "messages": [{"role": "user", "content": %s}],
                    "max_tokens": 8192,
                    "temperature": 0.0,
                    "chat_template_kwargs": {"enable_thinking": false}
                }
                """.formatted(model, jsonEscape(prompt));

        logger.info("vLLM request URL: " + vllmUrl);
        logger.info("vLLM request body (first 500 chars): " + body.substring(0, Math.min(500, body.length())));

        HttpClient client = HttpClient.newBuilder()
                .version(java.net.http.HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(vllmUrl))
                .header("Content-Type", "application/json; charset=utf-8")
                .timeout(TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(body, java.nio.charset.StandardCharsets.UTF_8));
        if (Boolean.getBoolean("pairs.background")) {
            // Bulk generation: a GPU broker (quarkus-gpu-broker) queues this behind interactive requests, and tells
            // who is asking. A vLLM server ignores the headers.
            builder.header("X-Job-Priority", "background").header("X-Submitter-Id", "kana-kanji-pairs");
        }
        HttpRequest request = builder.build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("HTTP " + response.statusCode() + ": " + response.body());
        }

        return extractContent(response.body());
    }

    private String extractContent(String jsonBody) {
        // Extract choices[0].message.content from OpenAI-compatible response
        int contentIdx = jsonBody.lastIndexOf("\"content\":");
        if (contentIdx < 0) {
            throw new RuntimeException("No content field in response: " + jsonBody.substring(0, Math.min(200, jsonBody.length())));
        }
        int valueStart = jsonBody.indexOf('"', contentIdx + 10);
        if (valueStart < 0) return "";
        valueStart++;

        StringBuilder sb = new StringBuilder();
        for (int i = valueStart; i < jsonBody.length(); i++) {
            char c = jsonBody.charAt(i);
            if (c == '"' && jsonBody.charAt(i - 1) != '\\') break;
            if (c == '\\' && i + 1 < jsonBody.length()) {
                char next = jsonBody.charAt(i + 1);
                switch (next) {
                    case 'n' -> { sb.append('\n'); i++; continue; }
                    case 't' -> { sb.append('\t'); i++; continue; }
                    case '"' -> { sb.append('"'); i++; continue; }
                    case '\\' -> { sb.append('\\'); i++; continue; }
                }
            }
            sb.append(c);
        }
        return sb.toString().strip();
    }

    private static String jsonEscape(String text) {
        return "\"" + text
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
                + "\"";
    }

}
