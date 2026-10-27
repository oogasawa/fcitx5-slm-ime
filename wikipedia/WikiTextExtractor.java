import javax.xml.stream.*;
import java.io.*;
import java.util.regex.*;

/**
 * Extract plain-text prose from a Japanese Wikipedia XML dump.
 *
 * Reads the .bz2 dump (arg 0), decompresses in-process, and writes TSV to stdout:
 *     title <TAB> paragraph
 * one line per non-empty prose paragraph. Only main-namespace (ns=0) non-redirect
 * articles are emitted. wikitext markup (templates, tables, refs, file/category
 * links, decoration) is stripped heuristically -- good enough for silver-label
 * training text, not a perfect MediaWiki parse.
 *
 * Optional arg 1 = max articles to emit (for sampling / validation).
 *
 * Usage:
 *   java -cp commons-compress.jar:commons-io.jar \
 *     -Djdk.xml.maxGeneralEntitySizeLimit=0 -Djdk.xml.totalEntitySizeLimit=0 \
 *     WikiTextExtractor.java jawiki-latest-pages-articles.xml.bz2 [maxArticles] > jawiki_text.tsv
 */
public class WikiTextExtractor {

    private static final Pattern HAS_KANJI = Pattern.compile("[\\u3040-\\u30FF\\u4E00-\\u9FFF]");

    // Precompiled cleaners (order matters; applied in sequence).
    private static final Pattern COMMENT   = Pattern.compile("(?s)<!--.*?-->");
    private static final Pattern REF_BLOCK  = Pattern.compile("(?is)<ref[^>]*>.*?</ref>");
    private static final Pattern REF_SELF   = Pattern.compile("(?is)<ref[^>]*/>");
    // Formulas: replaced with a sentinel so the whole paragraph can be dropped.
    private static final Pattern MATH_BLOCK = Pattern.compile("(?is)<(math|chem|ce)[^>]*>.*?</\\1>");
    private static final char FORMULA_MARK = '￿';
    private static final Pattern TAG_BLOCK  = Pattern.compile(
        "(?is)<(gallery|table|syntaxhighlight|source|code|pre|timeline|score|mapframe|maplink|nowiki|div|span)[^>]*>.*?</\\1>");
    private static final Pattern HTML_TAG   = Pattern.compile("(?s)<[^>]+>");
    private static final Pattern TABLE      = Pattern.compile("(?s)\\{\\|[^{]*?\\|\\}");
    private static final Pattern TEMPLATE   = Pattern.compile("\\{\\{[^{}]*\\}\\}");
    private static final Pattern MEDIA_LINK = Pattern.compile(
        "(?is)\\[\\[\\s*(File|Image|Media|Category|ファイル|画像|カテゴリ|メディア)\\s*:[^\\[\\]]*(\\[\\[[^\\]]*\\]\\])?[^\\[\\]]*\\]\\]");
    private static final Pattern LINK_PIPE  = Pattern.compile("\\[\\[[^\\]|]*\\|([^\\]|]*)\\]\\]");
    private static final Pattern LINK_PLAIN = Pattern.compile("\\[\\[([^\\]|]*)\\]\\]");
    private static final Pattern EXT_LINK_T = Pattern.compile("\\[https?://\\S+\\s+([^\\]]*)\\]");
    private static final Pattern EXT_LINK   = Pattern.compile("\\[https?://\\S+\\]");
    private static final Pattern WS         = Pattern.compile("[ \\t\\x0B\\f\\r]+");

    public static void main(String[] args) throws Exception {
        long maxArticles = (args.length > 1) ? Long.parseLong(args[1]) : Long.MAX_VALUE;

        InputStream raw = new org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream(
            new BufferedInputStream(new FileInputStream(args[0]), 1 << 20), true);
        XMLInputFactory f = XMLInputFactory.newInstance();
        f.setProperty(XMLInputFactory.IS_COALESCING, Boolean.TRUE);
        XMLStreamReader r = f.createXMLStreamReader(new BufferedInputStream(raw, 1 << 20), "UTF-8");
        PrintStream out = new PrintStream(new BufferedOutputStream(System.out, 1 << 20), false, "UTF-8");

        String title = null, ns = null, text = null, cur = null;
        long pages = 0, emitted = 0, paras = 0;

        while (r.hasNext() && emitted < maxArticles) {
            int ev = r.next();
            if (ev == XMLStreamConstants.START_ELEMENT) {
                String n = r.getLocalName();
                if (n.equals("page")) { title = null; ns = null; text = null; }
                cur = (n.equals("title") || n.equals("ns") || n.equals("text")) ? n : null;
            } else if (ev == XMLStreamConstants.CHARACTERS && cur != null) {
                String v = r.getText();
                if (cur.equals("title")) title = v;
                else if (cur.equals("ns")) ns = v.trim();
                else if (cur.equals("text")) text = v;
            } else if (ev == XMLStreamConstants.END_ELEMENT) {
                if (r.getLocalName().equals("page")) {
                    pages++;
                    if ("0".equals(ns) && title != null && text != null) {
                        String lead = text.stripLeading();
                        if (!lead.startsWith("#REDIRECT") && !lead.startsWith("#redirect")
                                && !lead.startsWith("#転送") && !lead.startsWith("#リダイレクト")) {
                            paras += emitArticle(title, text, out);
                            emitted++;
                        }
                    }
                }
                cur = null;
            }
        }
        out.flush();
        System.err.println("pages=" + pages + " articles=" + emitted + " paragraphs=" + paras);
    }

