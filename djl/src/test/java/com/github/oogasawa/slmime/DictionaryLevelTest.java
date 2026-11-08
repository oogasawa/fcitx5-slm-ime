package com.github.oogasawa.slmime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** {@code -Ddictionary.level}: which dictionaries each level uses, and that a misspelt level is refused. */
class DictionaryLevelTest {

    @Test
    void theValuesOfTheOptionNameTheLevels() {
        assertEquals(DictionaryLevel.ANTHY, DictionaryLevel.of("anthy"));
        assertEquals(DictionaryLevel.EXTENDED, DictionaryLevel.of("extended"));
        assertEquals(DictionaryLevel.HINTS, DictionaryLevel.of("hints"));
    }

    @Test
    void eachLevelHoldsTheOnesBeforeIt() {
        assertFalse(DictionaryLevel.ANTHY.privateDictionary());
        assertFalse(DictionaryLevel.ANTHY.hints());
        assertTrue(DictionaryLevel.EXTENDED.privateDictionary());
        assertFalse(DictionaryLevel.EXTENDED.hints());
        assertTrue(DictionaryLevel.HINTS.privateDictionary());
        assertTrue(DictionaryLevel.HINTS.hints());
    }

    @Test
    void aMisspeltLevelIsRefusedRatherThanTakenForAnother() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> DictionaryLevel.of("extend"));
        assertTrue(e.getMessage().contains("extend"), e.getMessage());
    }

    @Test
    void withoutTheOptionTheLevelIsTheOneOfBefore() {
        String saved = System.getProperty("dictionary.level");
        try {
            System.clearProperty("dictionary.level");
            assertEquals(DictionaryLevel.HINTS, DictionaryLevel.fromProperty());
            System.setProperty("dictionary.level", "extended");
            assertEquals(DictionaryLevel.EXTENDED, DictionaryLevel.fromProperty());
        } finally {
            if (saved == null) {
                System.clearProperty("dictionary.level");
            } else {
                System.setProperty("dictionary.level", saved);
            }
        }
    }
}
