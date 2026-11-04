package com.github.oogasawa.slmime;

import com.worksap.nlp.sudachi.Config;
import com.worksap.nlp.sudachi.Dictionary;
import com.worksap.nlp.sudachi.DictionaryFactory;
import com.worksap.nlp.sudachi.Morpheme;
import com.worksap.nlp.sudachi.Tokenizer;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Texts cut into words with their readings, by Sudachi with a system dictionary of SudachiDict: what
 * {@link Predictions} needs to find a text by the reading the user is typing. The texts of the conversation logs
 * have no readings.
 */
final class Readings implements AutoCloseable {

    /** A word of a text: as written, its reading in hiragana (empty when Sudachi has none in kana), and its part of
     *  speech (the first level of Sudachi's, such as 名詞 or 助詞). */
    record Word(String surface, String reading, String partOfSpeech) {
    }

    private final Dictionary dictionary;
    private final Tokenizer tokenizer;

    /** @param systemDictionary the {@code system.dic} of SudachiDict (core or full) */
    Readings(Path systemDictionary) throws IOException {
        dictionary = new DictionaryFactory().create(Config.defaultConfig().systemDictionary(systemDictionary));
        tokenizer = dictionary.create();
    }

    /** The words of a text, longest units (split mode C), so that a compound noun is one word. */
    List<Word> words(String text) {
        List<Word> words = new ArrayList<>();
        for (Morpheme m : tokenizer.tokenize(Tokenizer.SplitMode.C, text)) {
            words.add(new Word(m.surface(), hiragana(m.readingForm()), m.partOfSpeech().get(0)));
        }
        return words;
    }

    /** Katakana in hiragana, and empty when the reading holds anything but kana and ー. */
    static String hiragana(String katakana) {
        StringBuilder out = new StringBuilder(katakana.length());
        for (int i = 0; i < katakana.length(); i++) {
            char c = katakana.charAt(i);
            if (c >= 'ァ' && c <= 'ヶ') {
                out.append((char) (c - 0x60));
            } else if ((c >= 'ぁ' && c <= 'ゖ') || c == 'ー') {
                out.append(c);
            } else {
                return "";
            }
        }
        return out.toString();
    }

    @Override
    public void close() throws IOException {
        dictionary.close();
    }
}
