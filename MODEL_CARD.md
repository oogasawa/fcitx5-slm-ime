# Model card: fcitx5-slm-ime conversion model (version 2)

A small character-level Transformer (encoder-decoder, 60,953,723 parameters) that converts typed hiragana to
kanji-kana text with phrasal unit (bunsetsu) boundaries `|`. It reads the hiragana to convert together with the
text before the cursor, and it runs on a CPU. The engine of version 2 computes it in C++ (`engine/src/model/`).

## Files

| File | Content |
|---|---|
| `slm-ime-weights.bin` | The weights, written by `export_weights.py`: a JSON header with the size of the model and the place of every tensor, then the tensors as little-endian float32. |
| `tokenizer.json` | The character vocabulary (10,875 entries). |

The Debian and Fedora package `slm-ime-model` installs both in `/usr/share/slm-ime/model/`.

## Input and output

Input: the last 100 characters before the cursor, a separator (`\x1f`, which the tokenizer turns into the `[SEP]`
token), the hiragana to convert, and the end-of-sequence token. The text before the cursor may be empty. Output: the
kanji-kana text for the hiragana, with `|` between phrasal units (bunsetsu). Decoding is greedy.

## Training

- Data: about 5.3 million training rows, about half from each source: 2.4 million from the Japanese Wikipedia and 2.9
  million from FineWeb-2 (text of web pages). Each row is a stretch of one sentence that starts and ends at phrasal
  unit (bunsetsu) boundaries, with the text before it as the context (20 % of the rows have an empty context). 20 %
  of the training rows were chosen to get a twin whose reading stops inside the okurigana or particles at the end of
  its last phrasal unit, as when the user has typed only a part of them; a row whose last unit has no such kana tail
  gets none. The reading of every sentence and the phrasal unit
  boundaries were made by a large language model (Gemma 4, `google/gemma-4-26B-A4B-it`, Apache License 2.0) and
  checked against KANJIDIC2, JMdict and Sudachi; they were not written by hand. The pairs are published at
  https://huggingface.co/datasets/oogasawa/fcitx5-slm-ime-training-data.
- Settings: `d_model` 512, 8 heads, 6 encoder layers, 6 decoder layers, `d_ff` 2048, dropout 0.1, longest input 192
  characters, AdamW with a one-cycle schedule (learning rate 3e-4), batch size 64, 8 epochs, bfloat16.
- The released weights are those of epoch 8, which also has the lowest validation loss (0.0841).
- `prep/train_from_scratch.sh` makes a model of this kind from the raw corpora; the section "Train" of `README.md`
  describes its parts. Its result is not these weights bit for bit: the training does not fix its random seed, and the
  answers of the language model depend on the server.

## Accuracy and speed

See the sections "Accuracy" and "Speed" of `README.md`: the conversion is compared with Mozc and Anthy on sentences
of the Japanese Wikipedia and of FineWeb-2 that were not used for training, by the length of the reading. The
correct texts are the original sentences, whose readings were written by Gemma 4; the ideal for this program is the
accuracy of Gemma 4, on a PC without a GPU. The measurement does not cover other kinds of text (books, chat, mail,
technical writing).

## License and attribution

The weights are released under the Creative Commons Attribution-ShareAlike 4.0 International license
(CC BY-SA 4.0, https://creativecommons.org/licenses/by-sa/4.0/). They were trained on text of the Japanese
Wikipedia (https://ja.wikipedia.org/), which is licensed under CC BY-SA 4.0 (the authors of the articles are listed
in the page histories of Wikipedia), and of the Japanese part of FineWeb-2
(https://huggingface.co/datasets/HuggingFaceFW/fineweb-2), which is licensed under ODC-By 1.0. The readings were
checked with KANJIDIC2 and JMdict (CC BY-SA 4.0, the Electronic Dictionary Research and Development Group) and Sudachi
(SudachiDict, Apache License 2.0).
