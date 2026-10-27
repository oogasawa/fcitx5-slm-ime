import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Stage 3: check the readings an LLM gave to each bunsetsu against two dictionaries, without changing them.
 *
 * Input rows are  hiragana TAB kanji TAB page TAB source,  where hiragana and kanji are both cut into
 * bunsetsu by '|' and the n-th bunsetsu of one side answers the n-th of the other.
 *
 * For each kanji bunsetsu the longest head that is a dictionary headword is looked up (an optional
 * kana prefix of up to two characters and a kana tail such as a particle or okurigana are set aside).
 * The readings the dictionaries give for that head, plus the prefix and tail, are the expected
 * readings. If the LLM's reading is one of them the bunsetsu agrees; otherwise it is listed as a
 * mismatch with every expected reading. Nothing is replaced.
 *
 * Usage:
 *   java ReadingCheck.java check  term_reading_dict.tsv JMdict_e  out_dir  pairs1.tsv pairs2.tsv ...
 *     writes out_dir/mismatches.tsv and prints counts; out_dir/annotated/<name> carry a 5th column
 *     with "kanji=expected1/expected2" for every mismatching bunsetsu (the first four columns unchanged).
 */
public class ReadingCheck {

    /** headword -> readings (hiragana). */
    static final Map<String, Set<String>> DICT = new HashMap<>(1 << 21);

    public static void main(String[] a) throws Exception {
        if (a.length < 5 || !a[0].equals("check")) {
            System.err.println("usage: check term_reading_dict.tsv JMdict_e out_dir pairs.tsv...");
            System.exit(2);
        }
        loadWiki(Paths.get(a[1]));
        int wiki = DICT.size();
        loadJmdict(Paths.get(a[2]));
        System.err.println("dictionary: wikipedia headwords=" + wiki + ", merged headwords=" + DICT.size());

        Path out = Paths.get(a[3]);
        Files.createDirectories(out.resolve("annotated"));
        long bunsetsu = 0, withKanji = 0, hit = 0, agree = 0, mismatch = 0, skipped = 0;
        try (PrintStream mm = new PrintStream(Files.newOutputStream(out.resolve("mismatches.tsv")), false, "UTF-8")) {
            mm.println("file\tline\tkanji\tllm_reading\texpected_readings");
            for (int i = 4; i < a.length; i++) {
                Path in = Paths.get(a[i]);
                try (BufferedReader br = Files.newBufferedReader(in);
                     PrintStream ann = new PrintStream(Files.newOutputStream(out.resolve("annotated").resolve(in.getFileName())), false, "UTF-8")) {
                    String l = br.readLine();           // header
                    ann.println(l + "\tdict_diff");
                    int n = 1;
                    while ((l = br.readLine()) != null) {
                        n++;
                        String[] c = l.split("\t", -1);
                        if (c.length < 4) { ann.println(l + "\t"); continue; }
                        String[] hs = c[0].split("\\|", -1), ks = c[1].split("\\|", -1);
                        StringBuilder diff = new StringBuilder();
                        if (hs.length != ks.length) { skipped++; ann.println(l + "\t"); continue; }
                        for (int j = 0; j < ks.length; j++) {
                            bunsetsu++;
                            if (!hasKanji(ks[j]) || hasForeign(ks[j])) continue;
                            withKanji++;
                            Set<String> exp = expected(ks[j]);
                            if (exp == null) continue;
                            hit++;
                            String got = norm(hs[j]);
                            if (exp.contains(got) || partOf(exp, got)) { agree++; continue; }
                            mismatch++;
                            String e = String.join("/", exp);
                            mm.println(in.getFileName() + "\t" + n + "\t" + ks[j] + "\t" + hs[j] + "\t" + e);
                            if (diff.length() > 0) diff.append(';');
                            diff.append(ks[j]).append('=').append(e);
                        }
                        ann.println(l + "\t" + diff);
                    }
                }
            }
        }
        System.out.println("bunsetsu=" + bunsetsu + " with_kanji=" + withKanji + " dictionary_hit=" + hit
                + " agree=" + agree + " mismatch=" + mismatch + " rows_skipped(count differs)=" + skipped);
    }

    /** Readings the dictionaries expect for this bunsetsu, or null if no head of it is a headword. */
    static Set<String> expected(String k) {
        int n = k.length();
        for (int s = 0; s <= Math.min(2, n - 1); s++) {
            if (s > 0 && !isKana(k.substring(0, s))) break;
            String prefix = norm(k.substring(0, s));
            for (int e = n; e > s; e--) {
                String head = k.substring(s, e);
                if (!hasKanji(head) || head.length() < 2) continue;
                Set<String> rs = DICT.get(head);
                if (rs == null) continue;
                String tail = k.substring(e);
                String tailTrimmed = trimPunct(tail);
                if (!particlesOnly(tailTrimmed)) continue;      // not a plain headword + particles: leave it alone
                String t = norm(tailTrimmed);
                Set<String> exp = new TreeSet<>();
                for (String r : rs) exp.add(prefix + r + t);
                return exp;
            }
        }
        return null;
    }

