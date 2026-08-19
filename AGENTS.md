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
| `docs/` | Jekyll (just-the-docs) 製ドキュメントサイト。公開はせず手元で読む |
| `docs/api/` | SDK API リファレンス。雛形は `scripts/gen-api-docs.py` が生成 |
| `scripts/` | ドキュメント生成・同期スクリプト（Python 3） |
| `Package.swift` | iOS 向け SPM 定義（KMMBridge 生成。**手で編集しない**） |

## 上流 SDK との同期状況

- 上流 = [jig-SABERA/sabera-sdk](https://github.com/jig-SABERA/sabera-sdk)（このリポジトリの元）
- 上流の公開ドキュメント = <https://jig-sabera.github.io/sabera-sdk/>（`docs/` をビルドしたもの。0.1.0 に追従済み）
- **`ae374d2`（SDK 0.1.0）時点まで取り込み済み**
- team-e 独自ファイル（README / AGENTS.md / CLAUDE.md / CONTRIBUTING.md / .gitignore）は同期対象外
- `Package.swift` は上流でも 0.0.10 のまま。iOS は追従していない

上流を取り込み直すとき：

```bash
git remote add upstream https://github.com/jig-SABERA/sabera-sdk   # 初回のみ
git remote set-url --push upstream no_push                          # 誤 push 防止
git fetch upstream
git checkout upstream/main -- docs samples scripts .github Package.swift NOTICE LICENSE
git checkout HEAD -- .gitignore   # team-e 側の docs/_site 除外を戻す
```

- **`README.md` / `AGENTS.md` / `CLAUDE.md` / `CONTRIBUTING.md` は checkout の対象に入れない**
- `.github/workflows/docs.yml` は team-e 側で変更している（後述）。上書きしたら CI 方針を入れ直す

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
0.1.0 で **6DoF（グラスの姿勢）** が解放された。

### 画像を出す（0.0.11 で追加、0.0.12 で簡素化）

```kotlin
fun enterImageDisplayPage()
fun sendImage(width: Int, height: Int, grayscale: ByteArray)
```

- `enterImageDisplayPage()` で開いてから `sendImage()` を呼ぶ
- 画像表示ページ = **技適マークの表示に使っている画面**
- **最大 196x196。超えるとファーム側で弾かれ、何も表示されない**
- 渡すのは **1 画素 1 バイトのグレースケール**（0-255、左上から行優先）
- **3bit への量子化と RLE 圧縮は SDK が行う**（0.0.12 から。0.0.11 では呼び出し側の責任だった）
- 上流サンプルの `GlassImage.kt` に `toGlassGrayscale()` / `testPatternImage()` がある
- **表示面は緑の単色。** 渡すのはグレースケールだが、**グラスに映るのは緑の 8 階調**
  - API の契約はグレースケールのままなので、**緑の値を作って渡すのではない**
  - 等級や濃淡の設計は「緑 8 段でどう見えるか」で考える
- フルスクリーンの AR オーバーレイではない。**小さな単色画像 1 枚**

→ 星座線と星の点を描くには足りるが、**196x196 / 8階調に収まる図案**を前提に設計する必要がある。

### ナビページ経由で画像を出す（0.0.13 で追加）

```kotlin
fun enterNavigationPage()
fun sendNaviStatus(status: CommandManager.NaviStatus)
fun sendNavi(maneuverIcon, instructionText, distanceText, estimatedArrivalText, timeAndDistanceText,
             bitmapWidth: Int? = null, bitmapHeight: Int? = null, grayscale: ByteArray? = null)
fun sendNaviLargeImage(width: Int, height: Int, grayscale: ByteArray)
```

- 渡し方は `sendImage` と同じ（1画素1バイトのグレースケール。量子化と圧縮は SDK 側）
- `sendNavi` の地図は **255 まで**、`sendNaviLargeImage` は上流サンプルが **240x240** を送っている
- **画像表示ページより大きい画像を出せる**が、ナビ画面の枠・進行方向アイコン・案内テキストが一緒に出る。
  星図だけを見せる用途には向かない
- `sendNaviStatus(START)` にしないと `sendNavi` の内容は表示されない
- **誰も実機で試していない。実際の上限も見え方も未確認**

### テキストを出す

- `enterEmptyScreenPage()` + `sendEmptyScreenContent(content: String)` — **汎用テキストページ（0.0.11 追加）**
  - 200バイト超は分割して送られる
  - 用途が限定されないので、解説文の表示先の第一候補
- 用途別ページもある — Teleprompter / AI Chat / Translate / Navigation
- **0.0.14 で `enterAIPage` / `enterMeetingPage` / `enterNotificationPage` が撤去された**（ファームが対応していないため）。
  `sendAIContent` / `sendMeeting` は残っているが、ページを開く手段が無いので実質使えない
- Teleprompter は行送り・進捗・時刻の API が揃っている（`sendTeleprompterLine` など）

### 入力を取る

- **6DoF センサー（0.1.0 で追加）**

```kotlin
fun startImuData()
fun stopImuData()
val imuData: SharedFlow<CommandManager.ImuData>
val imuDataStarted: StateFlow<Boolean>
```

  - **`startImuData()` を呼ぶまで `imuData` には何も流れない**
  - 1 サンプルの中身 — `accelX/Y/ZMilliG`（加速度 [mg]）、`gyroX/Y/ZDps`（角速度 [dps]）、
    `pitchDegrees`、`yawDegrees`、`timestampMs`
  - **`timestampMs` は AR 起動からの経過時間。並べ替えと間隔の計算は受信時刻ではなくこれを使う**
  - **送信キューが詰まるとグラス側がサンプルを捨てる。** 指定周期どおりには届かない
  - **ロールは融合値として提供されない。** 必要なら 3 軸加速度から自前で出す
  - **ヨーは AR 起動基準の相対値。** 磁力計は入っていないので、絶対方位にはキャリブレーションが要る
  - ファームは `FEATURE_VERSION 2.0.0` 以上が対象。それ未満では何も起きない
  - 切断するとグラス側で止まるので、再接続後も続けるなら呼び直す
  - サンプル実装 — `samples/kmp/app/.../ui/ImuScreen.kt`
- ジェスチャー — `gestureEvents`（`SINGLE_TAP` / `DOUBLE_TAP` / `HOLD`）
- グラスのマイク — `openGlassMic()` / `closeGlassMic()` / `GlassClient.micChannel`
- 電源イベント、リモコンイベントのリスナー

### グラスの姿勢を取る（0.1.0 で追加）

```kotlin
fun startImuData()
fun stopImuData()
val imuData: SharedFlow<CommandManager.ImuData>
val imuDataStarted: StateFlow<Boolean>
```

- 1 サンプル = 加速度[mg] / 角速度[dps] / **ピッチ[度]** / **ヨー[度]** / AR 起動からの経過時間[ms]
- **ヨーは磁力計が無いのでドリフトする**（±180 で折り返す）。**絶対方位はスマホのコンパスから取るしかない**
- ピッチは取付補正済みで**上向きが負**
- 並べ替えや間隔の計算は受信時刻ではなく `timestampMs` を使う
- 送信キューが詰まるとグラス側がサンプルを捨てる。**指定した周期どおりには届かない**
- **FEATURE_VERSION 2.0.0 以上のファームが対象。** それ未満では `startImuData()` を呼んでも何も起きない
- 切断するとグラス側で止まる。再接続後に続けるなら呼び直す
- `enterImuDebugPage()` は**ページを開くだけ**で、値はこの API から取る

### 取れないもの（0.1.0 時点）

- **カメラ映像** — グラスから画像は取れない
- **音声出力** — `openGlassMic` / `micChannel` は入力のみ。**音を鳴らす API は無い**
- **絶対方位** — 6DoF に磁力計は無く、ヨーは起動基準の相対値でドリフトする

### 設計への含意

- 星座の特定 = **スマホのセンサー（方位・傾き・位置・時刻）から計算**
- グラスの 6DoF が使えるようになったので、**ピッチとロール（＝仰角と傾き）はグラスから絶対値で取れる**（加速度計が重力を測るため）
  - **方位（ヨー）だけは絶対基準が無い。** ここを天体アライメントで埋める
  - **スマホのコンパスでは代替できない。** スマホの磁気が示すのはスマホの方位で、頭とスマホの相対姿勢は未知
  - 詳しくは [docs/team-e/coordinate-system.md](docs/team-e/coordinate-system.md)
- グラスへの出力 = **196x196 の星図画像**＋**テキスト解説**の組み合わせ
- 画像送信が使えない事態（サイズ制約、エンコード負荷、ファーム差異）に備え、**テキストだけでも成立する経路を残す**
- → **星座特定・解説生成とグラス出力を分離する。** 出力層だけ差し替えられる形にしておく

### エージェントへの指示

- 画像送信は**まだ team-e の誰も実機で試していない**。動作を断定しない
- `sendImage` に渡すのはグレースケール。**RLE エンコーダを自前で書かない**（0.0.12 で SDK 側に入った）
- 6DoF も 0.1.0 で入ったばかりで**実機未確認**。手元のファームの FEATURE_VERSION も確かめていない

## ドキュメントサイトの仕組みと CI の落とし穴

CI（`.github/workflows/docs.yml`）が回すもの：

- `python3 scripts/sync-snippets.py --check` — Kotlin のコード例と `docs/` の差分（全 PR と main への push）
- `bundle exec jekyll build` — サイトがビルドできるか（PR のみ）

**コード例のコンパイルと ktlint は CI から外している**：

- SDK が private な GitHub Packages にあり、取得に `read:packages` の PAT が要るため
- team-e は全員ローカルに PAT を持っているので、検証は手元で行う

```bash
cd samples/kmp && ./gradlew :snippets:compileDebugKotlin :snippets:ktlintCheck
```

よくある落とし方：

- `samples/kmp/snippets/**` を直して `sync-snippets.py` を実行し忘れる → CI が落ちる
- `docs/api/**` のコードブロックを手で書き換える → 出処は Kotlin 側なので CI が落ちる
- `:snippets` をアプリコードの置き場と勘違いして壊す → CI では気づけない。**手元で Gradle を回す**

編集してはいけない生成物：

- `docs/_data/api_links.yml` — `scripts/gen-api-docs.py` が `SPEC` から生成
- `Package.swift` の KMMBRIDGE ブロック
- `docs/_site/` — Jekyll のビルド成果物（`.gitignore` 済み）

その他：

- `docs/**` の Markdown では公開 API 名をバッククォートで囲むだけで自動リンクされる（`docs/_plugins/api_autolink.rb`）。`[...](...)` は書かない
- **GitHub Pages への公開はしていない。** SDK ドキュメントは上流が
  <https://jig-sabera.github.io/sabera-sdk/> で公開しており、team-e が二重に出す必要がないため
- 読むだけなら上流の公開サイトが早い。`docs/` を直して見た目を確かめたいときだけ `cd docs && bundle exec jekyll serve`

## 未決定事項

決まったらこのファイルを更新する。エージェントは勝手に埋めない。

- 196x196 / 3bit グレースケールで星図をどう描くか（星の等級表現、星座線、文字の可読性）
- 星図の出し先 — 画像表示ページ（196x196、余計な表示なし）かナビページ（240x240 だがナビの枠が付く）か
- グラスの 6DoF とスマホのセンサーをどう組み合わせるか（ピッチはグラス、方位はスマホ、で足りるか）
- 音声解説をどこから鳴らすか（SDK に出力 API が無い）
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
