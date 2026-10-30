# fcitx5-slm-ime

A Japanese kana-to-kanji input method editor (IME) powered by a small language model (SLM): a character-level
Transformer (encoder-decoder) of about 60 million parameters that converts hiragana into kanji-kana text. The model
is small enough to run on a CPU only; no GPU is needed. It runs on Linux, on top of fcitx5.


Conventional Japanese input methods first cut the typed hiragana into words with a morphological analyzer, and choose
the kanji using that cut. However, a correct cut requires understanding the meaning of the whole sentence, and
understanding the meaning of the whole sentence requires a correct cut: there is a circular dependency.

This program avoids this problem by using a small language model directly: the small language model reads the typed
hiragana and writes the kanji-kana text together with the phrasal unit (bunsetsu) boundaries in one pass, with no
separate morphological analyzer.








## How it works

```
fcitx5 engine (engine/, C++)
   | hiragana + text before the cursor        (HTTP, JSON)
   v
inference server (djl/, Java, DJL + TorchScript, CPU)
   | source = <last 48 characters before the cursor> [SEP] <reading> [EOS]
   v
conversion model (Transformer encoder-decoder, greedy decoding)
   | kanji-kana text with phrasal unit (bunsetsu) boundaries, e.g.  機械学習|の|アルゴリズム
   v
reading of each phrasal unit (bunsetsu) is found by alignment; candidates = the choice the user made before
for the same reading, the model output, then Anthy's candidates in the order the model scores them
```

- The model is trained on pairs of a reading and the kanji-kana text that a large LLM (Gemma 4) made
  from sentences of the Japanese Wikipedia and of FineWeb-2 (Japanese web pages: blogs, shops, diaries). The training rows imitate real typing: the reading starts
  at a phrasal unit (bunsetsu) boundary inside a sentence, and the text before it is given as context
  (20 % of the rows have an empty context).
- When the user changes a phrasal unit (bunsetsu), by picking another candidate or by moving a border, the pair
  (reading, chosen text) is kept in a history file and comes first the next time the same reading appears.
  The model is not changed by it.
- When the application does not tell the text before the cursor, the engine sends the text the user committed
  before in the same input field instead. Ctrl+Shift+Insert takes the last commit back into conversion.

## Accuracy

We compared the accuracy of kanji conversion with Mozc and Anthy.

The character error rate is the edit distance between the output and the correct text, divided by the length of the correct text. The exact match is the share of the examples whose output is the same as the correct text. The examples are 4,396 stretches of sentences of the Japanese Wikipedia that were not used for training.

| System | Character error rate | Exact match |
|---|---|---|
| This program (1.1.0, 1.2.0) | 3.90 % | 76.3 % |
| This program (1.0.0) | 4.05 % | 75.5 % |
| Mozc | 8.99 % | 56.5 % |
| Anthy | 13.40 % | 47.1 % |

On 5,000 stretches of FineWeb-2 sentences that were not used for training, everyday text of web pages, the character error rate is 4.89 % for 1.1.0 and 7.25 % for 1.0.0, which was trained on the Japanese Wikipedia only; the exact match is 74.3 % and 65.1 %.

The validation loss is the loss measured on sentences that were not used for training; the smaller, the better. The validation sentences of 1.1.0 include FineWeb-2 sentences, so its losses cannot be compared with those of 1.0.0 directly.

| Epoch | Validation loss (1.1.0) | Validation loss (1.0.0) |
|---|---|---|
| 1 | 0.1967 | 0.2412 |
| 2 | 0.1635 | 0.1974 |
| 3 | 0.1483 | 0.1776 |
| 4 | 0.1337 | 0.1577 |
| 5 | 0.1211 | 0.1438 |
| 6 | 0.1119 | 0.1342 |
| 7 | 0.1072 | 0.1309 |
| 8 | 0.1067 | 0.1313 |

