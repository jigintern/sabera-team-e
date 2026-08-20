# AGENTS.md

- このリポジトリで作業する AI エージェント（Claude Code / Codex など）向けの共通ガイド
- 人間向けの手順は [CONTRIBUTING.md](CONTRIBUTING.md)

## プロジェクト

- **SABERA** = スマートグラス
- このリポジトリ = SABERA App SDK を使って **team-e** がアプリを開発する場所
- 目標 = **空にかざしたグラスの視界と星座を重ね、AI に頼むと今見えている星座を解説してくれるアプリ**
- 星座に続けて、**人工衛星モード**（星座と切り替えて、いま通っている衛星を同じ星図に出す）を作る。
  調査は [docs/team-e/satellites.md](docs/team-e/satellites.md)

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
- 上流の公開ドキュメント = <https://jig-sabera.github.io/sabera-sdk/>（`docs/` をビルドしたもの）
- **`f3db995`（SDK 0.6.0）時点まで取り込み済み**
- どのメソッドがどの版から使えるかは上流の `docs/api-history.md`（0.4.0 で追加）にまとまっている
- team-e 独自ファイル（README / AGENTS.md / CLAUDE.md / CONTRIBUTING.md / .gitignore）は同期対象外
- `Package.swift` は上流でも 0.0.10 のまま。iOS は追従していない

上流を取り込み直すとき：

```bash
git remote add upstream https://github.com/jig-SABERA/sabera-sdk   # 初回のみ
git remote set-url --push upstream no_push                          # 誤 push 防止
git fetch upstream
git checkout upstream/main -- docs samples scripts

# 上流が消したファイルは checkout では消えない。残ったものを洗い出す
comm -23 <(git ls-files docs samples scripts | sort) \
         <(git ls-tree -r --name-only upstream/main -- docs samples scripts | sort)
# ↑ team-e 所有のファイル（docs/team-e/ など）が混ざっていないか見てから git rm

# 上流は docs/_site を追跡しているが team-e では追跡しない
git rm -r --cached docs/_site && rm -rf docs/_site
```

**checkout の対象に入れてはいけないもの**（上書きすると team-e の変更が消える）：

| パス | 理由 |
|---|---|
| `README.md` / `AGENTS.md` / `CLAUDE.md` / `CONTRIBUTING.md` | team-e 独自ファイル |
| `.github/workflows/docs.yml` | team-e 側で CI 方針を変えている（後述） |
| `NOTICE` | team-e が星表データ（d3-celestial / XHIP）の帰属を足している |
| `.gitignore` | `docs/_site/` と `tools/.cache/` の除外は team-e 側の追加 |

- `Package.swift` / `LICENSE` は上流が変えたときだけ個別に取り込む
- **`git checkout <tree> -- <path>` は上流で削除されたファイルを消さない。** 0.0.14 で撤去された
  `enter-ai-page.md` などが手元に居残った実例があるので、上の `comm` は毎回走らせる

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
  - **例外 = 分割レイアウトと自由配置キャンバス（0.1.1 / 0.2.0）。** 送るだけで画面が切り替わる

詳細は `docs/getting-started.md` と `docs/api/`。

## グラスに何を出せるか

**team-e の仕様を左右する最重要事項。** 0.0.11 で画像送信が入り、**星図をグラスに出す道が開いた**。
0.1.0 で **6DoF（グラスの姿勢）** が解放され、0.1.1 / 0.2.0 で **分割レイアウト**と
**576×360 の自由配置キャンバス**が入った。0.4.0 でキャンバスに画像が置けるようになり、
**0.6.0 で画像に `id` が付いて 8 枚まで並べられるようになった**。

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

→ このページは 196x196 が上限。**より広く出したいならキャンバスの画像（0.4.0、576×360 まで）を使う**。

**表示パネルそのものは 576×360。** 0.2.0 のキャンバスがこの座標で要素を置くので判明した。

