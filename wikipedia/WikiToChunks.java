import java.io.*;
import java.nio.file.*;

/**
 * Convert extracted jawiki paragraphs (title<TAB>paragraph, from WikiTextExtractor)
 * into pipeline input chunk files that match the OCR tsv schema:
 *
 *     hiragana <TAB> kanji <TAB> page
 *
 * hiragana is left empty (the pipeline fills it); kanji holds the paragraph text;
 * page is a running page number. Each paragraph becomes one page; pagesPerChunk
 * paragraphs form one chunk file "wiki_<index>.tsv" in outputDir, so the existing
 * kkime workers can process Wikipedia exactly like OCR chunks.
 *
 * Args: inputTsv outputDir [maxParagraphs=0(all)] [pagesPerChunk=30] [stride=1]
 *   stride>1 does deterministic every-Nth sampling (spread across the whole dump).
 */
public class WikiToChunks {
    public static void main(String[] a) throws Exception {
        String input = a[0], outDir = a[1];
        long maxPara = a.length > 2 ? Long.parseLong(a[2]) : 0;   // 0 = all
        int perChunk = a.length > 3 ? Integer.parseInt(a[3]) : 30;
        int stride   = a.length > 4 ? Integer.parseInt(a[4]) : 1;
        Files.createDirectories(Paths.get(outDir));

        BufferedReader br = Files.newBufferedReader(Paths.get(input));
        long seen = 0, taken = 0;
        int chunkIdx = 0, pageInChunk = 0;
        PrintStream out = null;
        String line;
        while ((line = br.readLine()) != null) {
            int tab = line.indexOf('\t');
            if (tab < 0) continue;
            String para = line.substring(tab + 1).strip();
            if (para.isEmpty()) continue;
            if ((seen++ % stride) != 0) continue;                // stride sampling
            if (maxPara > 0 && taken >= maxPara) break;

            if (out == null || pageInChunk >= perChunk) {
                if (out != null) out.close();
                String name = String.format("wiki_%07d.tsv", chunkIdx++);
                out = new PrintStream(new BufferedOutputStream(
                    Files.newOutputStream(Paths.get(outDir, name))), false, "UTF-8");
                out.println("hiragana\tkanji\tpage");
                pageInChunk = 0;
            }
            // Split the paragraph into sentences at 。 so no single line is huge;
            // each sentence becomes one row, all rows of a paragraph share one page.
            int page = pageInChunk + 1;
            for (String sentence : splitSentences(para)) {
                out.print('\t');                // empty hiragana column
                out.print(sentence);
                out.print('\t');
                out.println(page);
            }
            pageInChunk++;
            taken++;
        }
        if (out != null) out.close();
        br.close();
        System.err.println("chunks=" + chunkIdx + " paragraphs_written=" + taken
            + " (scanned=" + seen + ", stride=" + stride + ", pagesPerChunk=" + perChunk + ")");
    }

    /** Split a paragraph into sentences at the Japanese full stop 。 (kept with each sentence). */
    private static java.util.List<String> splitSentences(String p) {
        java.util.List<String> out = new java.util.ArrayList<>();
        int start = 0;
        for (int i = 0; i < p.length(); i++) {
            if (p.charAt(i) == '。') {
                String s = p.substring(start, i + 1).strip();
                if (!s.isEmpty()) out.add(s);
                start = i + 1;
            }
        }
        if (start < p.length()) {
            String s = p.substring(start).strip();
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }
}
