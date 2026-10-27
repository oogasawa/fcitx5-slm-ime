package com.github.oogasawa.slmime;

import java.util.Arrays;
import java.util.List;

/**
 * Splits a kanji string into bunsetsu (Japanese phrasal units).
 *
 * The M-512-12 model is trained to output '|' as a bunsetsu boundary marker.
 * This class simply splits on that marker — no external tools are used.
 *
 * Example: "機械学習|の|アルゴリズム" → ["機械学習", "の", "アルゴリズム"]
 */
public class BunsetsuSegmenter {

    /**
     * Splits the model output on '|' and returns the resulting segments.
     * If the text contains no '|', the whole string is returned as a single segment.
     */
    public List<String> segment(String kanjiText) {
        if (kanjiText == null || kanjiText.isBlank()) {
            return List.of(kanjiText == null ? "" : kanjiText);
        }
        String[] parts = kanjiText.split("\\|", -1);
        return Arrays.asList(parts);
    }
}
