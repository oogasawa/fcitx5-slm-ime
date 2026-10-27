package com.github.oogasawa.slmime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Character-level tokenizer that loads a JSON vocab file.
 * Format: {"[PAD]": 0, "[BOS]": 1, "[EOS]": 2, "[UNK]": 3, "a": 4, ...}
 */
public class CharTokenizer {

    private static final int UNK_ID = 3;

    private final Map<String, Integer> charToId;
    private final Map<Integer, String> idToChar;

    private CharTokenizer(Map<String, Integer> charToId) {
        this.charToId = charToId;
        this.idToChar = new HashMap<>();
        charToId.forEach((k, v) -> idToChar.put(v, k));
    }

    public static CharTokenizer load(String path) throws IOException {
        String json = Files.readString(Path.of(path));
        return new CharTokenizer(parseJsonMap(json));
    }

    /** Encode input string character by character (no BOS/EOS added). */
    public List<Integer> encodeChars(String text) {
        List<Integer> ids = new ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            String ch = new String(Character.toChars(cp));
            ids.add(charToId.getOrDefault(ch, UNK_ID));
            i += Character.charCount(cp);
        }
        return ids;
    }

    /** Decode token IDs to string, skipping special tokens. */
    public String decode(List<Integer> ids) {
        StringBuilder sb = new StringBuilder();
        for (int id : ids) {
            String tok = idToChar.get(id);
            if (tok != null && !tok.startsWith("[")) {
                sb.append(tok);
            }
        }
        return sb.toString();
    }

    public int vocabSize() {
        return charToId.size();
    }

    /**
     * Minimal JSON parser for flat string-to-int maps.
     * Handles Unicode escapes in keys (backslash-u followed by 4 hex digits).
     */
    private static Map<String, Integer> parseJsonMap(String json) {
        Map<String, Integer> map = new HashMap<>();
        json = json.trim();
        if (json.startsWith("{")) json = json.substring(1);
        if (json.endsWith("}")) json = json.substring(0, json.length() - 1);

        int i = 0;
        while (i < json.length()) {
            // Skip whitespace and commas
            while (i < json.length() && (Character.isWhitespace(json.charAt(i)) || json.charAt(i) == ',')) i++;
            if (i >= json.length() || json.charAt(i) != '"') { i++; continue; }

            // Read key string
            i++; // skip opening "
            StringBuilder key = new StringBuilder();
            while (i < json.length() && json.charAt(i) != '"') {
                if (json.charAt(i) == '\\') {
                    i++;
                    if (i >= json.length()) break;
                    char esc = json.charAt(i);
                    if (esc == 'u' && i + 4 < json.length()) {
                        String hex = json.substring(i + 1, i + 5);
                        key.append((char) Integer.parseInt(hex, 16));
                        i += 5;
                        continue;
                    }
                    key.append(esc);
                } else {
                    key.append(json.charAt(i));
                }
                i++;
            }
            i++; // skip closing "

            // Skip ':' and whitespace
            while (i < json.length() && (json.charAt(i) == ':' || Character.isWhitespace(json.charAt(i)))) i++;

            // Read integer value
            StringBuilder val = new StringBuilder();
            while (i < json.length() && (Character.isDigit(json.charAt(i)) || json.charAt(i) == '-')) {
                val.append(json.charAt(i));
                i++;
            }

            if (!val.isEmpty()) {
                map.put(key.toString(), Integer.parseInt(val.toString()));
            }
        }
        return map;
    }
}
