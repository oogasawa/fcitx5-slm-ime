# The fcitx5 engine of fcitx5-slm-ime

An input method engine (a fcitx5 addon, C++) for Japanese. It is the engine of this repository, `fcitx5-slm-ime`; in
its source and in the installed files it is still named `llm-ime`, its former name. It turns typed romaji into
hiragana, converts the reading and the text before the cursor with a small Transformer model computed on the CPU
inside the engine, and shows the answer one phrasal unit (bunsetsu) at a time, with candidates to choose from.

## How it works

```
keys (romaji)
   |  src/llm-ime-engine.cpp: romaji -> hiragana, preedit, candidate window, keys of the chosen profile
   v
src/local-conversion.cpp: the conversion, read in the background when fcitx5 starts
   |  src/conversion/: the source of the model, the reading of every phrasal unit, the user's choices,
   |                   the candidates of Anthy put in order by the model
   v
src/model/: the Transformer (Eigen), weights from /usr/share/slm-ime/model/slm-ime-weights.bin
```

- The text before the cursor is read from the application through fcitx5 `SurroundingText` (the last 500
  characters before the cursor). When the application does not provide it, the text the user committed before in the
  same input field is used instead. In password fields and fields marked sensitive, nothing is read or kept.
- At commit time the engine learns the phrasal units (bunsetsu) that the user changed: a candidate other than the
  first was taken, or a border was moved so that the reading is new. The choices are kept in
  `~/.local/share/slm-ime/user_history.tsv` (readable by the user only) and come first the next time.
- The model directory is `/usr/share/slm-ime/model`, or the directory named by the environment variable
  `SLM_IME_MODEL_DIR` of fcitx5. The number of threads of the large products is `SLM_IME_THREADS` (4 by default).

## Keys

The keys are set in `fcitx5-configtool` by profile (Default, MS-IME, ATOK) or one by one (Custom); the operations and
the keys of each profile are in `src/key-actions.h`. The keys of the Default profile:

| Key | Action |
|---|---|
| letters | type romaji (the hiragana is shown in the preedit) |
| Space | start conversion; in conversion mode, the next candidate |
| Ctrl+Enter | convert the whole reading with the model only |
| Enter | commit (the hiragana as typed, or the converted text) |
| Escape | forget the reading; in conversion mode, back to the reading |
| Backspace / Delete | delete the kana before / after the cursor |
| Left / Right, Home / End | while typing: move the cursor in the reading; in conversion mode: another phrasal unit |
| Shift+Left / Shift+Right | shorten / extend the phrasal unit (bunsetsu) |
| Up / Down, 1 to 9 | choose a candidate |
| F6 to F10 | the phrasal unit in hiragana, katakana, half-width katakana, full-width letters, letters |
| Ctrl+Shift+Insert | right after a commit: take it back into conversion |

Whether Ctrl+Enter reaches the engine depends on the application and on the fcitx5 settings. The engine does not
write a log of the keys.

## Build and install

Requirements: fcitx5, CMake 3.16 or later, a C++17 compiler, Eigen 3.3 or later, Anthy (`anthy` or
`anthy-unicode`), `nlohmann-json` 3.2 or later, gettext.

```bash
# Ubuntu / Debian
sudo apt install fcitx5 libfcitx5core-dev libfcitx5config-dev libfcitx5utils-dev fcitx5-modules-dev \
                 nlohmann-json3-dev libeigen3-dev libanthy-dev gettext cmake g++ pkg-config

mkdir -p build && cd build
cmake .. -DCMAKE_PREFIX_PATH=/usr -DCMAKE_BUILD_TYPE=Release
make
ctest                    # the tests that need neither fcitx5 nor the model
sudo make install        # installs llm-ime.so, the addon file, the input method file and the translations
```

With the default install prefix `/usr/local`, the library is installed outside the directory in which
Ubuntu's fcitx5 looks for addon libraries (`/usr/lib/x86_64-linux-gnu/fcitx5`, or the directories listed in
the environment variable `FCITX_ADDON_DIRS`). On the machines where this engine runs, a link made once
there is what makes fcitx5 load it:

```bash
sudo ln -s /usr/local/lib/fcitx5/llm-ime.so /usr/lib/x86_64-linux-gnu/fcitx5/llm-ime.so
```

A running fcitx5 reads the addon files only at start-up, so restart it (`fcitx5 -r -d`) before adding the
input method. Add `llm-ime` to the group with `fcitx5-configtool`. Editing `~/.config/fcitx5/profile` while
fcitx5 is running does not work: fcitx5 writes its own copy over the file when it exits.
The model must be in `/usr/share/slm-ime/model`; see the README at the top of this repository.

`model-check` compares the C++ computation with PyTorch: set `SLM_IME_WEIGHTS` to the weights and `SLM_IME_GOLDEN` to
the cases written by `../eval/make_cpp_golden.py` (it is skipped without them).

## License

Apache License 2.0 (see `../LICENSE`).

Dependencies: fcitx5 core (`Fcitx5Core`), LGPL-2.1 or later; Eigen, MPL-2.0; Anthy, LGPL-3 or later (part of its
dictionary data is GPL-2); `nlohmann_json`, MIT.