- **画像表示ページの 196x196 はパネルの一部でしかない**（等倍で出るなら幅の 34%・高さの 54%）
- `要確認` 196x196 が拡大して出るのか等倍で出るのか、パネルのどこに出るのかは実機未確認
- 画角（何度分を占めるか）は別問題で、**パネルの画素数が分かっても角度は分からない**

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
- **0.0.14 で `enterAIPage` / `enterMeetingPage` / `enterNotificationPage` が撤去された**（ファームが対応していないため）
- **0.5.0 でアプリ本体に実装が無いメソッドがまとめて撤去された** — `sendMeeting` /
  `sendAIContent` / `sendAiChatSender` / `sendEmptyScreenStatus` / `sendTeleprompterGenerating` /
  `requestLog` / `requestNotificationCountSync` と、**電源・リモコンのイベントリスナー4つ**
- Teleprompter は行送り・進捗・時刻の API が揃っている（`sendTeleprompterLine` など）

### 分割レイアウトにテキストを出す（0.1.1 で追加）

```kotlin
fun sendLayout(mode: CommandManager.LayoutMode, texts: Map<Int, String> = emptyMap())
fun sendLayoutTexts(texts: Map<Int, String>)
fun closeLayout()
```

- `FULL` / `TOP_BOTTOM` / `LEFT_RIGHT` / `QUAD` の 4 分割。領域番号は分割ごとに意味が変わる
  （`TOP_BOTTOM` なら 0=上・1=下、`QUAD` なら 0=左上・1=右上・2=左下・3=右下）
- **`sendLayout` を送るだけで画面が切り替わる。** ページを先に開く必要はない
- `sendLayout` はレイアウトを作り直すので**全領域のテキストが消える**。差し替えだけなら `sendLayoutTexts`
- **分割して送れないので、テキストの合計は 190 バイト程度まで**
- `FEATURE_VERSION 2.0.0` 以上のファームが対象

### 自由配置キャンバス（0.2.0 でテキスト、0.4.0 で画像、0.6.0 で複数枚）

```kotlin
fun sendCanvas(elements: List<CommandManager.CanvasElement>)
fun sendCanvasElements(elements: List<CommandManager.CanvasElement>)
fun sendCanvasImage(id: Int, x: Int, y: Int, width: Int, height: Int, grayscale: ByteArray)
fun removeCanvasImage(id: Int)
fun clearCanvas()
fun closeCanvas()
```

- **キャンバスは 576×360、左上が原点。** 画像表示ページ（196x196）よりずっと広い
- **`sendCanvas` / `sendCanvasImage` は送るだけで画面が切り替わる。** ページを先に開く必要はない

テキスト要素：

- `CanvasElement(id, x, y, width, height, text)`。id は **0..7 の 8 個まで**
- はみ出した矩形は端で切られ、外に出た要素は描かれない
- `sendCanvas` は全消去してから並べ直す。`sendCanvasElements` は**差分更新**
  （既存 id は座標ごと差し替え、テキストを空にするとその id が消える）
- **テキストの合計は 190 バイト程度まで。** 収まらないときは `sendCanvasElements` で 1 要素ずつ積む

画像（0.4.0 で追加、0.6.0 で複数枚）：

- 渡し方は `sendImage` と同じ（1 画素 1 バイトのグレースケール。量子化と圧縮は SDK 側）
- **`x + width` は 576、`y + height` は 360 まで**
- **id は 0..7 の 8 枚まで。** 同じ id に送ると座標ごと差し替わる。消すのは `removeCanvasImage(id)`
- 0.6.0 の AAR を逆アセンブルして分かった、SDK が弾く条件（例外は `IllegalArgumentException`）：
  `0 <= id < 8` / `width > 0` / `height > 0` / `x >= 0` / `y >= 0` /
  `x + width <= 576` / `y + height <= 360` / **`width * height * 2 + 圧縮後サイズ <= 380,000`**
- 圧縮は **1 バイトに `(値3bit shl 5) or (連長 - 1)`**、連長は 32 まで、値は画素の上位 3bit。
  **同じ数え方をすれば送る前に上限を判定できる**（`feat/star-map` はそうしている）
- **画像バッファは全画像で共有していて、上限は 380,000 バイト。**
  置いてある画像の `width * height * 2` の合計に受信中の圧縮データを足した値で見る
  - **576×360 は画素だけで 414,720 になるので、1 枚でも入らない**（`require` で弾かれる）
  - 192 角なら 5 枚が目安。**16:10 で実用上の最大は 528×330**（348,480。圧縮後に 23,899 バイト残る）
