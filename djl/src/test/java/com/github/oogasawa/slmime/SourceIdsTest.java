package com.github.oogasawa.slmime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class SourceIdsTest {

    private static CharTokenizer tokenizer() throws Exception {
        Path file = Files.createTempFile("tokenizer", ".json");
        Files.writeString(file, "{\"[PAD]\": 0, \"[BOS]\": 1, \"[EOS]\": 2, \"[UNK]\": 3, \"[SEP]\": 4, "
                + "\"あ\": 5, \"い\": 6, \"漢\": 7, \"字\": 8}");
        return CharTokenizer.load(file.toString());
    }

    @Test
    void aModelWithoutContextReadsTheReadingAndEos() throws Exception {
        assertEquals(List.of(5, 6, 2), SourceIds.of(tokenizer(), "漢字", "あい", false, 48));
    }

    @Test
    void aModelWithContextReadsTheContextASeparatorTheReadingAndEos() throws Exception {
        assertEquals(List.of(7, 8, 4, 5, 6, 2), SourceIds.of(tokenizer(), "漢字", "あい", true, 48));
    }

    @Test
    void anEmptyContextStillGivesTheSeparator() throws Exception {
        assertEquals(List.of(4, 5, 2), SourceIds.of(tokenizer(), "", "あ", true, 48));
        assertEquals(List.of(4, 5, 2), SourceIds.of(tokenizer(), null, "あ", true, 48));
    }

    @Test
    void onlyTheEndOfALongContextIsKept() throws Exception {
        assertEquals(List.of(8, 4, 5, 2), SourceIds.of(tokenizer(), "漢字", "あ", true, 1));
    }

    @Test
    void anUnknownCharacterIsUnk() throws Exception {
        assertEquals(List.of(3, 2), SourceIds.of(tokenizer(), "", "ん", false, 48));
    }
}