    static final String[] PARTICLES = {"から", "まで", "より", "など", "だけ", "しか", "ばかり", "さえ", "こそ", "でも", "では", "には", "とは", "への", "での",
            "との", "として", "という", "による", "により", "について", "にて", "です", "ます", "である", "であり", "でした", "でしょう", "だった", "ない",
            "が", "を", "に", "の", "は", "で", "と", "も", "へ", "や", "か", "ね", "よ", "な", "だ", "し", "て"};

    /** True if s is empty or a concatenation of common particles. */
    static boolean particlesOnly(String s) {
        boolean[] ok = new boolean[s.length() + 1];
        ok[0] = true;
        for (int i = 0; i < s.length(); i++) {
            if (!ok[i]) continue;
            for (String p : PARTICLES) if (s.startsWith(p, i)) ok[i + p.length()] = true;
        }
        return ok[s.length()];
    }

    /** Digits and Latin letters: year, date and name forms the dictionaries read in their own ways. */
    static boolean hasForeign(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= 0xFF10 && c <= 0xFF5A)) return true;
        }
        return false;
    }

    /** A dictionary reading that is only the family or given name (Wikipedia gives one of them) and begins or ends the LLM's reading. */
    static boolean partOf(Set<String> exp, String got) {
        for (String r : exp) if (r.length() >= 2 && r.length() < got.length() && (got.startsWith(r) || got.endsWith(r))) return true;
        return false;
    }

    static boolean hasKanji(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 0x4E00 && c <= 0x9FFF) || c == '々' || (c >= 0x3400 && c <= 0x4DBF)) return true;
        }
        return false;
    }

    static boolean isKana(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean hira = c >= 0x3041 && c <= 0x3096, kata = c >= 0x30A1 && c <= 0x30FA, mark = c == 'ー';
            if (!(hira || kata || mark)) return false;
        }
        return true;
    }

    static String trimPunct(String s) {
        int e = s.length();
        while (e > 0 && "、。,.!?！？ 　」』）)".indexOf(s.charAt(e - 1)) >= 0) e--;
        return s.substring(0, e);
    }

    /** Katakana to hiragana, punctuation and spaces dropped. */
    static String norm(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x30A1 && c <= 0x30F6) c = (char) (c - 0x60);
            if ("、。,.!?！？ 　」『』「（）()・".indexOf(c) >= 0) continue;
            b.append(c);
        }
        return b.toString();
    }

    /** A reading is acceptable only if it is plain kana and not much longer than the headword could give. */
    static boolean plausible(String head, String reading) {
        if (reading.isEmpty() || !isKana(reading)) return false;
        long iteration = head.chars().filter(ch -> ch == '々').count();
        return reading.length() >= head.length() - iteration && reading.length() <= 4 * head.length() + 2;
    }

    static void add(String head, String reading) {
        String r = norm(reading);
        if (!plausible(head, r)) return;
        DICT.computeIfAbsent(head, x -> new TreeSet<>()).add(r);
    }

    /** term_reading_dict.tsv: headword TAB readings (comma separated) TAB defaultsort. */
    static void loadWiki(Path p) throws IOException {
        try (BufferedReader br = Files.newBufferedReader(p)) {
            String l;
            while ((l = br.readLine()) != null) {
                String[] c = l.split("\t", -1);
                if (c.length < 2 || !hasKanji(c[0])) continue;
                for (String r : c[1].split("[,、]")) add(c[0], r.strip());
            }
        }
    }

    /** JMdict_e: <keb> headwords and <reb> readings of each <entry>; read line by line. */
    static void loadJmdict(Path p) throws IOException {
        List<String> kebs = new ArrayList<>(), rebs = new ArrayList<>();
        try (BufferedReader br = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
            String l;
            while ((l = br.readLine()) != null) {
                if (l.startsWith("<entry>")) { kebs.clear(); rebs.clear(); }
                else if (l.startsWith("<keb>")) kebs.add(between(l, "<keb>", "</keb>"));
                else if (l.startsWith("<reb>")) rebs.add(between(l, "<reb>", "</reb>"));
                else if (l.startsWith("</entry>")) {
                    for (String k : kebs) if (hasKanji(k)) for (String r : rebs) add(k, r);
                }
            }
        }
    }

    static String between(String s, String a, String b) {
        int i = s.indexOf(a) + a.length(), j = s.indexOf(b, i);
        return j < 0 ? s.substring(i) : s.substring(i, j);
    }
}