- **画像はテキスト要素の背面に描かれる**
- `clearCanvas()` はテキストごと画像も消す
- **ナビの全体ルート画像とバッファを共有しているので、ナビ表示中は使えない**
- 数百バイトずつに分けて送るため、**大きい画像ほど表示まで時間がかかる**
- **0.6.0 から SDK が分割送信を直列化する。** 続けて呼んでもチャンクが混ざらない
  （0.5.0 までは `sendCommand` / `sendCommands` が毎回別コルーチンで書き出すため、
  送信が重なるとどの画像も組み立てられなかった）

ファーム要件は段階的に上がる — レイアウト `2.0.0` / キャンバス `2.1.0` / **キャンバス画像 `2.2.0`**。

→ **星図とラベルを同じ画面に出せる。** キャンバスに星図を置き、
星座名を任意座標のテキストで重ねられる。196x196 の画像表示ページより広く、テキストと排他でもない。

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
  - `enterImuDebugPage()` は**ページを開くだけ**で、値はこの API から取る
  - サンプル実装 — `samples/kmp/app/.../ui/ImuScreen.kt`
- **マイクの PCM ストリーミング（0.3.0 で追加、0.3.1 で増幅）**

```kotlin
fun startMicStreaming()
fun stopMicStreaming()
val micAudio: SharedFlow<ByteArray>
val micStreaming: StateFlow<Boolean>
```

  - **`PCM16` リトルエンディアン / 16kHz / モノラル。** 1 チャンク 640 バイト（20ms）
  - デバイス世代で形式が変わる（Ogg Opus / record stream）が、**判別とデコードは SDK 側**
  - グラスの録音は小さいため、**SDK が 3 倍に持ち上げてから流す**（0.3.1）
  - **購読が遅れると古いデータから捨てる。** 録音として貯めるなら受け取り側でバッファする
  - 文字起こし API に投げるなら **WAV ヘッダ（44 バイト）を付ける**。リサンプルは不要
  - 24kHz を要求するリアルタイム API には **16kHz から 24kHz へリサンプル**してから送る
  - 生の Opus を自分で扱うなら従来の `openGlassMic()` + `GlassClient.addAudioDataEventListener`
  - 止めるまでマイクは開いたまま。使い終わったら `stopMicStreaming()`
- ジェスチャー — `gestureEvents`（`SINGLE_TAP` / `DOUBLE_TAP` / `HOLD`）
- **電源・リモコンのイベントリスナーは 0.5.0 で撤去された**
  - `RemoteControlListener`（`onPrev` / `onNext` / `onEsc`）ごと 0.6.0 の AAR から消えている。
    **リモコン操作をアプリで拾う手段は無い**
  - それでも**リモコンの戻る操作でキャンバスと分割レイアウトは閉じる**
    （`closeCanvas` / `closeLayout` の説明）。**閉じられたことに気づけないまま**
    出しっぱなしの画面が消えるので、送り直せる作りにしておく
- **逃げ道 — `GlassClient.sendCommand(ByteArray)` / `sendCommandList` / `sendText`**
  - `CommandManager` に無い操作が要るときの生の経路。**上流の本文は未執筆で、電文の仕様も非公開**
  - **使う前に SDK チームに聞く。** 自力で電文を組み立てない
- **`GlassClient.cancelPendingPackets()` — 送信待ちのパケットを捨てる**
  - 上流の本文は未執筆だが、名前のとおりなら**古い星図フレームを積ませずに捨てられる**
  - 首を振ったときに前のフレームが順番待ちで残る問題への手当てになる
  - `要確認` 実際の挙動。**0.6.0 で SDK が分割送信を直列化したので、
    「積んだフレームを捨てる」用途はこちらに寄せられる可能性がある**

### グラスの設定を書き換える（`sendSetting`）

```kotlin
fun sendSetting(name: String, value: Int)      // Boolean / String / ByteArray の overload もある
fun requestSettingSync()                        // 全設定値の送信を要求。応答は parseResponse
```

**上流ドキュメントに設定キーの一覧が無い。** 以下は AAR を `javap` で読んで拾った
`CommandManager.SettingKey` の 17 個（値は定数名と同じ。`NOTIFICATION_CONTENT_MASK` だけ
実値が `NOTIF_CONTENT_MASK`）。**0.6.0 の AAR でも 17 個のまま変わっていない**。

