# Model card: fcitx5-slm-ime conversion model (epoch 7)

A small character-level Transformer (encoder-decoder, 58,372,653 parameters) that converts typed hiragana to
kanji-kana text with bunsetsu (phrase) boundaries `|`. It reads the hiragana to convert together with the
text before the cursor, and it runs on a CPU.

## Files

| File | Content |
|---|---|
| `fcitx5-slm-ime-model-torchscript-epoch7.zip` | The files the inference server in `djl/` loads: `encoder_m512-12.pt`, `decoder_m512-12.pt`, `crosskv_m512-12.pt`, `step_m512-12.pt` and `tokenizer.json`, in a directory `model/`. Unzip it in the directory from which the server is started. |
| `fcitx5-slm-ime-weights-epoch7.pt` | The weights, the model settings and the vocabulary size as a PyTorch checkpoint (`model_state`, `args`, `vocab_size`, `epoch`, `val_loss`), for training further or for exporting again with `export_torchscript.py`. The state of the optimizer is not included. |
| `tokenizer.json` | The character vocabulary (9,261 entries). The checkpoint needs it. |
| `SHA256SUMS` | Checksums of the files above. |

## Input and output

Input: the last 48 characters before the cursor, a separator (`\x1f`, which the tokenizer turns into the
`[SEP]` token), the hiragana to convert, and the end-of-sequence token. The text before the cursor may be
empty. Output: the kanji-kana text for the hiragana, with `|` between bunsetsu. The maximum input length is
192 characters. Decoding is greedy.

## Training

- Data: 1,368,372 rows made from sentences of the Japanese Wikipedia. Each row is a stretch of one sentence
  that starts and ends at bunsetsu boundaries, with the text before it as the context (20 % of the rows have
  an empty context). The reading of every sentence and the kanji-kana text with bunsetsu boundaries were made by
  a large language model (Gemma 4, Apache License 2.0); they were not written by hand. The data is not
  distributed.
- Settings: `d_model` 512, 8 heads, 6 encoder layers, 6 decoder layers, `d_ff` 2048, 8 epochs of 21,347 steps
  at batch size 64. The checkpoint of epoch 7 has the lowest validation loss (0.1405); the model of epoch 8 gives
  the same character error rate.

## Accuracy

4,389 rows cut from test chunks of the same Wikipedia data. Character error rate (edit distance divided by the
length of the reference, symbols unified; smaller is better):

| System | Character error rate | Exact match |
|---|---|---|
| This model, with the text before the cursor | 4.58 % | 73.9 % |
| This model, empty context | 5.17 % | 72.1 % |
| Mozc (reading only) | 9.20 % | 55.0 % |
| Anthy (reading only) | 13.37 % | 47.2 % |

The measurement does not cover other kinds of text (books, chat, mail, technical writing). The reference
conversions were made by a language model and contain errors.

## License and attribution

The weights are released under the Creative Commons Attribution-ShareAlike 4.0 International license
(CC BY-SA 4.0, https://creativecommons.org/licenses/by-sa/4.0/). They were trained on text of the Japanese
Wikipedia (https://ja.wikipedia.org/), which is licensed under CC BY-SA 4.0; the authors of the articles are
listed in the page histories of Wikipedia.
