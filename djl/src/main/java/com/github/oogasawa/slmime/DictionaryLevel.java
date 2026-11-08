package com.github.oogasawa.slmime;

/**
 * How many dictionaries are used besides the model ({@code -Ddictionary.level}). Each level holds the ones before it.
 *
 * <ul>
 *   <li>{@link #ANTHY}: the model and the dictionary that Anthy comes with.</li>
 *   <li>{@link #EXTENDED}: the above and the private dictionary of Anthy ({@code ~/.anthy/private_words_default}),
 *       which holds the words added to it.</li>
 *   <li>{@link #HINTS}: the above and the words of {@code -Dhints.dict}, given to the model as hints; the default,
 *       which is what the server did before this option.</li>
 * </ul>
 */
public enum DictionaryLevel {
    ANTHY(false, false),
    EXTENDED(true, false),
    HINTS(true, true);

    private final boolean privateDictionary;
    private final boolean hints;

    DictionaryLevel(boolean privateDictionary, boolean hints) {
        this.privateDictionary = privateDictionary;
        this.hints = hints;
    }

    /** Whether Anthy reads the private dictionary of the user. */
    public boolean privateDictionary() {
        return privateDictionary;
    }

    /** Whether the words of {@code -Dhints.dict} are given to the model. */
    public boolean hints() {
        return hints;
    }

    /**
     * The level a {@code dictionary.level} value names.
     *
     * @throws IllegalArgumentException for any other word: a misspelt level must not become another one silently
     */
    public static DictionaryLevel of(String name) {
        return switch (name) {
            case "anthy" -> ANTHY;
            case "extended" -> EXTENDED;
            case "hints" -> HINTS;
            default -> throw new IllegalArgumentException(
                    "-Ddictionary.level is anthy, extended or hints, not '" + name + "'");
        };
    }

    /** The level of {@code -Ddictionary.level}, {@link #HINTS} when it is not given. */
    public static DictionaryLevel fromProperty() {
        return of(System.getProperty("dictionary.level", "hints"));
    }
}
