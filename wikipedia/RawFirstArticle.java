import javax.xml.stream.*;
import java.io.*;

/** Print the first ns=0 article's raw title + wikitext (first N chars) from a bz2 dump.
 *  Usage: java -cp cc.jar:cio.jar -Djdk.xml...=0 RawFirstArticle.java dump.bz2 500 */
public class RawFirstArticle {
    public static void main(String[] a) throws Exception {
        int max = a.length > 1 ? Integer.parseInt(a[1]) : 500;
        InputStream raw = new org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream(
            new BufferedInputStream(new FileInputStream(a[0])), true);
        XMLInputFactory f = XMLInputFactory.newInstance();
        f.setProperty(XMLInputFactory.IS_COALESCING, Boolean.TRUE);
        XMLStreamReader r = f.createXMLStreamReader(new BufferedInputStream(raw), "UTF-8");
        String title = null, ns = null, text = null, cur = null;
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
                else if (cur.equals("text")) text = v;
            } else if (ev == XMLStreamConstants.END_ELEMENT) {
                if (r.getLocalName().equals("page") && "0".equals(ns) && title != null && text != null) {
                    String t = text.length() > max ? text.substring(0, max) : text;
                    System.out.println("<title>" + title + "</title>");
                    System.out.println("<ns>0</ns>");
                    System.out.println("<text>" + t + "</text>");
                    return;
                }
                cur = null;
            }
        }
    }
}