| キー | 推測される用途 | team-e への効き |
|---|---|---|
| `BRIGHTNESS_LEVEL` / `BRIGHTNESS_AUTO` | 表示の明るさ・自動調整 | **暗順応。明るいまま出すと肉眼の空が見えなくなる** |
| `SCREEN_OFF_TIME` | 消灯までの時間 | **見上げ続ける用途なので、途中で消えると成立しない** |
| `FEATURE_VERSION` | ファームの機能バージョン | **キャンバス画像（2.2.0）が使えるかを実行時に判定できる** |
| `FONT_SIZE` | 文字の大きさ | キャンバス・レイアウトに入る文字数が変わる |
| `DISPLAY_HEIGHT_LEVEL` / `DISPLAY_DISTANCE_LEVEL` | 表示位置・見かけの距離 | 表示面が視界のどこに出るか |
| `MESSAGE_DISPLAY_MODE` / `MESSAGE_DISPLAY_TIME` / `NOTIF_CONTENT_MASK` | 通知の出し方 | **観測中に通知が割り込むと星図が消える** |
| `AR_SYSTEM_MODE` / `AR_NAME` / `AR_TYPE` / `AR_VERSION` | AR 側の識別情報 | 未調査 |
| `INSCRIPTION_MODE` | 刻印表示（`clearInscriptionText` と対） | 未調査 |
| `RCP_MAC` / `RST` | リモコンの MAC / リセット | 未調査 |

- **値の範囲・単位・書き換え可否はすべて未確認。** キー名が読めただけで、意味は推測
- **読み出しの経路が不明。** `requestSettingSync()` の応答は `parseResponse(ByteArray)` で受けるが、
  上流の本文が未執筆で、パース後の値がどこに出るのか公開 API から辿れない
- `要確認` **SDK チームに聞く価値が高い。** 特に明るさ・消灯時間・`FEATURE_VERSION` の読み方

### 見上げたときのウェイクアップ（`sendWakeupTiltThreshold`）

```kotlin
fun sendWakeupTiltThreshold(degrees: Int)   // 0..65535
fun enterGlassAngleAdjustmentPage()          // 調整ページを開く
```

- ヘッドアップ（見上げ）でグラスが起きる**傾きのしきい値**
- **星を見る = ずっと見上げている**ので、この設定と正面衝突する可能性がある
- 逆に、**見上げた瞬間に起きる**のは体験として噛み合う。しきい値の調整でどちらにも振れる
- `要確認` 実機で見上げっぱなしのときに何が起きるか

### グラスの状態を問い合わせる（`requestSystemStatus`）

- **バッテリー残量・装着状態・充電状態**の通知を要求する
- **装着状態が取れるのは大きい。** かけていないのに星図を送り続ける無駄を避けられる
- 応答は `parseResponse` 経由なので、**上と同じ理由で読み出し経路が不明**

### グラスに方位を渡す口はある（`sendNaviCourse`）

```kotlin
fun sendNaviCourse(courseDegrees: Double)
```

- 上流の説明 = 「端末の GPS 進行方向を送る。グラスは磁力計を持たず方位を単体で保てないため、
  **この値をジャイロのドリフト補正に使う**。案内中に数秒おきに送る」
- **ファームにヨーの外部補正が入っている証拠。** 0.1.0 からあるが team-e では見落としていた
- 引数はただの角度なので、**GPS 進行方向でなくてもよい**。こちらで求めた方位を入れられる

ただし**これで方位キャリブレーションが要らなくなるわけではない**：

- **GPS 進行方向は「歩いている向き」であって「顔の向き」ではない。** 星を見るときは立ち止まるので値が出ない
- **送る値をどこから得るかという問題がそのまま残る。** キャリブレーションはその値を作る工程なので、
  `sendNaviCourse` は**答えではなく答えの置き場**
- 「案内中に送る」とあり、**ナビ状態でないと効かない可能性がある**
- `要確認` **最重要 —** 補正後の方位が `imuData` の `yawDegrees` に返るのか。返るなら
  **ドリフト補正をファームに任せられ**、こちらは初期オフセットを与えるだけで済む

### 取れないもの（0.6.0 時点）

