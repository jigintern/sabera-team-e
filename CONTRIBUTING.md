# コントリビューションガイド

- team-e でこのリポジトリを触るときの手順とルール
- AI エージェント向けの技術的前提は [AGENTS.md](AGENTS.md)

## このリポジトリでやること

- SABERA スマートグラス向けに、**空にかざすと視界と星座が重なり、AI に頼むと今見えている星座を解説してくれるアプリ**を作る
- いまは **仕様策定フェーズ**。実装より「何を作るか」を書き残すほうが優先
- SDK 0.0.12 で **画像送信**（`enterImageDisplayPage` / `sendImage`）が使える
  - グレースケールを渡すだけでよい。**196x196 / 3bit（8 階調）**という制約は残る
  - 詳細は [AGENTS.md](AGENTS.md#グラスに何を出せるか)
- 上流 SDK は 0.0.12 まで取り込み済み

## 環境をつくる

### 1. 必要なもの

- **Android 実機**（BLE 必須。**エミュレータでは動かない**）
- Android Studio
- JDK 17
- Python 3（ドキュメント用スクリプト）
- Ruby 3.4 + Bundler（ドキュメントサイトをローカルで見る場合のみ）

### 2. GitHub PAT を設定する（最初の関門）

- SDK は private な GitHub Packages（`jig-SABERA/sabera-sdk-packages`）で配布されている
- **`read:packages` スコープの PAT が無いとビルドが認証エラーで落ちる**
- 発行手順 → [docs/github-pat.md](docs/github-pat.md)
- 置き場所 → `~/.gradle/gradle.properties`（リポジトリ内ではなくホーム配下）

```properties
GitHubPackagesUsername=<GitHubのユーザー名>
GitHubPackagesPassword=<read:packages を持つ PAT>
```

- プロパティ名は `samples/kmp/settings.gradle.kts` のリポジトリ名（`GitHubPackages`）から決まる。**変えると認証されない**
- **PAT は絶対にコミットしない**

### 3. 動かす

```bash
cd samples/kmp
./gradlew :app:installDebug
```

- 実機にインストールされる
- アプリ起動 → デバイス選択ダイアログでグラスを選ぶ、で接続まで完了
- 詰まったら [困ったとき](#困ったとき) へ

## どこを触るか

- **`samples/kmp/app/`** — team-e のアプリコード。ここを書き換えて育てる
- `samples/kmp/snippets/` — **ドキュメント用のコード例置き場。アプリではない**。壊すと CI が落ちる
- `samples/flutter/` — 使わない。参照用に残す
- `docs/` — SDK のドキュメントサイト。team-e の仕様書もここに置く（後述）

## 進め方

- 運用は軽量。**main への直接 push を許可**する（仕様策定フェーズで、壊れて困る実装がまだ無いため）
- 次のときは feature ブランチを切って PR にする
  - 他のメンバーの作業とぶつかりそうな変更
  - 設計の方向を決める変更（アーキテクチャ、依存の追加）
  - レビューしてほしいとき

```bash
git switch -c feat/star-catalog
# ...作業...
git push -u origin feat/star-catalog
```

- CI（`.github/workflows/docs.yml`）はすべての PR と main への push で回る。**落ちたまま放置しない**
- **コード例のコンパイルと ktlint は CI から外してある**（SDK 取得に PAT が要るため）。手元で回す

```bash
cd samples/kmp && ./gradlew :snippets:compileDebugKotlin :snippets:ktlintCheck
```

### コミットメッセージ

- 形式は `<type>: <日本語の要約>`

| type | 使いどころ |
|---|---|
| `feat:` | 機能の追加 |
| `fix:` | バグ修正 |
| `docs:` | ドキュメント・仕様書 |
| `refactor:` | 挙動を変えない整理 |
| `test:` | テスト |
| `chore:` | 雑務（設定、依存の更新など） |
| `build:` | ビルド周り |
| `ci:` | CI 設定 |
| `style:` | 整形のみ |

```
docs: 星座データの持ち方の候補を仕様書に追記する

Hipparcos と自前 JSON を比較。サイズと精度のトレードオフを表にした。
```

- 本文は任意。書くなら「なぜそうしたか」
- **`Co-Authored-By` に AI エージェント（Claude / Codex など）を入れない**

## 仕様書とドキュメント

議論して決まったことは、チャットで終わらせずリポジトリに残す。

| 書くもの | 置き場所 |
|---|---|
| team-e の仕様書・議事録・設計メモ | `docs/team-e/`（まだ無い。最初に書く人が作る） |
| 決まった技術的前提・制約 | [AGENTS.md](AGENTS.md) |
| SDK の使い方・API リファレンス | `docs/`（SDK 側の話なので team-e の仕様は混ぜない） |

`docs/` は **GitHub Pages に公開していない**（上流が公開しているため）。読むときは手元で Jekyll を立てる。

書き方の作法 → [docs/authoring.md](docs/authoring.md)。要点：

- 公開 API 名は**バッククォートで囲むだけ**で該当ページにリンクされる。`[...](...)` は書かない
- コード例は Markdown に直接書かない。出処は `samples/kmp/snippets/` の Kotlin
- `docs/_data/api_links.yml` と `docs/_site/` は生成物。手で編集しない

ローカルで見る：

```bash
cd docs && bundle install && bundle exec jekyll serve   # http://127.0.0.1:4000/
```

## 困ったとき

- **ビルドが 401 / 認証エラーで落ちる**
  - `~/.gradle/gradle.properties` に書いたか
  - PAT に `read:packages` スコープが付いているか
  - プロパティ名が `GitHubPackagesUsername` / `GitHubPackagesPassword` になっているか
- **グラスが見つからない / 接続できない**
  - エミュレータでは動かない。実機を使う
  - Bluetooth と位置情報の権限が許可されているか
- **CI の「docs のコード例が最新か確かめる」が落ちる**
  - `samples/kmp/snippets/` を直したあと `python3 scripts/sync-snippets.py` を忘れている
  - 実行して差分をコミットする
- **`:snippets` のコンパイルが手元で落ちる**
  - `samples/kmp/snippets/` が SDK の実 API と食い違っている
  - `:snippets` はドキュメント専用。アプリの都合で書き換えない
  - CI では検出できないので、触ったら必ず手元で回す
- **グラスの画面に何も出ない**
  - 送信先のページを先に開く必要がある（例: `enterEmptyScreenPage()` → `sendEmptyScreenContent()`）
  - 画像なら 196x196 を超えていないか。超えるとファーム側で弾かれて無表示になる

## ライセンス

- このリポジトリのサンプル・ラッパーコードは [Apache License 2.0](LICENSE)
- **SDK 本体（`jp.jig.sabera.app.sdk:*`）はこのライセンスの対象外**
  - GitHub Packages から配布されるバイナリで、利用には別途 SDK 利用規約が適用される
