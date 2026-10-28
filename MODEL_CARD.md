# Model card: fcitx5-slm-ime conversion model (epoch 8)

A small character-level Transformer (encoder-decoder, 59,822,044 parameters) that converts typed hiragana to
kanji-kana text with phrasal unit (bunsetsu) boundaries `|`. It reads the hiragana to convert together with the
text before the cursor, and it runs on a CPU.

## Files

| File | Content |
|---|---|
| `fcitx5-slm-ime-model-torchscript-epoch8.zip` | The files the inference server in `djl/` loads: `encoder_m512-12.pt`, `decoder_m512-12.pt`, `crosskv_m512-12.pt`, `step_m512-12.pt` and `tokenizer.json`, in a directory `model/`. Unzip it in the directory from which the server is started. The Debian package `slm-ime-model` holds the same files. |
| `fcitx5-slm-ime-weights-epoch8.pt` | The weights, the model settings and the vocabulary size as a PyTorch checkpoint (`model_state`, `args`, `vocab_size`, `epoch`, `val_loss`), for training further or for exporting again with `export_torchscript.py`. The state of the optimizer is not included. |
| `tokenizer.json` | The character vocabulary (10,204 entries). The checkpoint needs it. |
| `SHA256SUMS` | Checksums of the files above. |

## Input and output

Input: the last 48 characters before the cursor, a separator (`\x1f`, which the tokenizer turns into the
`[SEP]` token), the hiragana to convert, and the end-of-sequence token. The text before the cursor may be
empty. Output: the kanji-kana text for the hiragana, with `|` between phrasal units (bunsetsu). The maximum input length is
192 characters. Decoding is greedy.

## Training

- Data: 2,486,631 training rows and 23,867 validation rows made from sentences of the Japanese Wikipedia. Each row is a
  stretch of one sentence that starts and ends at phrasal unit (bunsetsu) boundaries, with the text before it as the
  context (20 % of the rows have an empty context). 20 % of the training rows were chosen to get one more row whose
  reading stops inside the okurigana or particles at the end of its last phrasal unit (bunsetsu), as when the user has
  typed only a part of them; a row whose last phrasal unit has no such kana tail gets none, so 227,151 rows were added
  (2,259,480 rows without them). The reading of every sentence and the kanji-kana text with phrasal unit (bunsetsu) boundaries
  were made by a large language model (Gemma 4, Apache License 2.0); they were not written by hand. The data is not
  distributed.
- Settings: `d_model` 512, 8 heads, 6 encoder layers, 6 decoder layers, `d_ff` 2048, 8 epochs of 38,777 steps at batch
  size 64.
- The checkpoint of epoch 7 has the lowest validation loss (0.1309; epoch 8: 0.1313). The two were measured on the same
  rows, and epoch 8 has the lower character error rate (4.05 % against 4.13 %), so epoch 8 is the one that is
  released.

## Accuracy

The examples are 4,396 stretches of sentences of the Japanese Wikipedia that were not used for training. Mozc and Anthy get the reading of an example; the model gets the reading and the text before the cursor. The character error rate is the edit distance between the output and the correct text, divided by the length of the correct text (symbols are unified); the smaller, the better. The exact match is the share of the examples whose output is the same as the correct text.

| System | Character error rate | Exact match |
|---|---|---|
| This model, with the text before the cursor | 4.05 % | 75.5 % |
| This model, empty text before the cursor (4,401 examples) | 4.66 % | 73.5 % |
| Mozc | 8.99 % | 56.5 % |
| Anthy (no private dictionary) | 13.40 % | 47.1 % |

On 2,157 examples whose reading stops inside the okurigana or particles at the end of the last phrasal unit (bunsetsu), the model gives a character error rate of 4.98 % and an exact match of 70.2 %.

The correct texts were made by Gemma 4 and contain some errors. The measurement does not cover other kinds of text (books, chat, mail, technical writing).

## License and attribution

The weights are released under the Creative Commons Attribution-ShareAlike 4.0 International license
(CC BY-SA 4.0, https://creativecommons.org/licenses/by-sa/4.0/). They were trained on text of the Japanese
Wikipedia (https://ja.wikipedia.org/), which is licensed under CC BY-SA 4.0; the authors of the articles are
listed in the page histories of Wikipedia.
