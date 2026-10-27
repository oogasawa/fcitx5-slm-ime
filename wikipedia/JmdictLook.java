import javax.xml.stream.*;
import java.io.*;
import java.util.*;

/**
 * Print JMdict readings for given kanji headwords (hard word-level facts).
 * Usage: java -Djdk.xml.entityExpansionLimit=0 JmdictLook.java JMdict_e 並立 受精 ...
 */
public class JmdictLook {
    public static void main(String[] args) throws Exception {
        Set<String> want = new HashSet<>();
        for (int i = 1; i < args.length; i++) want.add(args[i]);

        Map<String, LinkedHashSet<String>> map = new HashMap<>();   // keb -> readings
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
                    for (String k : kebs)
                        if (want.contains(k))
                            map.computeIfAbsent(k, x -> new LinkedHashSet<>()).addAll(rebs);
                cur = null;
            }
        }
        for (int i = 1; i < args.length; i++) {
            LinkedHashSet<String> s = map.get(args[i]);
            System.out.println(args[i] + " => " + (s == null ? "(not found)" : String.join("・", s)));
        }
    }
}