The training set of 1.1.0 has 5,400,012 examples, from the Japanese Wikipedia and FineWeb-2, and the model has about 60 million parameters. The correct texts were made by Gemma 4, a large language model that needs GPUs, and Gemma 4 does not convert perfectly either. The ideal for this program is the accuracy of Gemma 4, on a PC without a GPU.

## Speed

One conversion takes 70 to 260 ms on the CPU alone of a mini PC, the ASUS MINIPC PN51-S1: AMD Ryzen 7 5700U (8 cores, 16 threads, 1.8 GHz base clock, up to 4.37 GHz), 32 GB of dual-channel DDR4-3200 memory, no GPU. This is fast enough for daily typing, and the wait is not noticeable in use. On an arm64 machine, the NVIDIA DGX Spark with 20 cores (10 Cortex-X925 and 10 Cortex-A725), one conversion takes 110 to 200 ms on the CPU alone. The package limits PyTorch to 8 threads there, because with a thread on each of the 20 cores one conversion takes about 0.85 s.

## Integration of Anthy for the alternative kanji

Once the SLM has decided the kanji-kana text and the phrasal units (bunsetsu), choosing among alternative kanji for
one phrasal unit is a well-defined job, and Anthy (`libanthy`) is exactly the right tool for it. Anthy is installed
together with this program and called from inside the conversion server, so the user does not set it up and does not
have to be aware of it. Choosing alternative kanji is the only job of Anthy: given the reading of one phrasal unit, it
reliably returns the alternative kanji. It is simple and robust, and it has done its work for many years. There is
nothing to improve by replacing something that already does its job this well, so this program does not reimplement
it; it relies on it. The SLM decides the phrasal units and gives the first candidate; Anthy supplies the other
candidates that the user steps through with the Space key. The order of the candidates for a phrasal unit is: what the
user chose before for the same reading, the SLM's answer, and then Anthy's candidates, which the SLM puts in order by
how likely it finds each of them after the text before the phrasal unit. Because Anthy has a dictionary,
it also reaches rare words, single kanji and proper nouns that a small model is weak at.

## Key bindings

The keys are set in `fcitx5-configtool`: open the configuration of `SLM IME`. The key binding profile is one of
`Default`, `MS-IME`, `ATOK` and `Custom`. The `MS-IME` and `ATOK` profiles follow the key binding styles of those
input methods as the style files of fcitx5-anthy describe them, for the operations this program has. With `Custom`,
the keys of every operation are the ones listed under "Keys of the Custom profile", which start as those of
`Default` and can be changed one by one. The settings are kept in `~/.config/fcitx5/conf/llm-ime.conf`.

| Operation | Default | MS-IME | ATOK |
|---|---|---|---|
| Convert | Space | Space, Shift+Space, Henkan | Space, Henkan |
| Next / previous candidate | Space, Down / Up | Space, Tab, Henkan, Down / Up, Shift+Space | Space, Tab, Henkan / Up |
| Next / previous phrasal unit | Right / Left | Right, Ctrl+D / Left, Ctrl+S | Shift+Right / Shift+Left |
| Longer / shorter phrasal unit | Shift+Right / Shift+Left | Shift+Right, Ctrl+L / Shift+Left, Ctrl+E | Right, Ctrl+L / Left, Ctrl+K |
| Back to the reading | Esc | Esc, BackSpace | Esc, BackSpace |
| Hiragana, katakana, half-width katakana, full-width letters, letters | F6 to F10 | F6 to F10, Ctrl+U/I/O/P/T | F6 to F10, Ctrl+U/I/O/P/@ |
| The model's conversion of the whole reading | Ctrl+Enter | Ctrl+Enter | Ctrl+Enter |
| Take the last commit back | Ctrl+Shift+Insert | Ctrl+Shift+Insert | Ctrl+Shift+Insert |

F9 and F10 pressed again change the letters: lower case, upper case, the first letter in upper case. Letters put in
with F9 or F10 are not learned.

## Security

