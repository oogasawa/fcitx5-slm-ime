package com.github.oogasawa.slmime;

/**
 * Whether the user's earlier choices (the history) are used and added to ({@code -Dhistory.mode}).
 *
 * <ul>
 *   <li>{@link #SUBSTITUTE}: put into the answer of the model wherever their reading is
 *       ({@link HistorySubstitution}), and first in the candidates of a reading; what the user chooses is kept. The
 *       default.</li>
 *   <li>{@link #OFF}: the history is neither used nor added to. The choice the user made for a reading is not put
 *       into the answer or first in its candidates, and what the engine reports with {@code /api/record} is not
 *       kept. Choices made before are not lost: the history file is only left alone.</li>
 * </ul>
 *
 * <p>The same names as in version 3, which has the modes {@code hints} and {@code both} besides: this version gives
 * the model no hints, so it has only these two.</p>
 */
public enum HistoryMode {
    SUBSTITUTE(true),
    OFF(false);

    private final boolean used;

    HistoryMode(boolean used) {
        this.used = used;
    }

    /** Whether the history is used or added to at all: false only for {@link #OFF}. */
    public boolean used() {
        return used;
    }

    /** The mode a {@code history.mode} value names. A word that names no mode means {@link #SUBSTITUTE}. */
    public static HistoryMode of(String name) {
        return "off".equals(name) ? OFF : SUBSTITUTE;
    }

    /** The mode of {@code -Dhistory.mode}, {@link #SUBSTITUTE} when it is not given. */
    public static HistoryMode fromProperty() {
        return of(System.getProperty("history.mode", "substitute"));
    }
}
