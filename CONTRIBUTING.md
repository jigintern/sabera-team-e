# コントリビューションガイド

team-e でこのリポジトリを触るときの手順とルール。
AI エージェント向けの技術的前提は [AGENTS.md](AGENTS.md)、仕様は
[docs/team-e/index.md](docs/team-e/index.md)。

## 環境をつくる

### 1. 必要なもの

| | 補足 |
|---|---|
| **Android 実機** | **BLE 必須。エミュレータでは動かない** |
| Android Studio / JDK 17 | |
| Python 3 | 同梱データと天球シミュレータの生成 |

### 2. GitHub PAT を設定する（最初の関門）

SDK は private な GitHub Packages（`jig-SABERA/sabera-sdk-packages`）で配布されている。
**`read:packages` スコープの PAT が無いとビルドが認証エラーで落ちる。**
発行手順 → [docs/github-pat.md](docs/github-pat.md)

`~/.gradle/gradle.properties`（**リポジトリ内ではなくホーム配下**）：

```properties
GitHubPackagesUsername=<GitHubのユーザー名>
GitHubPackagesPassword=<read:packages を持つ PAT>
```

- プロパティ名は `samples/kmp/settings.gradle.kts` のリポジトリ名（`GitHubPackages`）から決まる。
  **変えると認証されない**
- **PAT は絶対にコミットしない**

### 3. OpenAI の API キーを置く（AI 解説を使うときだけ）

```bash
cp .env.example .env   # OPENAI_API_KEY= に手で書き込む
```

- `.env` は `.gitignore` 済み。テンプレートの `.env.example` だけを追跡している
- 探す順は `.env` → `local.properties` → `~/.gradle/gradle.properties`（`openAiApiKey`）→ 環境変数
- **リポジトリの外に置きたいなら `~/.gradle/gradle.properties`**（SDK 取得の PAT と同じ場所）
- **キーが無くてもビルドは通る。** 星図までは動き、タップすると「AI の設定がありません」と喋る
- **キーは APK に埋まる。** 逆コンパイルすれば読めるので、**配布せず手元の実機で動かす**前提
- **モデルを変えたら `OPENAI_REASONING_EFFORT` も見直す。** 推論するモデルなら `none`、
  推論を持たないモデル（`gpt-4o` など）なら**空**にする（送ると 400 で弾かれる）。
  切り忘れると遅くなるうえ、**本文が空で返って「解説がうまく作れませんでした」になる**
- **`.env` を書き換えたらビルドし直す。** 値は `BuildConfig` に焼かれるので、再起動では変わらない

### 4. 動かす

```bash
cd samples/kmp
./gradlew :app:installDebug              # 実機にインストール
./gradlew :app:testDebugUnitTest         # JVM テスト（座標変換・SGP4・AI 周り）
./gradlew :app:assembleDebug              # デバッグAPKを作る
```

アプリ起動 → デバイス選択ダイアログでグラスを選ぶ、で接続まで完了。
詰まったら [困ったとき](#困ったとき)へ。

## どこを触るか

| パス | |
|---|---|
| **`samples/kmp/app/`** | **アプリ本体。ここを書き換えて育てる** |
| `docs/team-e/` | team-e の仕様書 |
| `docs/github-pat.md` | SDK取得に必要なPATの設定 |
| `data/` / `tools/` | 同梱データと生成スクリプト。`data/` は**すべて生成物なので手で編集しない** |

## 進め方

- 運用は軽量。**main への直接 push を許可**する
- 次のときは feature ブランチを切って PR にする
  - 他のメンバーの作業とぶつかりそうな変更
  - 設計の方向を決める変更（アーキテクチャ、依存の追加）
  - レビューしてほしいとき

```bash
git switch -c feat/star-catalog
# ...作業...
git push -u origin feat/star-catalog
```

- CI（`.github/workflows/checks.yml`）はすべてのPRとmainへのpushで回る。**落ちたまま放置しない**
- private SDKのPATをCIへ置かないため、アプリのビルドとJVMテストは変更者が手元で実行する

### コミットメッセージ

形式は `<type>: <日本語の要約>`。本文は任意で、書くなら「なぜそうしたか」。

| type | 使いどころ |
|---|---|
| `feat:` | 機能の追加 |
| `fix:` | バグ修正 |
| `docs:` | ドキュメント・仕様書 |
| `refactor:` | 挙動を変えない整理 |
| `test:` | テスト |
| `chore:` | 雑務（設定、依存の更新など） |
| `build:` / `ci:` / `style:` | ビルド周り / CI 設定 / 整形のみ |

```
docs: 星座データの持ち方の候補を仕様書に追記する

Hipparcos と自前 JSON を比較。サイズと精度のトレードオフを表にした。
```

**`Co-Authored-By` に AI エージェント（Claude / Codex など）を入れない。** 作者は人間。

## 仕様書とドキュメント

議論して決まったことは、チャットで終わらせずリポジトリに残す。

| 書くもの | 置き場所 |
|---|---|
| team-e の仕様・設計判断 | [`docs/team-e/index.md`](docs/team-e/index.md) |
| 決まった技術的前提・SDK の制約 | [AGENTS.md](AGENTS.md) |
| SDK の使い方・API リファレンス | [上流の公開ドキュメント](https://jig-sabera.github.io/sabera-sdk/) |

`docs/team-e/`は通常のMarkdownとして管理する。API名はバッククォートで囲み、
上流の特定ページを根拠にするときだけ通常のMarkdownリンクを張る。

## 困ったとき

| 症状 | 見るところ |
|---|---|
| **ビルドが 401 / 認証エラー** | PAT が `~/.gradle/gradle.properties` にあるか。`read:packages` スコープが付いているか。プロパティ名が `GitHubPackagesUsername` / `GitHubPackagesPassword` か |
| **グラスが見つからない** | エミュレータでは動かない。Bluetooth と位置情報の権限が許可されているか |
| **グラスの画面に何も出ない** | キャンバス（`sendCanvasImage` / `sendCanvasElements`）は送るだけで出るが、**上限を超えると `IllegalArgumentException` で落ちる**。用途別ページは先に開く必要がある。→ [グラス出力の制約](docs/team-e/glass-output.md) |
| **星図が点いては消える** | 転送中は前の絵が消える。**首が止まってから送る**（0.4 秒静止 ＋ 6° 以上のずれ） |

## ライセンス

- このリポジトリのサンプル・ラッパーコードは [Apache License 2.0](LICENSE)
- **SDK 本体（`jp.jig.sabera.app.sdk:*`）は対象外。** GitHub Packages 配布のバイナリで、
  利用には別途 SDK 利用規約が適用される
- 同梱の星表データ（d3-celestial / XHIP）は BSD-3-Clause。帰属は [NOTICE](NOTICE)