The conversion server listens on this machine only (`127.0.0.1:8190`), but other accounts on the machine and the web
pages a browser shows can send requests there too. So the server answers only the user's own engine: at every start
it writes a new random token to `~/.local/share/slm-ime/server-token`, which only the user can read, and refuses
requests without it or addressed to another host name. The history of the user's choices
(`~/.local/share/slm-ime/user_history.tsv`) is readable by the user only. In password fields and fields that the
application marks as sensitive nothing is learned and the text is not kept. Version 1.1.0 and earlier had no token:
upgrade, and restart fcitx5 (or log in again) after the upgrade, because an engine of 1.1.0 does not send the token.

## Install with the Debian packages

Tested on Ubuntu 24.04. The program consists of the following parts.

What this program distributes, as three Debian packages, each of which declares the packages that it needs:

- `fcitx5-slm-ime`, the engine that runs inside `fcitx5`
- `slm-ime-server`, the conversion server
- `slm-ime-model`, the weights and the tokenizer of the model

External components that the packages depend on, which `apt` installs:

- `fcitx5`, the input method framework, with `fcitx5-data`, `fcitx5-modules` and the `libfcitx5` libraries
- Anthy (`libanthy1t64`) with its dictionary (`anthy-common`)

External components that `slm-ime-server` contains, so that no Java is needed on the system and the `java` command
of the system is not changed:

- the Java runtime, made with `jlink`
- DJL (Deep Java Library), which calls PyTorch from Java
- the PyTorch native libraries

`apt` installs the declared packages by itself. But these three packages are on GitHub Releases and not in an `apt`
repository, so `apt` can find `slm-ime-server` and `slm-ime-model` only if their files are named on the command line.
The release has `fcitx5-slm-ime` and `slm-ime-server` for two CPU architectures, `amd64` and `arm64`, and one
`slm-ime-model` for both. `dpkg --print-architecture` prints the architecture of the machine. Download the three
`.deb` files for it from <https://github.com/oogasawa/fcitx5-slm-ime/releases> into one directory and run this
command in it:

```bash
sudo apt install ./fcitx5-slm-ime_*_$(dpkg --print-architecture).deb ./slm-ime-server_*_$(dpkg --print-architecture).deb ./slm-ime-model_*_all.deb
```

The conversion server is a systemd user service that starts at login and listens on `127.0.0.1:8190`, so log in
again after the installation (to start it at once: `systemctl --user start slm-ime-server`). Then restart fcitx5 and
add `SLM IME` to the input methods with `fcitx5-configtool`.

## Install from source

A Debian package contains the result of a build. This route does the work of the packages by hand: it builds the
conversion server from `djl/`, gets the model, builds the engine from `engine/`, and starts the server.

Requirements: a JDK 25 and Maven (to build the server); CMake 3.16 or later, a C++17 compiler, `libcurl`,
`nlohmann-json` 3.2 or later and the development files of fcitx5 (to build the engine); `libanthy.so.1` for the
extra candidates (optional). The commands for Ubuntu are in `engine/README.md`.

### Build the server

```bash
mkdir -p ~/slm-ime
cd djl
rm -rf target && mvn install            # builds target/slm-ime-server-1.2.0-shaded.jar and runs the tests
cp target/slm-ime-server-1.2.0-shaded.jar ~/slm-ime/
```

### Get the model

The server reads a model directory, so the model is placed in `~/slm-ime/model` (not in the `model/` of this
repository, which is the source of the Transformer). The release `v1.1.0` has the trained model, which 1.2.0 uses
as it is; to make the model yourself, see "Train".

```bash
cd ~/slm-ime
base=https://github.com/oogasawa/fcitx5-slm-ime/releases/download/v1.1.0
curl -LO $base/fcitx5-slm-ime-model-torchscript-epoch8.zip    # about 700 MB
curl -LO $base/SHA256SUMS
sha256sum -c --ignore-missing SHA256SUMS
unzip fcitx5-slm-ime-model-torchscript-epoch8.zip              # makes model/
```

