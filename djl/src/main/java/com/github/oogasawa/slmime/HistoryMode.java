package com.github.oogasawa.slmime;

/**
 * How the user's earlier choices (the history) are used and added to ({@code -Dhistory.mode}).
 *
 * <ul>
 *   <li>{@link #SUBSTITUTE}: put into the answer of the model wherever their reading is
 *       ({@link HistorySubstitution}); the default.</li>
 *   <li>{@link #HINTS}: given to the model as hints {@code reading~text}, for a model trained to judge them by the
 *       text around them (prep/add_hints.py --history-table).</li>
 *   <li>{@link #BOTH}: both of the above.</li>
 *   <li>{@link #OFF}: the history is neither used nor added to. The choice the user made for a reading is not put
 *       first in its candidates, and what the engine reports with {@code /api/record} is not kept. Choices made
 *       before are not lost: the history file is only left alone.</li>
 * </ul>
 */
public enum HistoryMode {
    SUBSTITUTE(false, true, true),
    HINTS(true, false, true),
    BOTH(true, true, true),
    OFF(false, false, false);

    private final boolean hints;
    private final boolean substitution;
    private final boolean used;

    HistoryMode(boolean hints, boolean substitution, boolean used) {
        this.hints = hints;
        this.substitution = substitution;
        this.used = used;
    }

    /** Whether the earlier choices are given to the model as hints. */
    public boolean hints() {
        return hints;
    }

    /** Whether the earlier choices are put into the answer of the model. */
    public boolean substitution() {
        return substitution;
    }

    /** Whether the history is used or added to at all: false only for {@link #OFF}. */
    public boolean used() {
        return used;
    }

    /**
     * The mode a {@code history.mode} value names. A word that names no mode means {@link #BOTH}, as it always has:
     * a value other than {@code substitute} turned the hints on and a value other than {@code hints} turned the
     * substitution on.
     */
    public static HistoryMode of(String name) {
        return switch (name) {
            case "substitute" -> SUBSTITUTE;
            case "hints" -> HINTS;
            case "off" -> OFF;
            default -> BOTH;
        };
    }

    /** The mode of {@code -Dhistory.mode}, {@link #SUBSTITUTE} when it is not given. */
    public static HistoryMode fromProperty() {
        return of(System.getProperty("history.mode", "substitute"));
    }
}
