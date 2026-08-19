# AGENTS.md

- このリポジトリで作業する AI エージェント（Claude Code / Codex など）向けの共通ガイド
- 人間向けの手順は [CONTRIBUTING.md](CONTRIBUTING.md)

## プロジェクト

- **SABERA** = スマートグラス
- このリポジトリ = SABERA App SDK を使って **team-e** がアプリを開発する場所
- 目標 = **空にかざしたグラスの視界と星座を重ね、AI に頼むと今見えている星座を解説してくれるアプリ**

## 現在のフェーズ：仕様策定中

- **まだ実装フェーズに入っていない**
- チームでドキュメントと仕様書を詰めている段階

エージェントとして作業するときの原則：

- 頼まれていないアプリ機能を先回りして実装しない（仕様未確定なので手戻りになる）
- 「こう実装できます」より「この API があるので、この方式なら実現できる／できない」を返す
- 未決定事項に行き当たったら勝手に決めず、[未決定事項](#未決定事項) に追記するか質問する

## リポジトリ構成

| パス | 中身 |
|---|---|
| `samples/kmp/` | **team-e の実装ベース**。Kotlin + Compose の Android アプリ |
| `samples/kmp/app/` | ここを書き換えて育てる |
| `samples/kmp/snippets/` | ドキュメント用コード例。**アプリではない**（壊すと CI が落ちる） |
| `samples/flutter/` | Flutter 版サンプル。team-e では使わない（参照用に残す） |
| `docs/` | Jekyll (just-the-docs) 製ドキュメントサイト。GitHub Pages に自動デプロイ |
| `docs/api/` | SDK API リファレンス。雛形は `scripts/gen-api-docs.py` が生成 |
| `scripts/` | ドキュメント生成・同期スクリプト（Python 3） |
| `Package.swift` | iOS 向け SPM 定義（KMMBridge 生成。**手で編集しない**） |

## 上流 SDK との同期状況

- 上流 = [jig-SABERA/sabera-sdk](https://github.com/jig-SABERA/sabera-sdk)（このリポジトリの元）
- **このリポジトリのコードとドキュメントは SDK 0.0.10 時点のコピー**
- **上流は 0.0.11 に上がっており、画像送信 API が追加されている**（後述）
- `docs/api/` にも `samples/kmp/` の依存にも 0.0.11 の内容は入っていない
- → **上流を取り込む作業が未実施。** SDK の話をするときは上流を確認する

## 開発コマンド

すべて `samples/kmp/` で実行：

```bash
cd samples/kmp
./gradlew :app:installDebug              # 実機にインストール
./gradlew :app:assembleDebug             # ビルドのみ
./gradlew :snippets:compileDebugKotlin   # ドキュメントのコード例をコンパイル
./gradlew :snippets:ktlintCheck          # コード例の lint
./gradlew :snippets:ktlintFormat         # コード例の自動整形
```

ドキュメント：

```bash
cd docs && bundle install && bundle exec jekyll serve   # http://127.0.0.1:4000/
python3 scripts/sync-snippets.py                        # Kotlin のコード例を docs/ へ写す
python3 scripts/sync-snippets.py --check                # 差分を検査（CI と同じ）
python3 scripts/gen-api-docs.py                         # API ページの雛形を生成（既存本文は保持）
```

### 前提

- JDK 17 / Gradle 8.10.2（wrapper 同梱）/ Kotlin 2.3.10 / AGP 8.7.0
- Android `minSdk 31` / `compileSdk 36` / `targetSdk 36`
- **BLE 実機が必須。エミュレータでは動作確認できない**
- SDK は private な GitHub Packages 配布。`read:packages` の PAT が無いとビルドが認証エラーで落ちる
- `ktlint` は `:snippets` のみ適用。`:app` は対象外
- `.editorconfig`（4スペース / 120桁 / intellij_idea）は全 Kotlin に効く

## SDK のアーキテクチャ

- 依存 = `jp.jig.sabera.app.sdk:sabera-app-core`（バイナリ配布）
- 接続のライフサイクルは一本道で、途中を飛ばせない

```
Application.onCreate()        GlassesSDK.setLogger / setProd / setDevicePersistence
        ↓                     ※ プロセスシングルトン。他のどの SDK API より先に呼ぶ
Activity.onCreate()           SdkActivityHost.showBleDeviceSelectionDialog を差し込む
        ↓                     BleCompanionDeviceService.connectToLastDevice() で自動再接続
getGlassManager(context)
        ↓
manager.connectedDevice       StateFlow<GlassClient?>。接続状態はここだけを見る
        ↓
manager.showAutomaticSelectionDialog(activity)   ← 選択と接続を両方やる。別途 connect() 不要
        ↓
client.createCommandManager()
        ↓
コマンド送信 / commandManager.gestureEvents 購読
        ↓
manager.disconnect(client)    ← GlassClient に disconnect() は無い。必ず Manager 経由
```

設計上の約束ごと：

- `showAutomaticSelectionDialog()` の第1引数は **Activity**。Application Context だとダイアログが出ない
- `GlassClient` は切断手段を持たない（`GlassClientInternal` に隔離）。UI 層が接続状態を抱えないための制約なので回避しない
- `gestureEvents` は `SharedFlow`。購読開始前のジェスチャーは受け取れない
- `setDevicePersistence` を省くとインメモリになり、プロセスをまたぐと接続先を忘れる
- コンテンツはページを開いてから送る。開いていないと表示されない

詳細は `docs/getting-started.md` と `docs/api/`。

## グラスに何を出せるか

**team-e の仕様を左右する最重要事項。** 0.0.11 で画像送信が入り、**星図をグラスに出す道が開いた**。

### 画像を出す（0.0.11 で追加）

```kotlin
fun enterImageDisplayPage()
fun sendImage(width: Int, height: Int, encodedBitmap: ByteArray)
```

- `enterImageDisplayPage()` で開いてから `sendImage()` を呼ぶ
- 画像表示ページ = **技適マークの表示に使っている画面**
- **最大 196x196。超えるとファーム側で弾かれ、何も表示されない**
- 形式 = **3bit グレースケール（8階調）を RLE 圧縮した `ByteArray`**
- **エンコードは呼び出し側の責任。** SDK はやってくれない
- カラー不可、フルスクリーンの AR オーバーレイでもない。**小さなモノクロ画像1枚**

→ 星座線と星の点を描くには足りるが、**196x196 / 8階調に収まる図案**を前提に設計する必要がある。

### テキストを出す

- `enterEmptyScreenPage()` + `sendEmptyScreenContent(content: String)` — **汎用テキストページ（0.0.11 追加）**
  - 200バイト超は分割して送られる
  - 用途が限定されないので、解説文の表示先の第一候補
- 用途別ページもある — Teleprompter / AI / AI Chat / Translate / Meeting / Notification
- Teleprompter は行送り・進捗・時刻の API が揃っている（`sendTeleprompterLine` など）

### 入力を取る

- ジェスチャー — `gestureEvents`（`SINGLE_TAP` / `DOUBLE_TAP` / `HOLD`）
- グラスのマイク — `openGlassMic()` / `closeGlassMic()` / `GlassClient.micChannel`
- 電源イベント、リモコンイベントのリスナー

### 取れないもの（0.0.11 時点）

- **カメラ映像** — グラスから画像は取れない
- **グラスの姿勢・方位** — `enterImuDebugPage()` は**ページを開くだけ**で値は取れない。`sendWakeupTiltThreshold(degrees)` も起動閾値の設定のみ
- → **方位・傾きはスマホ側センサーに依存するしかない**。この前提は 0.0.11 でも変わっていない

### 設計への含意

- 星座の特定 = **スマホのセンサー（方位・傾き・位置・時刻）から計算**
- グラスへの出力 = **196x196 の星図画像**＋**テキスト解説**の組み合わせ
- 画像送信が使えない事態（サイズ制約、エンコード負荷、ファーム差異）に備え、**テキストだけでも成立する経路を残す**
- → **星座特定・解説生成とグラス出力を分離する。** 出力層だけ差し替えられる形にしておく

### エージェントへの指示

- 画像送信は**まだ team-e の誰も実機で試していない**。動作を断定しない
- `sendImage` のエンコード（3bit グレースケール + RLE）は自前実装が要る。仕様の詳細は上流の
  `docs/api/command-manager/send-image.md` を参照する
- 0.0.11 の API はこのリポジトリの `docs/api/` にまだ無い。**上流を見る**

## ドキュメントサイトの仕組みと CI の落とし穴

CI（`.github/workflows/docs.yml`）は **すべての PR と main への push** で実行：

1. `:snippets:compileDebugKotlin` — コード例が SDK の実 API と合っているか
2. `:snippets:ktlintCheck`
3. `python3 scripts/sync-snippets.py --check` — Kotlin のコード例と `docs/` の差分

よくある落とし方：

- `samples/kmp/snippets/**` を直して `sync-snippets.py` を実行し忘れる → 3 で落ちる
- `docs/api/**` のコードブロックを手で書き換える → 出処は Kotlin 側なので 3 で落ちる
- `:snippets` をアプリコードの置き場と勘違いして壊す → 1 で落ちる

編集してはいけない生成物：

- `docs/_data/api_links.yml` — `scripts/gen-api-docs.py` が `SPEC` から生成
- `Package.swift` の KMMBRIDGE ブロック
- `docs/_site/` — Jekyll のビルド成果物（`.gitignore` 済み）

その他：

- `docs/**` の Markdown では公開 API 名をバッククォートで囲むだけで自動リンクされる（`docs/_plugins/api_autolink.rb`）。`[...](...)` は書かない
- `main` への push で GitHub Pages に自動デプロイされる

## 未決定事項

決まったらこのファイルを更新する。エージェントは勝手に埋めない。

- 上流 SDK 0.0.11 をいつ・どう取り込むか
- 196x196 / 3bit グレースケールで星図をどう描くか（星の等級表現、星座線、文字の可読性）
- RLE エンコーダを自前で書くか、上流にサンプルが出るのを待つか
- 星図の計算方法（自前実装 / ライブラリ / サーバー API）と星座データの出処
- 「AI にお願いする」の入力経路 — グラスのマイク（`openGlassMic`）かスマホ側か
- 解説文の生成に使う LLM と呼び出し場所（端末直かバックエンド経由か）
- 解説テキストの表示先 — `enterEmptyScreenPage` / AI Chat / Teleprompter
- 画像とテキストの出し分け（同時には出せない。ページ遷移が要る）
- スマホ側 UI の役割（星図プレビューを出すか、コントローラに徹するか）
- 位置・方位のパーミッション設計（現状の Manifest は BLE 系と `ACCESS_FINE_LOCATION` のみ）
- API キーなど秘密情報の持ち方（`.gitignore` は `.env` を除外済み）

## 規約

### コミットメッセージ

- 形式 = `<type>: <日本語の要約>`
- 使う type — `feat:` / `fix:` / `docs:` / `refactor:` / `test:` / `chore:` / `build:` / `ci:` / `style:`
- 例 — `docs: 星座データの候補を仕様書に追記する`
- 本文は任意。書くなら「なぜそうしたか」

### エージェントの取り扱い

- **`Co-Authored-By` に Claude / Codex などのエージェントを入れない**
- コミットの作者はあくまで人間

### そのほか

- ドキュメント・コミットメッセージ・PR は**日本語**
- コードのコメントは「何をしているか」ではなく「なぜそうしたか」。既存ファイルの密度に合わせる
- 秘密情報（PAT、API キー）をコミットしない。SDK 取得の認証情報は `~/.gradle/gradle.properties` か環境変数
- ライセンス — サンプル・ラッパーコードは Apache License 2.0。**SDK 本体（`jp.jig.sabera.app.sdk:*`）は対象外**で別途 SDK 利用規約
