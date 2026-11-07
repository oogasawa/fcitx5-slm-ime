// Copied from plugin-kana-kanji of Turing-workflow-plugins (https://github.com/scivicslab/Turing-workflow-plugins),
// commit 5fabd30, the version that made the training pairs of the releases (Apache License 2.0, the same author).
// Only the package, ActionResult (now in this package) and the headers for our own GPU broker were changed.
package com.github.oogasawa.slmime.pairs;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Logger;

/**
 * OCR結果のページ集合と読み出し位置 を保持する素のオブジェクト。アクターとして動かすのは OcrActor の役目であり、
 * 「アクターであること」はこのクラスの性質ではない（ActorSuffixAndOwnedActorRef_260722_oo01）。
 *
 * Actor that reads OCR TSV files and provides page text one page at a time.
 *
 * <p>OCR TSV format: hiragana TAB kanji TAB page (hiragana column is always empty).</p>
 *
 * <p>Supported actions:</p>
 * <ul>
 *   <li>{@code loadFile} - Load a single OCR TSV file</li>
 *   <li>{@code nextPage} - Advance to next page; returns fail when exhausted</li>
 *   <li>{@code getPageText} - Get current page OCR text (newline-joined fragments)</li>
 *   <li>{@code getPageInfo} - Get current page number and source filename</li>
 * </ul>
 */
public class OcrPages {

    private static final Logger logger = Logger.getLogger(OcrPages.class.getName());

    private String sourceFile = "";
    private List<Integer> pageOrder = new ArrayList<>();
    private Map<Integer, List<String>> pages = new TreeMap<>();
    private int currentPageIndex = -1;
    private int currentPageNum = -1;

    /**
     * Load an OCR TSV file. Groups kanji-column text by page number.
     */
    public ActionResult loadFile(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return new ActionResult(false, "File path is required");
        }
        Path path = Path.of(filePath);

        pages.clear();
        pageOrder.clear();
        currentPageIndex = -1;
        currentPageNum = -1;
        sourceFile = path.getFileName().toString();

        try (BufferedReader reader = Files.newBufferedReader(path)) {
            String line;
            boolean first = true;
            while ((line = reader.readLine()) != null) {
                if (first) { first = false; continue; } // skip header
                String[] cols = line.split("\t", -1);
                if (cols.length < 3) continue;
                String kanji = cols[1].strip();
                String pageStr = cols[2].strip();
                if (kanji.isEmpty() || !pageStr.matches("\\d+")) continue;
                int pageNum = Integer.parseInt(pageStr);
                pages.computeIfAbsent(pageNum, k -> new ArrayList<>()).add(kanji);
            }
        } catch (IOException e) {
            return new ActionResult(false, "Failed to read file: " + e.getMessage());
        }

        pageOrder = new ArrayList<>(pages.keySet());
        logger.info("Loaded " + sourceFile + ": " + pageOrder.size() + " pages");
        return new ActionResult(true, "Loaded " + pageOrder.size() + " pages from " + sourceFile);
    }

    /**
     * Advance to the next page. Returns failure (false) when all pages are exhausted.
     * This causes the workflow to try the next row (e.g., transition to end state).
     */
    public ActionResult nextPage() {
        currentPageIndex++;
        if (currentPageIndex >= pageOrder.size()) {
            return new ActionResult(false, "No more pages");
        }
        currentPageNum = pageOrder.get(currentPageIndex);
        int lineCount = pages.get(currentPageNum).size();
        logger.info("Advancing to page " + currentPageNum + " (" + lineCount + " lines)");
        return new ActionResult(true, "Page " + currentPageNum);
    }

    /**
     * Get the OCR text of the current page (fragments joined by newlines).
     */
    public ActionResult getPageText() {
        if (currentPageNum < 0) {
            return new ActionResult(false, "No page loaded. Call nextPage first.");
        }
        List<String> lines = pages.get(currentPageNum);
        if (lines == null || lines.isEmpty()) {
            return new ActionResult(false, "Page " + currentPageNum + " has no content");
        }
        String text = String.join("\n", lines);
        return new ActionResult(true, text);
    }

    /**
     * Get metadata about the current page: "pageNum\tsourceFile".
     */
    public ActionResult getPageInfo() {
        if (currentPageNum < 0) {
            return new ActionResult(false, "No page loaded");
        }
        return new ActionResult(true, currentPageNum + "\t" + sourceFile);
    }

}
