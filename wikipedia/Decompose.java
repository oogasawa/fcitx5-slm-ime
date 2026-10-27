import javax.xml.stream.*;
import java.io.*;
import java.util.*;

/**
 * Prototype: greedy longest-match compound decomposer over JMdict kanji headwords.
 * Grammar (greedy approximation):  compound := <JMdict keb> compound | epsilon
 * At each position, match the LONGEST JMdict headword that is a prefix, emit it
 * (with its reading), advance, repeat. Unknown single chars are marked '?'.
 *
 * Usage: java -Djdk.xml.entityExpansionLimit=0 Decompose.java JMdict_e 多細胞動物 受精 ...
 */
public class Decompose {
    public static void main(String[] args) throws Exception {
        Map<String, List<String>> dict = new HashMap<>();     // keb -> readings
        int maxLen = 1, CAP = 16;

        XMLInputFactory f = XMLInputFactory.newInstance();
        f.setProperty(XMLInputFactory.IS_COALESCING, Boolean.TRUE);
        f.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.TRUE);
        XMLStreamReader r = f.createXMLStreamReader(
            new BufferedInputStream(new FileInputStream(args[0])), "UTF-8");
        List<String> kebs = new ArrayList<>(), rebs = new ArrayList<>();
        String cur = null;
        while (r.hasNext()) {
            int ev = r.next();
            if (ev == XMLStreamConstants.START_ELEMENT) {
                cur = r.getLocalName();
                if (cur.equals("entry")) { kebs.clear(); rebs.clear(); }
            } else if (ev == XMLStreamConstants.CHARACTERS && cur != null) {
                String v = r.getText();
                if (cur.equals("keb")) kebs.add(v);
                else if (cur.equals("reb")) rebs.add(v);
            } else if (ev == XMLStreamConstants.END_ELEMENT) {
                if (r.getLocalName().equals("entry"))
                    for (String k : kebs) {
                        if (k.length() > CAP) continue;
                        dict.computeIfAbsent(k, x -> new ArrayList<>(rebs));
                        if (k.length() > maxLen) maxLen = k.length();
                    }
                cur = null;
            }
        }
        System.err.println("dict entries=" + dict.size() + " maxKebLen=" + maxLen);

        for (int i = 1; i < args.length; i++) {
            String s = args[i];
            StringBuilder pieces = new StringBuilder(), reading = new StringBuilder();
            int pos = 0; boolean complete = true;
            while (pos < s.length()) {
                int hit = -1;
                for (int len = Math.min(maxLen, s.length() - pos); len >= 1; len--) {
                    String sub = s.substring(pos, pos + len);
                    if (dict.containsKey(sub)) { hit = len;
                        pieces.append(sub).append('|');
                        reading.append(dict.get(sub).get(0)).append('|');
                        pos += len; break;
                    }
                }
                if (hit < 0) { pieces.append(s.charAt(pos)).append("?|");
                    reading.append("?|"); pos++; complete = false; }
            }
            System.out.println(s + "  =>  " + pieces + "   読み: " + reading
                + (complete ? "" : "  (辞書に無い要素あり)"));
        }
    }
}
