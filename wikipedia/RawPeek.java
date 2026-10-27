import java.io.*;

/** Print the first N characters of a bz2-compressed text file (for a real raw sample).
 *  Usage: java -cp commons-compress.jar:commons-io.jar RawPeek.java file.bz2 6000 */
public class RawPeek {
    public static void main(String[] a) throws Exception {
        int n = a.length > 1 ? Integer.parseInt(a[1]) : 4000;
        try (Reader r = new InputStreamReader(
                new org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream(
                    new BufferedInputStream(new FileInputStream(a[0]))), "UTF-8")) {
            char[] buf = new char[n];
            int got = r.read(buf, 0, n);
            System.out.print(new String(buf, 0, Math.max(0, got)));
        }
    }
}
