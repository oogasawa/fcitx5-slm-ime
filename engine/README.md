# The fcitx5 engine of fcitx5-slm-ime

An input method engine (a fcitx5 addon, C++) for Japanese. It is the engine of this repository, `fcitx5-slm-ime`; in its source and in the installed files it is still named `llm-ime`, its former name. It turns typed romaji into hiragana, sends
the reading and the text before the cursor to a conversion server over HTTP, and shows the answer
one phrasal unit (bunsetsu) at a time, with candidates to choose from. The conversion itself is done by the server.

It is developed against the inference server in `../djl` (a small neural model on a CPU), and it
also speaks to the older backend `quarkus-llm-ime` (Mozc and vLLM; see `docs/legacy-backend-setup.ja.md`,
in Japanese).

## How it works

```
keys (romaji)
   |  the engine: romaji -> hiragana, preedit, candidate window
   v
POST /api/segment-convert  {"input": reading, "context": text before the cursor, "n": 5}
   v
conversion server (http://localhost:8190)
   -> {"segments": [{"reading": "...", "candidates": ["...", ...]}, ...]}
```

- The text before the cursor is read from the application through fcitx5 `SurroundingText`
  (the last 500 characters before the cursor). It also contains what was typed while the input
  method was switched off. Applications that do not provide it send no context.
- At commit time the engine sends the phrasal units (bunsetsu) that the user changed, with `POST /api/record`
  (`reading` and `chosen` of each). A phrasal unit (bunsetsu) is changed when the user took a candidate other
  than the first, or moved a border so that its reading is new, even if the first candidate of that reading was
  taken as it is. The engine remembers what the server offered at the start of the conversion and compares the
  committed text with it.

Server address: `http://localhost:8190` (set in `src/llm-ime-engine.cpp`).

| Request | Use |
|---|---|
| `POST /api/segment-convert` | convert a reading; the answer is cut into phrasal units (bunsetsu) with candidates |
| `POST /api/segment-candidates` | candidates for one phrasal unit (bunsetsu) whose reading is known |
| `POST /api/convert` | convert the whole text into one string |
| `POST /api/record` | what the user chose |
| `GET /api/predict`, `POST /api/continue` | predictions and continuations (the server may answer empty) |

## Keys

| Key | Action |
|---|---|
| letters | type romaji (the hiragana is shown in the preedit) |
| Space | start conversion; in conversion mode, the next candidate |
| Ctrl+Enter | convert the whole text with the LLM conversion |
| Enter | commit (the hiragana as typed, or the converted text) |
| Escape | cancel; in conversion mode, back to typing |
| Backspace | delete the letter before the cursor |
| Left / Right | while typing: move the cursor in the reading by one kana; in conversion mode: previous / next phrasal unit (bunsetsu) |
| Home / End / Delete | while typing: move the cursor to the start / the end of the reading; delete the kana after the cursor |
| Shift+Left / Shift+Right | shorten / extend the phrasal unit (bunsetsu) |
| Up / Down, 1 to 9 | choose a candidate |
| Ctrl+Tab, Tab | ask for a continuation of the text; accept it (with an empty buffer) |

Whether Ctrl+Enter reaches the engine depends on the application and on the fcitx5 settings.

While typing, the cursor of the reading is shown in the preedit. In conversion mode the cursor is at the start of the
phrasal unit (bunsetsu) that is being chosen (the highlighted one). The engine does not write a log of the keys.

## Build and install

Requirements: fcitx5, CMake 3.16 or later, a C++17 compiler, `libcurl`, `nlohmann-json` 3.2 or later.

```bash
# Ubuntu / Debian
sudo apt install fcitx5 libfcitx5core-dev libfcitx5config-dev libfcitx5utils-dev fcitx5-modules-dev \
                 nlohmann-json3-dev libcurl4-openssl-dev cmake g++ pkg-config

mkdir -p build && cd build
cmake .. -DCMAKE_PREFIX_PATH=/usr
make
sudo make install        # installs llm-ime.so, the addon file and the input method file
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
A conversion server must be running on `localhost:8190`; see the README at the top of this repository.

## License

Apache License 2.0 (see `../LICENSE`).

Dependencies: fcitx5 core (`Fcitx5Core`), LGPL-2.1 or later; `nlohmann_json`, MIT; `libcurl`, curl license.