- **カメラ映像** — グラスから画像は取れない
- **音声出力** — `startMicStreaming` / `openGlassMic` はいずれも入力のみ。**音を鳴らす API は無い**
- **絶対方位** — 6DoF に磁力計は無く、ヨーは起動基準の相対値でドリフトする
  - ただし**外から方位を入れる口は `sendNaviCourse` にある**（上記）。値を作る工程は残る

### 設計への含意

- 星座の特定 = **スマホのセンサー（方位・傾き・位置・時刻）から計算**
- グラスの 6DoF が使えるようになったので、**ピッチとロール（＝仰角と傾き）はグラスから絶対値で取れる**（加速度計が重力を測るため）
  - **方位（ヨー）だけは絶対基準が無い。** ここを天体アライメントで埋める
  - **スマホのコンパスでは代替できない。** スマホの磁気が示すのはスマホの方位で、頭とスマホの相対姿勢は未知
  - 詳しくは [docs/team-e/coordinate-system.md](docs/team-e/coordinate-system.md)
- グラスへの出力 = **キャンバスに星図画像＋星座名ラベル**が第一候補（0.4.0、0.6.0 で複数枚）
  - 画像表示ページ（196x196）より広く、**テキストと画像が同じ画面に共存できる**
  - **全画面 576×360 は画像バッファに入らない。** 16:10 のまま **528×330** に落とす
  - `FEATURE_VERSION 2.2.0` 以上が要る
  - 動かない場合の退路 — 画像表示ページ（196x196）／キャンバスのテキストだけ／全画面テキスト
- 「AI にお願いする」の音声入力は**グラスのマイクで成立する**（0.3.0）。
  PCM16 / 16kHz が取れるので、WAV に包めば文字起こし API にそのまま渡せる
- 画像送信が使えない事態（サイズ制約、エンコード負荷、ファーム差異）に備え、**テキストだけでも成立する経路を残す**
- → **星座特定・解説生成とグラス出力を分離する。** 出力層だけ差し替えられる形にしておく

### エージェントへの指示

- `sendImage` に渡すのはグレースケール。**RLE エンコーダを自前で書かない**（0.0.12 で SDK 側に入った）
- マイクの PCM を扱うとき **Opus のデコーダを自前で書かない**（0.3.0 で SDK 側に入った）
- **キャンバスの画像は id 0..7 の 8 枚まで。** ただし置ける総量はバッファ 380,000 バイトで、
  `width * height * 2` の合計で数える。**枚数ではなく面積で設計する**
- **転送量は面積でほぼ決まる。** 3bit RLE は真っ黒でも 32 画素で 1 バイト使うので、
  `面積 / 32` バイトが下限。**528×330 の星図は圧縮後 6〜7KB で、その 8 割は真っ黒な背景**。
  星座線を切っても 1 割しか減らない
- **1 パケットあたりの時間は確かめられていない。** 200 バイトずつ送るのは確かだが、
  以前の「30ms/本」は根拠が実測メモだけで、`feat/star-map` では 20ms を初期値にして
  画面のつまみで詰められるようにしてある。**短くしすぎても 0.6.0 は直列化するので
  絵は壊れず、順番待ちが伸びるだけ**
- **「なめらかに動かす」は成立しない。** 全画面を毎回送り直す方式なので、
  視線が一定以上動いたときだけ送り直す作りにする
- **0.6.0 の直列化は「積んでよい」という意味ではない。** チャンクは混ざらなくなったが、
  送信の呼び出しは積むだけで返るので、転送が追いつかないと古いフレームが順番待ちで残る
- 実機で確かめたこと（`feat/star-map`）：
  - `sendCanvas`（テキスト）と `enterImageDisplayPage` ＋ `sendImage` は動く
  - **`sendCanvasImage` に 576×360 を渡すと必ず `require` で落ちる。** ファームではなく SDK の制限
  - **528×330 の星図＋星座名は 0.6.0 で通る**（バッファ使用 355,000B 前後）
  - **転送中は前の絵が消える。** 動くたびに送ると「点いては消える」だけになるので、
    首が止まってから送る（0.4 秒静止 ＋ 前の絵から 6° 以上ずれたら）
  - `imuData` は 10Hz 程度で流れ続ける。**画像を送っている間も止まらない**ので、
    「6DoF が途切れたら転送中」という見分け方はできない
  - 手元のファームは**キャンバス画像が動く**。ただし **FEATURE_VERSION の数値は未取得**で、
    配布先のグラスが同じとは限らないので、**退路（196x196 の画像表示ページ）は残す**