The model directory must hold `encoder_m512-12.pt`, `decoder_m512-12.pt` and `tokenizer.json`; with
`crosskv_m512-12.pt` and `step_m512-12.pt` the decoder runs with a key-value cache. The model is described in
`MODEL_CARD.md`.

### Build the engine

Build and install the engine as written in "Build and install" of `engine/README.md`. In a source install the
engine is still named `llm-ime`, its former name.

### Start the server

The server listens on `127.0.0.1:8190`, where the engine looks for it. The first start downloads the native PyTorch
libraries to `~/.djl.ai`.

```bash
cd ~/slm-ime
java --enable-native-access=ALL-UNNAMED -Xmx2g \
     -Dmodel.context=true -Dmodel.dir=model -Dtokenizer=model/tokenizer.json \
     -jar slm-ime-server-1.2.0-shaded.jar --server 8190
```

At every start the server writes a new token to `$XDG_DATA_HOME/slm-ime/server-token` (by default
`~/.local/share/slm-ime/server-token`), where the engine reads it; see "Security".

## Options and requests of the conversion server

The options are Java system properties (`-D…`). With the Debian packages, put them into the environment variable
`SLM_IME_JAVA_OPTS` of the service (`systemctl --user edit slm-ime-server`, then
`Environment=SLM_IME_JAVA_OPTS=-Dlog.context=true`).

| Option | Meaning |
|---|---|
| `-Dmodel.context=true` | the model was trained with the text before the cursor (use `false` for a model trained without it) |
| `-Dmodel.contextChars=48` | how many characters before the cursor are given to the model |
| `-Dhistory.file=PATH` | file of the candidates the user chose (default `user_history.tsv`; empty = memory only) |
| `-Dtoken.file=PATH` | file of the token the engine must send (default `$XDG_DATA_HOME/slm-ime/server-token`, where the engine reads it) |
| `-Dlog.context=true` | log the reading and the text before the cursor of each request |

Every request but `GET /api/health` must carry the token in the header `X-SLM-IME-Token`.
Requests answered (the ones the fcitx5 engine sends): `POST /api/segment-convert`,
`POST /api/segment-candidates`, `POST /api/convert`, `POST /api/record`; `GET /api/predict` and
`POST /api/continue` are answered with empty results.

## Train

The model learns from rows of a reading and the kanji-kana text of that reading. The training data is not
distributed, so training starts from the Japanese Wikipedia: a large language model (Gemma 4) writes the reading and
the phrasal unit (bunsetsu) boundaries of its sentences, and the small model is trained to reproduce that answer from
the reading and the text before the cursor. Training has five parts. Each part reads the files that the part
before it wrote.

### Make the pairs from Wikipedia

A pair is a sentence as a reading and as kanji-kana text, both cut into phrasal units (bunsetsu) by `|`, and the
n-th unit of the reading answers the n-th unit of the kanji-kana text.

```bash
# 1. the prose paragraphs of the articles of the dump: one line "title TAB paragraph"
java -cp commons-compress.jar:commons-io.jar -Djdk.xml.maxGeneralEntitySizeLimit=0 -Djdk.xml.totalEntitySizeLimit=0 \
     wikipedia/WikiTextExtractor.java jawiki-latest-pages-articles.xml.bz2 > jawiki_text.tsv
# 2. chunk files wiki_<n>.tsv of 30 paragraphs each, the unit of the work in step 3
java wikipedia/WikiToChunks.java jawiki_text.tsv CHUNK_DIR
# 3. one worker takes chunks from a list (ORDER_FILE has one chunk path per line), has the language model write
#    the pairs, and leaves CHUNK_pairs.tsv; many workers can share one list (wikipedia/image/ is their image)
wikipedia/image/worker.sh BROKER_CHAT_URL TAG MODEL ORDER_FILE
```

Step 1 skips redirects and pages that are not articles, and removes the wiki markup with simple rules, which is
good enough for training text. In step 3 the workers run the workflow `wikipedia/image/kana-kanji-pairs.yaml` of
Turing workflow against an LLM server that is reached through the GPU broker of this project, so this step needs
such a server. `wikipedia/ReadingCheck.java` compares the readings of the pairs with two dictionaries and lists the
units that differ; it changes nothing.