    // Remove [[...]] wiki links with correct nesting. File/Image/Category links are
    // dropped whole (caption and all); ordinary links become their display text.
    private static String stripLinks(String s) {
        StringBuilder b = new StringBuilder(s.length());
        int i = 0, n = s.length();
        while (i < n) {
            if (i + 1 < n && s.charAt(i) == '[' && s.charAt(i + 1) == '[') {
                int depth = 1, j = i + 2, innerStart = j;
                while (j < n && depth > 0) {
                    if (j + 1 < n && s.charAt(j) == '[' && s.charAt(j + 1) == '[') { depth++; j += 2; }
                    else if (j + 1 < n && s.charAt(j) == ']' && s.charAt(j + 1) == ']') { depth--; j += 2; }
                    else j++;
                }
                String inner = s.substring(innerStart, Math.max(innerStart, j - 2));
                if (!isMediaOrCat(inner)) {
                    String disp = stripLinks(inner);          // resolve nested links in caption/text
                    int p = disp.lastIndexOf('|');
                    b.append(p >= 0 ? disp.substring(p + 1) : disp);
                }
                i = j;
            } else {
                b.append(s.charAt(i));
                i++;
            }
        }
        return b.toString();
    }

    private static boolean isMediaOrCat(String inner) {
        int c = inner.indexOf(':');
        if (c < 0) return false;
        String p = inner.substring(0, c).trim().toLowerCase();
        return p.equals("file") || p.equals("image") || p.equals("media") || p.equals("category")
            || p.equals("ファイル") || p.equals("画像") || p.equals("カテゴリ") || p.equals("メディア");
    }

    private static long emitArticle(String title, String wikitext, PrintStream out) {
        String s = wikitext;
        s = COMMENT.matcher(s).replaceAll("");
        s = REF_BLOCK.matcher(s).replaceAll("");
        s = REF_SELF.matcher(s).replaceAll("");
        s = MATH_BLOCK.matcher(s).replaceAll(String.valueOf(FORMULA_MARK));  // mark formulas
        s = TAG_BLOCK.matcher(s).replaceAll("");
        // tables and templates can nest: strip innermost repeatedly
        for (int i = 0; i < 8; i++)  { String t = TABLE.matcher(s).replaceAll("");    if (t.equals(s)) break; s = t; }
        for (int i = 0; i < 12; i++) { String t = TEMPLATE.matcher(s).replaceAll(""); if (t.equals(s)) break; s = t; }
        s = stripLinks(s);                          // [[...]] incl. File/Category, nesting-safe
        s = EXT_LINK_T.matcher(s).replaceAll("$1");
        s = EXT_LINK.matcher(s).replaceAll("");
        s = HTML_TAG.matcher(s).replaceAll("");
        s = s.replace("'''''", "").replace("'''", "").replace("''", "");

        String titleTab = title + "\t";
        long n = 0;
        for (String line : s.split("\n")) {
            String t = line.strip();
            if (t.isEmpty()) continue;
            char c0 = t.charAt(0);
            // drop headings, list/table/definition markers, leftover braces/pipes
            if (c0 == '=' || c0 == '|' || c0 == '!' || c0 == '*' || c0 == '#'
                    || c0 == ':' || c0 == ';' || c0 == '{' || c0 == '}') continue;
            t = WS.matcher(t).replaceAll(" ").strip();
            // drop paragraphs that contained a formula, a stray file ref, or a URL
            if (t.indexOf(FORMULA_MARK) >= 0 || t.contains("File:") || t.contains("http")) continue;
            // safety net: drop any line still carrying markup residue
            if (t.indexOf('[') >= 0 || t.indexOf(']') >= 0 || t.indexOf('{') >= 0
                    || t.indexOf('}') >= 0 || t.indexOf('<') >= 0 || t.indexOf('>') >= 0) continue;
            // keep only lines that actually contain Japanese and are prose-length
            if (t.length() < 8) continue;
            if (!HAS_KANJI.matcher(t).find()) continue;
            out.print(titleTab);
            out.println(t);
            n++;
        }
        return n;
    }
}
