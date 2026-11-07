// Copied from plugin-kana-kanji of Turing-workflow-plugins (https://github.com/scivicslab/Turing-workflow-plugins),
// commit 5fabd30, the version that made the training pairs of the releases (Apache License 2.0, the same author).
// Only the package, ActionResult (now in this package) and the headers for our own GPU broker were changed.
package com.github.oogasawa.slmime.pairs;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * 対データの書き出し先と件数 を保持する素のオブジェクト。アクターとして動かすのは PairsActor の役目であり、
 * 「アクターであること」はこのクラスの性質ではない（ActorSuffixAndOwnedActorRef_260722_oo01）。
 *
 * Actor that validates LLM output and writes kana-kanji pairs to a TSV file.
 *
 * <p>Supported actions:</p>
 * <ul>
 *   <li>{@code openOutput} - Open output TSV file and write header</li>
 *   <li>{@code checkHiragana} - Validate that hiragana column contains hiragana; fails if not</li>
 *   <li>{@code writePairs} - Write validated pairs to output (with page/source metadata)</li>
 *   <li>{@code closeOutput} - Flush and close output file</li>
 * </ul>
 */
public class PairWriter {

    private static final Logger logger = Logger.getLogger(PairWriter.class.getName());

    private BufferedWriter writer = null;
    private String currentPage = "";
    private String currentSource = "";
    private int totalPairs = 0;

    /**
     * Open the output TSV file. Appends if file exists, creates if not.
     * Writes header row on creation.
     */
    public ActionResult openOutput(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return new ActionResult(false, "Output file path is required");
        }
        Path path = Path.of(filePath);
        boolean exists = Files.exists(path);
        try {
            writer = Files.newBufferedWriter(path,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            if (!exists) {
                writer.write("hiragana\tkanji\tpage\tsource\n");
                writer.flush();
            }
            return new ActionResult(true, "Output opened: " + filePath);
        } catch (IOException e) {
            return new ActionResult(false, "Failed to open output: " + e.getMessage());
        }
    }

    /**
     * Set page metadata (page number and source filename) for subsequent writePairs calls.
     * Argument format: "pageNum\tsourceFile"
     */
    public ActionResult setPageInfo(String pageInfo) {
        String info = pageInfo == null ? "" : pageInfo;
        if (info.isBlank()) {
            return new ActionResult(false, "Page info is required");
        }
        String[] parts = info.split("\t", 2);
        if (parts.length == 2) {
            currentPage = parts[0];
            currentSource = parts[1];
        } else {
            currentPage = info;
            currentSource = "";
        }
        return new ActionResult(true, "Page info set: page=" + currentPage + " source=" + currentSource);
    }

    /**
     * Validate that the LLM response has proper tab-separated format and
     * that the hiragana column actually contains hiragana characters.
     *
     * Returns failure if any tab-separated line has no hiragana in the left column.
     * Returns success with the count of valid lines if all lines pass.
     */
    public ActionResult checkHiragana(String response) {
        String text = response == null ? "" : response;
        if (text.isBlank()) {
            return new ActionResult(false, "Empty response");
        }
        List<String> lines = text.lines()
                .map(String::strip)
                .filter(l -> !l.isEmpty() && l.contains("\t"))
                .toList();

        if (lines.isEmpty()) {
            return new ActionResult(false, "No tab-separated lines found");
        }

        // Only require hiragana on lines whose kanji column contains Japanese characters.
        // Lines with purely ASCII/numeric content (formulas, English terms) need no reading.
        int japaneseLines = 0;
        int missingHiragana = 0;
        for (String line : lines) {
            String[] parts = line.split("\t", 2);
            String kanji = parts.length > 1 ? parts[1] : parts[0];
            if (containsJapanese(kanji)) {
                japaneseLines++;
                if (!containsHiragana(parts[0])) {
                    missingHiragana++;
                }
            }
        }

        if (japaneseLines > 0 && missingHiragana == japaneseLines) {
            // Every Japanese line is missing hiragana — LLM failed to produce readings
            logger.warning("Hiragana check failed: 0/" + japaneseLines + " Japanese lines have hiragana");
            return new ActionResult(false, "No hiragana readings produced for " + japaneseLines + " Japanese lines");
        }

        logger.info("Hiragana check passed: " + (japaneseLines - missingHiragana) + "/" + japaneseLines
                + " Japanese lines have hiragana, " + lines.size() + " total lines");
        return new ActionResult(true, String.valueOf(lines.size()));
    }

    /**
     * Write tab-separated pairs from the LLM response to the output file.
     * Appends page and source columns. Skips lines without a tab.
     */
    public ActionResult writePairs(String response) {
        if (writer == null) {
            return new ActionResult(false, "Output not open. Call openOutput first.");
        }
        String text = response == null ? "" : response.trim();
        if (text.isBlank()) {
            return new ActionResult(false, "Empty response");
        }

        List<String> written = new ArrayList<>();
        for (String line : text.lines().map(String::strip).filter(l -> !l.isEmpty()).toList()) {
            if (!line.contains("\t")) continue;
            String[] parts = line.split("\t", 2);
            try {
                writer.write(parts[0] + "\t" + parts[1] + "\t" + currentPage + "\t" + currentSource + "\n");
                written.add(line);
            } catch (IOException e) {
                return new ActionResult(false, "Write failed: " + e.getMessage());
            }
        }

        try {
            writer.flush();
        } catch (IOException e) {
            return new ActionResult(false, "Flush failed: " + e.getMessage());
        }

        totalPairs += written.size();
        logger.info("Wrote " + written.size() + " pairs (total: " + totalPairs + ")");
        return new ActionResult(true, String.valueOf(written.size()));
    }

    /**
     * Close the output file.
     */
    public ActionResult closeOutput() {
        if (writer == null) {
            return new ActionResult(true, "Output already closed");
        }
        try {
            writer.close();
            writer = null;
            return new ActionResult(true, "Output closed. Total pairs written: " + totalPairs);
        } catch (IOException e) {
            return new ActionResult(false, "Close failed: " + e.getMessage());
        }
    }

    private static boolean containsJapanese(String text) {
        for (char c : text.toCharArray()) {
            if ((c >= '\u3041' && c <= '\u3096')
                    || (c >= '\u30A1' && c <= '\u30F6')
                    || (c >= '\u4E00' && c <= '\u9FFF')
                    || (c >= '\u3400' && c <= '\u4DBF')) {
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

}
