import javax.xml.stream.*;
import java.io.*;
import java.util.*;
import java.util.regex.*;

/**
 * Build a term -> reading dictionary from a Japanese Wikipedia XML dump.
 *
 * Reads the DECOMPRESSED dump XML from stdin and writes TSV to stdout:
 *     title <TAB> reading(s, comma-separated) <TAB> defaultsort
 *
 * Only main-namespace (ns=0) pages whose title contains a kanji are emitted.
 * The reading is taken from the first parenthesized kana run in the lead
 * (e.g. "'''受精'''（じゅせい）とは" -> じゅせい). DEFAULTSORT is kept as a
 * coarse cross-check (it is seion / lossy).
 *
 * This is a first-cut extractor; thresholds are meant to be refined AFTER
 * looking at the real output against produced pairs.
 *
 * Usage (bz2 file directly, needs commons-compress on the classpath):
 *     java -cp commons-compress.jar WikiTermDict.java jawiki-latest-pages-articles.xml.bz2 > term_reading_dict.tsv
 * Or from an already-decompressed stream on stdin:
 *     cat jawiki.xml | java WikiTermDict.java > term_reading_dict.tsv
 */
public class WikiTermDict {

    // CJK unified ideographs (common block + ext A) -> "contains a kanji"
    private static final Pattern HAS_KANJI =
        Pattern.compile("[\\u4E00-\\u9FFF\\u3400-\\u4DBF]");

    // First parenthesis whose content STARTS with a kana run.
    // Group 1 = the leading run of hiragana/katakana/long-mark/middle-dot/space/comma.
    private static final Pattern LEAD_READING =
        Pattern.compile("（[\\s　]*([\\u3041-\\u3096\\u30A1-\\u30FA\\u30FC・、 　]+)");

    private static final Pattern DEFAULTSORT =
        Pattern.compile("\\{\\{\\s*(?:DEFAULTSORT|デフォルトソート|drsort)\\s*[:：]\\s*([^}|]+)");

    // A clean hiragana reading candidate (small kana + long mark allowed).
    private static final Pattern HIRAGANA_ONLY =
        Pattern.compile("^[\\u3041-\\u3096\\u30FC]+$");

    public static void main(String[] args) throws Exception {
        // Input: a .bz2 dump file (arg 0) decompressed in-process, or stdin.
        InputStream raw;
        if (args.length > 0) {
            raw = new org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream(
                new BufferedInputStream(new FileInputStream(args[0]), 1 << 20), true);
        } else {
            raw = System.in;
        }

        XMLInputFactory f = XMLInputFactory.newInstance();
        f.setProperty(XMLInputFactory.IS_COALESCING, Boolean.TRUE);
        XMLStreamReader r = f.createXMLStreamReader(
            new BufferedInputStream(raw, 1 << 20), "UTF-8");
        PrintStream out = new PrintStream(
            new BufferedOutputStream(System.out, 1 << 20), false, "UTF-8");

        String title = null, ns = null, text = null, cur = null;
        long pages = 0, emitted = 0;

        while (r.hasNext()) {
            int ev = r.next();
            if (ev == XMLStreamConstants.START_ELEMENT) {
                String n = r.getLocalName();
                if (n.equals("page")) { title = null; ns = null; text = null; }
                cur = (n.equals("title") || n.equals("ns") || n.equals("text")) ? n : null;
            } else if (ev == XMLStreamConstants.CHARACTERS && cur != null) {
                String v = r.getText();
                if (cur.equals("title")) title = v;
                else if (cur.equals("ns")) ns = v.trim();
                else if (cur.equals("text")) text = v;   // coalescing => full text
            } else if (ev == XMLStreamConstants.END_ELEMENT) {
                if (r.getLocalName().equals("page")) {
                    pages++;
                    if ("0".equals(ns) && title != null && text != null
                            && HAS_KANJI.matcher(title).find()) {
                        if (emit(title, text, out)) emitted++;
                    }
                }
                cur = null;
            }
        }
        out.flush();
        System.err.println("pages=" + pages + " emitted=" + emitted);
    }

    private static boolean emit(String title, String text, PrintStream out) {
        String t = text.stripLeading();
        if (t.startsWith("#REDIRECT") || t.startsWith("#redirect")
                || t.startsWith("#転送") || t.startsWith("#リダイレクト")) return false;

        // reading(s) from the first kana parenthesis in the lead
        List<String> rs = new ArrayList<>();
        Matcher m = LEAD_READING.matcher(text);
        if (m.find()) {
            for (String part : m.group(1).split("[、 　]")) {
                String p = kataToHira(part.replace("・", "").trim());
                if (!p.isEmpty() && HIRAGANA_ONLY.matcher(p).matches()) rs.add(p);
            }
        }
        String readings = String.join(",", rs);

        // defaultsort (coarse, seion) for cross-check
        String ds = "";
        Matcher d = DEFAULTSORT.matcher(text);
        if (d.find()) ds = kataToHira(d.group(1).trim());

        if (readings.isEmpty() && ds.isEmpty()) return false;
        out.print(title); out.print('\t');
        out.print(readings); out.print('\t');
        out.println(ds);
        return true;
    }

    // Katakana -> hiragana (readings are sometimes written in katakana).
    private static String kataToHira(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 'ァ' && c <= 'ヶ') c -= 0x60;
            b.append(c);
        }
        return b.toString();
    }
}
