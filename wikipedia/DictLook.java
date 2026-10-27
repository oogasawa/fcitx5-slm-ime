import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Look up specific headwords in a term_reading_dict.tsv (no grep).
 * Usage: java DictLook.java dict.tsv 受精 多細胞動物 ...
 * Prints the full dict line for each query term, plus the entry count.
 */
public class DictLook {
    public static void main(String[] a) throws Exception {
        Map<String, String> m = new HashMap<>(1 << 21);
        try (BufferedReader br = Files.newBufferedReader(Paths.get(a[0]))) {
            String l;
            while ((l = br.readLine()) != null) {
                int t = l.indexOf('\t');
                if (t > 0) m.putIfAbsent(l.substring(0, t), l);
            }
        }
        for (int i = 1; i < a.length; i++) {
            String v = m.get(a[i]);
            System.out.println(a[i] + " => " + (v == null ? "(not found)" : v));
        }
        System.out.println("dict entries=" + m.size());
    }
}