### Make the training files

```bash
python3 prep/build_dataset_g.py PAIRS_DIR DATA_DIR --context-chars 48 --partial-end-percent 20
```

`PAIRS_DIR` holds the `*_pairs.tsv` files. The script cuts each sentence the way a user types it, from left to
right: a row is the reading of a few phrasal units (bunsetsu) from a random place in a sentence, put after the last
48 characters of the text before it and the separator `SEP`, and the answer is the kanji-kana text of that reading
with `|` kept. In 20 % of the rows (`--empty-percent`) the text before the cursor is left empty, so that the model
also converts well in applications that do not tell what is before the cursor. `--partial-end-percent` adds a twin
of a training row whose reading stops inside the okurigana or particles at the end of the last unit (for example
`せいどがよ` for `せいどがよい`), as when the user has typed only a part of them. The script writes `DATA_DIR/train.tsv`, `valid.tsv` and `test.tsv` (1 % each for valid and test by
default). A whole chunk goes to one of the three, chosen by a hash of its file name, because the sentences of a chunk
come from neighbouring articles and rows of one chunk in different files would leak the test text into training.

### Train the model

```bash
python3 -u train.py --data-dir DATA_DIR --run-name NAME --epochs 8 --batch-size 64 \
        --d-model 512 --nhead 8 --enc-layers 6 --dec-layers 6 --d-ff 2048 --max-len 192
```

The options give the size of the Transformer (512 dimensions, 8 heads, 6 encoder layers, 6 decoder layers; about 60
million parameters) and the longest input in characters. If `DATA_DIR` has no `tokenizer.json`, the script builds the
character vocabulary from `train.tsv` and saves it there. The optimizer is AdamW with a one-cycle schedule of the
learning rate (`--lr`, 3e-4 by default). After every epoch the script saves `checkpoints/NAME/last.pt` and the weights of that
epoch as `epoch_NN.pt`, and it saves `best.pt` when the loss on `valid.tsv` is the lowest so far. The forward pass
runs in bfloat16 by default (`--amp bf16`). On an RTX 4080, one epoch of 5.4 million rows takes about 91 minutes.

### Export the model for the server

```bash
python3 export_torchscript.py checkpoints/NAME/best.pt MODEL_DIR
cp DATA_DIR/tokenizer.json MODEL_DIR/
```

The inference server in `djl/` is written in Java and cannot read a PyTorch checkpoint, so the script converts the
trained model to TorchScript and writes four files in `MODEL_DIR`: the encoder, the decoder, and the two parts of the
decoder with a key-value cache. It checks, for inputs of several lengths, that the converted files give the same
output as the original model. The server also needs the tokenizer, which is copied by the second command. `MODEL_DIR`
is the model directory of "Start the server".

### Evaluate

```bash
python3 eval/greedy_check.py checkpoints/NAME DATA_DIR
```

The script converts the sentences of `test.tsv` with the checkpoint, in the same way as the Java server (greedy
decoding), and prints the character error rate, the share of exact matches, the error rate of the `|` marks alone, and
a few examples. `eval/compare_cut.py` compares models, Mozc and Anthy on the same rows.

## License

The source code is licensed under the Apache License 2.0 (see `LICENSE`).

The training data (pairs of a reading and kanji-kana text) and the trained model are licensed under
the Creative Commons Attribution-ShareAlike 4.0 International license (CC BY-SA 4.0,
https://creativecommons.org/licenses/by-sa/4.0/). They are made from the text of the Japanese
Wikipedia (https://ja.wikipedia.org/), which is licensed under CC BY-SA 4.0. The readings and the
phrasal unit (bunsetsu) boundaries were made by an LLM (Gemma 4, Apache License 2.0).

Main dependencies: DJL (`ai.djl`), Apache License 2.0; Anthy (`libanthy`), LGPL-3 or later
(part of its dictionary data is GPL-2).
