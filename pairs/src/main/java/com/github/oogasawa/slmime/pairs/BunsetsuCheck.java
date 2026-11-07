// Copied from plugin-kana-kanji of Turing-workflow-plugins (https://github.com/scivicslab/Turing-workflow-plugins),
// commit 5fabd30, the version that made the training pairs of the releases (Apache License 2.0, the same author).
// Only the package, ActionResult (now in this package) and the headers for our own GPU broker were changed.
package com.github.oogasawa.slmime.pairs;

import java.util.Set;

/**
 * Checks on a line whose bunsetsu are separated by {@code |}.
 *
 * <p>A bunsetsu is one independent word with the particles and auxiliaries that follow it
 * ("現代の", "フォントでも、"). A segment made only of particles or punctuation means the
 * line was split into words instead, which teaches the model boundaries an IME never shows.
 * A plain POJO with no state.</p>
 */
public final class BunsetsuCheck {

    private static final Set<String> PARTICLES = Set.of(
            "は", "が", "を", "に", "へ", "と", "で", "の", "も", "や", "か", "ね", "よ",
            "から", "まで", "より", "など", "だけ", "ばかり", "しか", "ので", "のに");

    private static final String PUNCTUATION = "、。，．,.!?！？";

    private BunsetsuCheck() {
    }

    /** True if some segment is only particles or punctuation, so it should be part of the segment before it. */
    public static boolean hasLoneParticle(String line) {
        for (String segment : line.split("\\|", -1)) {
            String bare = stripPunctuation(segment.strip());
            if (segment.isBlank()) {
                continue;
            }
            if (bare.isEmpty() || PARTICLES.contains(bare)) {
                return true;
            }
        }
        return false;
    }

    /** True if both lines have the same number of {@code |}, so segment n of one answers segment n of the other. */
    public static boolean sameBoundaryCount(String reading, String kanji) {
        return count(reading) == count(kanji);
    }

    private static int count(String line) {
        int n = 0;
        for (int i = 0; i < line.length(); i++) {
            if (line.charAt(i) == '|') {
                n++;
            }
        }
        return n;
    }

    private static String stripPunctuation(String segment) {
        int end = segment.length();
        while (end > 0 && PUNCTUATION.indexOf(segment.charAt(end - 1)) >= 0) {
            end--;
        }
        return segment.substring(0, end);
    }
}
