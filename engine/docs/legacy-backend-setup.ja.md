# fcitx5-llm-ime

fcitx5用のLLMベースIMEプラグイン。ローマ字→ひらがな変換を行い、バックエンド `quarkus-llm-ime`（port 8090）経由でMozcによる辞書変換とvLLMによるLLM変換を利用する。

## アーキテクチャ

```
ユーザー入力（ローマ字）
  ↓
fcitx5-llm-ime（C++プラグイン）
  ├─ ローマ字→ひらがな変換
  ├─ Space → /api/segment-convert（Mozc文節変換、高速）
  └─ Ctrl+Enter → /api/convert（LLM全文変換、文脈考慮）
  ↓
quarkus-llm-ime（Javaバックエンド、port 8090）
  ├─ Mozc（Unix socket経由、辞書ベース変換）
  └─ vLLM（HTTP経由、LLMベース変換）
```

## セットアップ概要

システム全体は以下の3つのコンポーネントから構成される：

1. **vLLMサーバー** — LLMモデルをホストするGPUサーバー
2. **quarkus-llm-ime** — MozcとvLLMを統合するJavaバックエンド（localhost:8090）
3. **fcitx5-llm-ime** — fcitx5用C++プラグイン（本リポジトリ）

以下、各コンポーネントのセットアップ手順を説明する。

---

## 1. vLLMサーバーのセットアップ

LLM変換機能を使うには、vLLM互換のAPIサーバーが必要。GPUを搭載したマシンで以下を実行する。

### vLLMのインストール

```bash
pip install vllm
```

### モデルのダウンロードと起動

```bash
vllm serve llm-jp/llm-jp-3-3.7b-instruct \
  --host 0.0.0.0 \
  --port 8000 \
  --download-dir /models
```

- `--host 0.0.0.0` でネットワーク上の他のマシンからアクセス可能にする
- モデルは任意の日本語対応instruct modelに変更可能
- デフォルト設定では `localhost:8000` を想定（変更は `quarkus-llm-ime` の設定ファイルで行う）

### 動作確認

```bash
curl http://<vllm-host>:8000/v1/models
```

モデル名が返れば正常。

> **注意**: vLLMサーバーはLLM変換（Ctrl+Enter）にのみ使用される。Mozc変換（Space）はvLLMなしでも動作する。

---

## 2. quarkus-llm-ime（Javaバックエンド）のセットアップ

### 前提条件

- **Java 21以上**（`java -version` で確認）
- **Maven 3.9以上**（`mvn -version` で確認）
- **Mozc**（`mozc_server` がインストール済みであること）

### Mozcのインストール

Ubuntu/Debianの場合：

```bash
sudo apt install mozc-server mozc-utils-gui
```

Mozcはバックエンドと同じマシンで動作する必要がある（Unix socket経由で通信するため）。

### ソースの取得

```bash
cd ~/works
git clone https://github.com/oogasawa/quarkus-llm-ime.git
cd quarkus-llm-ime
```

### 設定の変更

`src/main/resources/application.properties` を環境に合わせて編集する：

```properties
# vLLMサーバーのURL（GPUサーバーのアドレスに変更）
quarkus.rest-client.vllm.url=http://localhost:8000

# 使用するモデル名（vllm serveで指定したモデルと一致させる）
llm-ime.model=/models/llm-jp-3-3.7b-instruct

# バックエンドのポート番号
quarkus.http.port=8090
```

### ビルド

```bash
rm -rf target
mvn install
```

### 起動

```bash
java -jar target/quarkus-app/quarkus-run.jar
```

バックグラウンドで常駐させる場合：

```bash
nohup java -jar target/quarkus-app/quarkus-run.jar > /tmp/quarkus-llm-ime.log 2>&1 &
```

### 動作確認

```bash
# Mozc変換のテスト
curl -s -X POST http://localhost:8090/api/segment-convert \
  -H 'Content-Type: application/json' \
  -d '{"input": "にほんご"}' | python3 -m json.tool

# LLM変換のテスト（vLLMサーバーが必要）
curl -s -X POST http://localhost:8090/api/convert \
  -H 'Content-Type: application/json' \
  -d '{"input": "にほんご"}' | python3 -m json.tool
```