- `sendNaviCourse` を**方位問題の解決として扱わない**。値を作る工程は残る（上記）
- **画素数と画角を混同しない。** パネルが 576×360 と分かっても、視野の何度を占めるかは別に確かめる
- **`SettingKey` の一覧は AAR から読み取ったもの**で、上流ドキュメントには無い。
  **値の意味と範囲は未確認なので、動作を断定しない**

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

## 決まったこと（プロトタイプで確定）

`feat/star-map` を実機で動かして決まったもの。仕様の詳細は
[docs/team-e/](docs/team-e/) を見る。

- **星図の出し先と大きさ** — キャンバスに **528×330**。等級は「明るさ ＋ 点の大きさ」で表し、
  点は丸く打つ（四角だと 17×17 の塊に見える）
- **星座名** — 画像に焼かず、キャンバスのテキストで重ねる。矩形は文字数から取る
  （120×22 では「みなみのかんむり座」が切れて読めなかった）。重なった名前は視野中心に
  近いほうを残す。**画像を送ってから名前を送る**（逆順だと名前だけが 1 秒近く先に出る）
- **描き直しの条件** — 転送中は前の絵が消えるので、**首が止まってから送る**
  （0.4 秒静止 ＋ 前の絵から 6° 以上ずれたら）。動きに追従させると点滅にしかならない
- **姿勢と方位** — 仰角と方位の相対値はグラス、絶対方位の基準はスマホ。合わせるときは
  **グラスに十字を出してスマホのマーカーと重ねてもらう**（スマホの位置ずれ由来の誤差が消える）。
  残るのは地磁気そのものの誤差なので、**次は同じ十字で天体アライメント**
- **観測地** — スマホの測位（融合 → GPS → 基地局）。取れないときだけ手入力
- **パーミッション** — `ACCESS_FINE_LOCATION` ＋ `ACCESS_COARSE_LOCATION` を起動時にまとめて聞く
- **星表** — 自前計算。データはリポジトリ同梱の `data/`（d3-celestial / XHIP。帰属は `NOTICE`）
- **スマホ側 UI** — 画面はスキャンと星図の 2 つだけ。星図の画面は「方位を合わせる」
  「グラスに映っているものを見る」「ログを見る」の 3 つに絞る

## 未決定事項

決まったらこのファイルを更新する。エージェントは勝手に埋めない。

- 音声解説をどこから鳴らすか（SDK に出力 API が無い）
- 「AI にお願いする」の入力経路 — グラスのマイク（`startMicStreaming` で PCM が取れる）かスマホ側か
- 解説文の生成に使う LLM と呼び出し場所（端末直かバックエンド経由か）
- 解説テキストの表示先 — `enterEmptyScreenPage` / AI Chat / Teleprompter / 分割レイアウト / キャンバス
- 発話の区切りをどう決めるか（`micAudio` は流れ続けるので、どこで文字起こしに送るか）
- **マイクと星図が同じ BLE を食い合う。** 音声を流しながら画像を送れるか
- API キーなど秘密情報の持ち方（`.gitignore` は `.env` を除外済み）
- `sendNaviCourse` でファームにヨー補正を任せられるか（`imuData` に返るかが未確認）
- **0.6.0 のキャンバス画像 8 枚を使うか。** 星図を分割して動いた部分だけ送り直せるが、
  バッファは面積の合計で数えるので総量は減らない
- **1 パケットあたりの転送時間。** 200 バイトずつ送るのは確かだが、実時間を測る手段が無い。
  いまは 20ms を初期値に画面のつまみで詰めている
- **天体アライメントに使う基準天体**（月・明るい星）と、その選び方
- **人工衛星モード** — SGP4 を自前で書くか、TLE をいつ更新するか、
  見えない衛星も出すか、モードをどう切り替えるか（[調査](docs/team-e/satellites.md)）
- **観測地の測位を続けるか**（いまは画面に入ったとき 1 回だけ）
- **観測中のグラス設定**（明るさ・消灯時間・通知の抑止）をアプリが書き換えるか、ユーザーに任せるか
- **`FEATURE_VERSION` が読めないので、退路への切り替えをどう判定するか**
  （「送って反応を見る」か「整備画面で選ばせる」か）

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
