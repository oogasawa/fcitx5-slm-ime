package com.github.oogasawa.slmime;

import java.util.List;

/**
 * Where the alternatives to the model's conversion of one bunsetsu come from.
 *
 * <p>The model gives one answer. When it is wrong, the user picks another candidate for that bunsetsu. The
 * candidates are made from the reading of the bunsetsu by a dictionary-based converter, so that rare words,
 * single kanji and proper nouns, which the model is weak at, can still be reached.</p>
 */
@FunctionalInterface
public interface CandidateSource {

    /**
     * @param reading the typed hiragana of one bunsetsu
     * @param limit the largest number of candidates wanted
     * @return the candidates, best first, without duplicates; empty if there are none
     */
    List<String> candidates(String reading, int limit);

    /** A source with no candidates. */
    CandidateSource NONE = (reading, limit) -> List.of();
}