Mozc変換が動作すれば、バックエンドのセットアップは完了。

---

## 3. fcitx5-llm-ime（C++プラグイン）のセットアップ

### 前提条件

- **fcitx5** がインストール済みで動作していること
- **CMake 3.16以上**
- **C++17対応コンパイラ**（g++ 8以上）

### 依存パッケージのインストール

Ubuntu/Debianの場合：

```bash
sudo apt install \
  fcitx5 \
  fcitx5-config-qt \
  libfcitx5core-dev \
  libfcitx5config-dev \
  libfcitx5utils-dev \
  fcitx5-modules-dev \
  nlohmann-json3-dev \
  libcurl4-openssl-dev \
  cmake \
  g++ \
  pkg-config
```

> **パッケージ名の注意**: ディストリビューションによってパッケージ名が異なる場合がある。
> `libfcitx5core-dev` が見つからない場合は `fcitx5-libs-dev` を試す。
> `apt search fcitx5 | head -40` でパッケージ名を確認する。

### ソースの取得

```bash
cd ~/works
git clone <this-repository-url>
cd fcitx5-llm-ime
```

### ビルド

```bash
mkdir -p build && cd build
cmake .. -DCMAKE_PREFIX_PATH=/usr
make
```

### インストール

```bash
sudo make install
```

以下のファイルがインストールされる：

| ファイル | インストール先 |
|---------|---------------|
| `llm-ime.so` | `/usr/lib/fcitx5/llm-ime.so` |
| `llm-ime-addon.conf` | `/usr/share/fcitx5/addon/llm-ime.conf` |
| `llm-ime.conf` | `/usr/share/fcitx5/inputmethod/llm-ime.conf` |

### fcitx5の再起動

```bash
fcitx5 -r -d
```

### IMの切り替え

インストール直後はデフォルトIMが `llm-ime` になっていない。以下のコマンドで切り替える：

```bash
fcitx5-remote -s llm-ime
```

**これを忘れるとllm-imeのコードは一切実行されない。** 入力しても反応がない場合、まずこのコマンドを実行すること。

常にllm-imeをデフォルトにするには、fcitx5を一度停止してからプロファイルを編集する：

```bash
# fcitx5を停止
pkill fcitx5

# プロファイルを編集
# DefaultIM=llm-ime に変更する
nano ~/.config/fcitx5/profile

# fcitx5を再起動
fcitx5 -d
```

`~/.config/fcitx5/profile` の `[Groups/0]` セクションで以下のようにする：

```ini
[Groups/0]
Name="グループ 1"
Default Layout=jp
DefaultIM=llm-ime

[Groups/0/Items/0]
Name=keyboard-jp
Layout=

[Groups/0/Items/1]
Name=llm-ime
Layout=
```

> **注意**: fcitx5は起動中にプロファイルを自動的に上書きする。fcitx5の実行中にファイルを編集しても反映されない。必ずfcitx5を停止してから編集すること。

### 動作確認

1. テキストエディタ（`gedit`、`kate`、ブラウザの入力欄など）を開く
2. Ctrl+Spaceで日本語入力モードに切り替え（fcitx5アイコンが変化する）
3. ローマ字（例: `nihongo`）を入力 → ひらがな（`にほんご`）がプレビュー表示される
4. Spaceを押す → Mozcの変換候補が表示される
5. Enterで確定

LLM変換をテストする場合：

1. ローマ字を入力してひらがなプレビューが表示された状態で Ctrl+Enter を押す
2. LLMによる変換結果が表示される（vLLMサーバーが稼働している必要がある）

---

## 重要な注意事項

### IMの選択

fcitx5の設定（`~/.config/fcitx5/profile`）で `DefaultIM=llm-ime` に設定する必要がある。
`DefaultIM=mozc` のままだと、llm-imeではなくmozcが使われる。

