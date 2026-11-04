package com.github.oogasawa.slmime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The server must give the model the source it was trained on. cases.tsv holds sources that prep/add_hints.py made
 * (text before the cursor TAB reading TAB source) from test rows of the training data, with and without hints, and
 * a few made-up ones at the limits of the length (one with 200 characters before the cursor, which the server cuts
 * to 100 first, as the training rows were cut: its source was made by add_hints.py from the last 100). table.tsv
 * holds the words of hard_words.py --keep-compounds whose readings those readings hold, which give the same hints as
 * the whole table.
 */
class DictionaryHintsTest {

    private static Path resource(String name) throws Exception {
        return Paths.get(DictionaryHintsTest.class.getResource("/hints/" + name).toURI());
    }

    @Test
    void theSourcesAreThoseOfTheTrainingRows() throws Exception {
        DictionaryHints hints = DictionaryHints.load(resource("table.tsv"));
        List<String> cases = Files.readAllLines(resource("cases.tsv"), StandardCharsets.UTF_8);
        int hinted = 0;
        for (String line : cases) {
            String[] f = line.split("\t", 3);
            String source = SourceIds.text(f[0], f[1], 100, hints.of(f[1]), 192);
            assertEquals(f[2], source, "reading " + f[1]);
            hinted += source.chars().filter(c -> c == SourceIds.SEP_CHAR).count() == 2 ? 1 : 0;
        }
        assertTrue(hinted >= 300, hinted + " cases with hints");
    }

    @Test
    void theLongestReadingsComeFirstAndEachWordOnce() throws Exception {
        Path table = Files.createTempFile("hints", ".tsv");
        Files.writeString(table, String.join("\n",
            "きんきゅうじ\t緊急時\trare",
            "きんきゅうじたい\t緊急事態\trare",
            "きゅうじたい\t旧字体\trare",
            "きゅうじたい\t緊急事態\trare",
            "こうたい\t交代劇\trare",
            "あいうえおか\t二字\trare"), StandardCharsets.UTF_8);
        List<DictionaryHints.Hint> found = DictionaryHints.load(table).of("きんきゅうじたいにもこうたいあいうえおか");
        assertEquals("[きんきゅうじたい=緊急事態, きんきゅうじ=緊急時, きゅうじたい=旧字体]", found.toString());
    }

    @Test
    void theTextBeforeTheCursorIsCutToStayWithinTheLongestSource() {
        List<DictionaryHints.Hint> one = List.of(new DictionaryHints.Hint("あゆついしょう", "阿諛追従"));
        String reading = "あゆついしょうがすぎる";
        String source = SourceIds.text("あ".repeat(100), reading, 100, one, 30);
        assertEquals(30, source.codePointCount(0, source.length()));
        assertTrue(source.startsWith("あゆついしょう=阿諛追従\u001f"));
        assertTrue(source.endsWith("\u001f" + reading));
        // Hints and reading alone too long: the source without hints
        assertEquals("い\u001f" + reading, SourceIds.text("い", reading, 100, one, 20));
    }

    @Test
    void aChoiceOfTheUserIsWrittenWithATilde() {
        List<DictionaryHints.Hint> given = List.of(new DictionaryHints.Hint("かんせいこんすい", "肝性昏睡"),
                new DictionaryHints.Hint("かんじをかく", "感じを書く", '~'));
        // as prep/add_hints.py writes the rows with --history-table
        assertEquals("かんせいこんすい=肝性昏睡 かんじをかく~感じを書く\u001f\u001fかんじをかく",
                SourceIds.text("", "かんじをかく", 100, given, 192));
    }
}
