import javax.xml.stream.*;
import java.io.*;
import java.util.*;

/**
 * Print KANJIDIC2 hard facts (on/kun readings, English meanings) for given kanji.
 * Usage: java KanjiLook.java kanjidic2.xml 並立受精
 */
public class KanjiLook {
    public static void main(String[] args) throws Exception {
        Set<Integer> want = new HashSet<>();
        for (int i = 1; i < args.length; i++)
            for (int off = 0; off < args[i].length(); ) {
                int cp = args[i].codePointAt(off); off += Character.charCount(cp);
                want.add(cp);
            }

        Map<String, String[]> facts = new HashMap<>();   // literal -> {on, kun, meanings}
        XMLInputFactory f = XMLInputFactory.newInstance();
        f.setProperty(XMLInputFactory.IS_COALESCING, Boolean.TRUE);
        XMLStreamReader r = f.createXMLStreamReader(
            new BufferedInputStream(new FileInputStream(args[0])), "UTF-8");

        String literal = null, cur = null, rtype = null; boolean meaningEng = false;
        List<String> on = new ArrayList<>(), kun = new ArrayList<>(), mean = new ArrayList<>();
        while (r.hasNext()) {
            int ev = r.next();
            if (ev == XMLStreamConstants.START_ELEMENT) {
                cur = r.getLocalName();
                if (cur.equals("character")) { literal = null; on.clear(); kun.clear(); mean.clear(); }
                else if (cur.equals("reading")) rtype = r.getAttributeValue(null, "r_type");
                else if (cur.equals("meaning")) meaningEng = (r.getAttributeValue(null, "m_lang") == null);
            } else if (ev == XMLStreamConstants.CHARACTERS && cur != null) {
                String v = r.getText();
                if (cur.equals("literal")) literal = v;
                else if (cur.equals("reading")) {
                    if ("ja_on".equals(rtype)) on.add(v);
                    else if ("ja_kun".equals(rtype)) kun.add(v);
                } else if (cur.equals("meaning") && meaningEng) mean.add(v);
            } else if (ev == XMLStreamConstants.END_ELEMENT) {
                if (r.getLocalName().equals("character") && literal != null
                        && want.contains(literal.codePointAt(0))) {
                    facts.put(literal, new String[]{
                        String.join("・", on), String.join("・", kun), String.join(", ", mean)});
                }
                cur = null;
            }
        }

        for (int i = 1; i < args.length; i++)
            for (int off = 0; off < args[i].length(); ) {
                int cp = args[i].codePointAt(off); off += Character.charCount(cp);
                String k = new String(Character.toChars(cp));
                String[] v = facts.get(k);
                if (v == null) System.out.println(k + "  (not found)");
                else System.out.println(k + "  音:[" + v[0] + "]  訓:[" + v[1] + "]  意:" + v[2]);
            }
    }
}