**注意**: fcitx5は起動中にプロファイルを自動的に上書きするため、ファイルを直接編集しても反映されないことがある。以下のいずれかの方法で切り替える：

1. **コマンドで切り替え**: `fcitx5-remote -s llm-ime`
2. **トレイアイコンから切り替え**: タスクバーのfcitx5アイコンを右クリックしてllm-imeを選択
3. **fcitx5停止中に編集**: `pkill fcitx5` でプロセスを停止してからプロファイルを編集し、再起動

### `.so`差し替え時の再起動

`.so`ファイルを差し替えた後は、`fcitx5 -r -d` で再起動する。

- `pkill -9 fcitx5` するとセッションマネージャが自動的にfcitx5を再起動するが、この場合DBusの状態が不正になり**候補リストウィンドウが表示されなくなる**ことがある
- `pkill -9` 後に手動で `fcitx5 -d` を実行すると「Is there another fcitx already running?」でDBus競合が起きる
- **推奨手順**: `sudo make install` → `fcitx5 -r -d`
- それでもダメな場合: `pkill -9 fcitx5` → 自動再起動を待つ → `fcitx5 -r -d`

### キーバインドの制約

fcitx5はプラグインにキーイベントを渡す前に、以下のキーをインターセプトする：

- **Shift+Space**: 全角/半角切替（fcitx5デフォルト）
- **Tab / Shift+Tab**: 次/前の候補（fcitx5グローバル設定）
- **F6〜F10**: ひらがな/カタカナ/半角/英数変換（fcitx5デフォルト）
- **Ctrl+Space**: IM切替（fcitx5トリガーキー）

これらのキーはプラグイン内で`key.check()`しても到達しない。LLM変換のキーバインドにはこれらと衝突しないキーを使う必要がある。

---

## キー操作

### 入力モード（ローマ字入力中）

| キー | 動作 |
|------|------|
| 英字キー | ローマ字入力（リアルタイムでひらがなプレビュー） |
| Space | Mozc文節変換を開始（高速） |
| Ctrl+Enter | LLM全文変換を開始（文脈を考慮） |
| Enter | ひらがなのまま確定 |
| Escape | 入力キャンセル |
| Backspace | 1文字削除 |

### 変換モード（候補選択中）

| キー | 動作 |
|------|------|
| Space | 次の候補（初回押下時にLLM候補も取得） |
| Ctrl+Enter | LLM全文変換に切り替え |
| Enter | 変換結果を確定 |
| Escape | 入力モードに戻る |
| ←/→ | 前/次の文節に移動 |
| Shift+←/→ | 文節の境界を伸縮 |
| ↑/↓ | 前/次の候補 |
| 1-9 | 候補を直接選択 |

### 変換方式の違い

- **Space（Mozc変換）**: 文を文節に分割し、各文節ごとにMozcの辞書候補を表示。高速で安定。文節の境界はShift+矢印で調整可能。
- **Ctrl+Enter（LLM変換）**: 文全体をLLM（vLLM経由）に送り、文脈を考慮した一括変換結果を返す。同音異義語の使い分けに有利だが、モデルの性能に依存。

**注意**: Ctrl+Enterが実際にプラグインに届くかはアプリケーションやfcitx5の設定に依存する。届かない場合は別のキーバインドへの変更を検討すること。

## ローマ字変換テーブル

最長一致で変換される（4文字 → 3文字 → 2文字 → 1文字の順にマッチ）。

### 基本母音

| ローマ字 | ひらがな |
|----------|----------|
| `a` | あ |
| `i` | い |
| `u` | う |
| `e` | え |
| `o` | お |

### か行〜わ行

| | a | i | u | e | o |
|---|---|---|---|---|---|
| k | か | き | く | け | こ |
| s | さ | し(si) | す | せ | そ |
| t | た | ち(ti) | つ(tu) | て | と |
| n | な | に | ぬ | ね | の |
| h | は | ひ | ふ(hu) | へ | ほ |
| m | ま | み | む | め | も |
| y | や | い(yi) | ゆ | いぇ(ye) | よ |
| r | ら | り | る | れ | ろ |
| w | わ | ゐ(wi) | - | ゑ(we) | を(wo) |

### 濁音・半濁音

| | a | i | u | e | o |
|---|---|---|---|---|---|
| g | が | ぎ | ぐ | げ | ご |
| z | ざ | じ(zi) | ず | ぜ | ぞ |
| d | だ | ぢ(di) | づ(du) | で | ど |
| b | ば | び | ぶ | べ | ぼ |
| p | ぱ | ぴ | ぷ | ぺ | ぽ |

### 拗音（きゃ行等）

| | a | i | u | e | o |
|---|---|---|---|---|---|
| ky | きゃ | きぃ | きゅ | きぇ | きょ |
| sh / sy | しゃ | し(shi) / しぃ(syi) | しゅ | しぇ | しょ |
| ch / cy / ty | ちゃ | ち(chi) / ちぃ(cyi) | ちゅ | ちぇ | ちょ |
| ny | にゃ | にぃ | にゅ | にぇ | にょ |
| hy | ひゃ | ひぃ | ひゅ | ひぇ | ひょ |
| my | みゃ | みぃ | みゅ | みぇ | みょ |
| ry | りゃ | りぃ | りゅ | りぇ | りょ |
| gy | ぎゃ | ぎぃ | ぎゅ | ぎぇ | ぎょ |
| j / jy | じゃ | じ(ji) / じぃ(jyi) | じゅ | じぇ | じょ |
| dy | ぢゃ | ぢぃ | ぢゅ | ぢぇ | ぢょ |
| zy | じゃ | じぃ | じゅ | じぇ | じょ |
| by | びゃ | びぃ | びゅ | びぇ | びょ |
| py | ぴゃ | ぴぃ | ぴゅ | ぴぇ | ぴょ |

### 外来語

| | a | i | u | e | o |
|---|---|---|---|---|---|
| f | ふぁ | ふぃ | ふ(fu) | ふぇ | ふぉ |
| v | ゔぁ | ゔぃ | ゔ(vu) | ゔぇ | ゔぉ |
| ts | つぁ | つぃ | つ(tsu) | つぇ | つぉ |
| wh | うぁ | うぃ | う(whu) | うぇ | うぉ |
| q | くぁ | くぃ | - | くぇ | くぉ |
| gw | ぐぁ | ぐぃ | ぐぅ | ぐぇ | ぐぉ |

| | a | i | u | e | o |
|---|---|---|---|---|---|
| th | てぁ | てぃ | てゅ | てぇ | てょ |
| dh | でぁ | でぃ | でゅ | でぇ | でょ |
| tw | とぁ | とぃ | とぅ | とぇ | とぉ |
| dw | どぁ | どぃ | どぅ | どぇ | どぉ |

拗音付き:

| | a | i | u | e | o |
|---|---|---|---|---|---|
| qy | くゃ | くぃ | くゅ | くぇ | くょ |

### 特殊

| ローマ字 | ひらがな | 備考 |
|----------|----------|------|
| `tsu` | つ | |
| `dzu` | づ | |
| `nn` | ん | |
| `n'` | ん | 母音の前で「ん」を確定 |
| `n` + 子音 | ん | 母音・y以外の前で自動変換 |
| 子音の連打 | っ | 例: `tt` → っ + t... |

### 小書き（`x` / `l` どちらでも可）

| x系 | l系 | ひらがな |
|-----|-----|----------|
| `xa` | `la` | ぁ |
| `xi` | `li` | ぃ |
| `xu` | `lu` | ぅ |
| `xe` | `le` | ぇ |
| `xo` | `lo` | ぉ |
| `xya` | `lya` | ゃ |
| `xyu` | `lyu` | ゅ |
| `xyo` | `lyo` | ょ |
| `xtu` / `xtsu` | `ltu` / `ltsu` | っ |
| `xwa` | `lwa` | ゎ |
| `xka` | - | ヵ |
| `xke` | - | ヶ |

### 記号

| ローマ字 | 変換後 |
|----------|--------|
| `-` | ー |
| `,` | 、 |
| `.` | 。 |
| `!` | ！ |
| `?` | ？ |
| `/` | ・ |
| `~` | 〜 |
| `[` | 「 |
| `]` | 」 |
| `{` | ｛ |
| `}` | ｝ |
| `(` | （ |
| `)` | ） |

## バックエンドAPI

`quarkus-llm-ime`（localhost:8090）が提供するAPIエンドポイント：

| エンドポイント | 用途 |
|----------------|------|
| `POST /api/segment-convert` | Mozc文節分割・変換（Space押下時） |
| `POST /api/segment-candidates` | 文節ごとの候補取得（Mozc+LLM統合） |
| `POST /api/convert` | LLM全文変換（Ctrl+Enter押下時） |
| `POST /api/record` | 変換履歴の記録（確定時に自動送信） |

## トラブルシューティング

### 候補リストが表示されない

`fcitx5 -r -d` で再起動する。`pkill -9` の後に手動で `fcitx5 -d` を実行するとDBusの競合が起きる。`pkill -9` 後は自動再起動を待ってから `fcitx5 -r -d` を実行する。

### 入力がllm-imeではなくmozcで処理される

`fcitx5-remote -s llm-ime` でIMを切り替える。`DefaultIM=mozc` のままだとllm-imeのコードが一切実行されない。現在のIMを確認するには：

```bash
fcitx5-remote -n
```

`llm-ime` と表示されれば正常。

### 変換が遅い

vLLMサーバーの応答を確認。Space（Mozc変換）は即座に返る。Ctrl+Enter（LLM変換）はモデル推論のため数秒かかることがある。

### `.so`差し替え後に反映されない

`sudo make install` → `fcitx5 -r -d`。`fcitx5 -r -d`は既存プロセスを正しく置き換えて新しい`.so`をロードする。

### Ctrl+Enterが効かない

アプリケーションがCtrl+Enterをインターセプトしている可能性。ソースコード内の `FcitxKey_Return, KeyState::Ctrl` を別のキーに変更してリビルドする。

### バックエンドに接続できない

`quarkus-llm-ime` がlocalhost:8090で稼働していることを確認：

```bash
curl http://localhost:8090/api/segment-convert \
  -X POST -H 'Content-Type: application/json' \
  -d '{"input": "てすと"}'
```

エラーになる場合はバックエンドを起動する：

```bash
cd ~/works/quarkus-llm-ime
java -jar target/quarkus-app/quarkus-run.jar
```

### fcitx5がインストールされているか確認

```bash
fcitx5 --version
```

fcitx5自体が入っていない場合：

```bash
sudo apt install fcitx5 fcitx5-config-qt
```

## 開発者向け情報

### リビルド＆テストの手順

```bash
# C++プラグインのリビルド
cd ~/works/fcitx5-llm-ime/build
make && sudo make install && fcitx5 -r -d

# Javaバックエンドのリビルド
cd ~/works/quarkus-llm-ime
rm -rf target
mvn install
# 実行中のバックエンドを停止してから再起動
java -jar target/quarkus-app/quarkus-run.jar
```

### デバッグログの有効化

`llm-ime-engine.cpp` にファイルベースのデバッグログを追加する場合：

```cpp
#include <fstream>
// keyEvent内に追加：
{
    std::ofstream log("/tmp/llm-ime-keys.log", std::ios::app);
    log << "key: sym=" << key.sym()
        << " state=" << static_cast<uint32_t>(key.states()) << std::endl;
}
```

ログ確認：

```bash
tail -f /tmp/llm-ime-keys.log
```

## ライセンス

Apache License 2.0。全文は `LICENSE` にある。

依存するものの主なライセンスは次のとおりである。

- fcitx5 の本体（`Fcitx5Core`）: LGPL-2.1 以降
- `nlohmann_json`: MIT 系（Expat）
- `libcurl`: curl ライセンス
