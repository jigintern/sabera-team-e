# AGENTS.md

このリポジトリで作業する AI エージェント（Claude Code / Codex など）向けの共通ガイド。
人間向けの手順は [CONTRIBUTING.md](CONTRIBUTING.md)、仕様は [docs/team-e/index.md](docs/team-e/index.md)。

## プロジェクト

- **SABERA** = スマートグラス。このリポジトリ = SABERA App SDK で **team-e** がアプリを作る場所
- 目標 = **空にかざしたグラスの視界と星座を重ね、AI に頼むと今見えている星座を解説してくれるアプリ**
- 続けて**人工衛星モード**（星座と切り替えて、いま通っている衛星を星図と同じ座標で出す。
  **星座とは排他で、衛星モードには星を描かない**）

### 現在のフェーズ：実装中

`samples/kmp/app` が team-e のアプリ本体。**実機で動いているもの**と**テストだけのもの**は
[docs/team-e/index.md](docs/team-e/index.md) の表で分けている。

エージェントとして作業するときの原則：

- **頼まれていないアプリ機能を先回りして実装しない**
- **未決定事項に行き当たったら勝手に決めず、[未決定事項](#未決定事項) に追記するか質問する**
- **実機（BLE）でしか確かめられないことが多い。確かめていないことを「動く」と書かない**

## リポジトリ構成

| パス | 中身 |
|---|---|
| `samples/kmp/app/` | **team-e のアプリ本体**（Kotlin + Compose）。ここを書き換えて育てる |
| `docs/team-e/` | **team-e の仕様書** |
| `docs/github-pat.md` | private SDK を取得するための GitHub PAT 設定 |
| `data/` | 同梱データ（星表・星座線・TLE）。**すべて生成物** |
| `tools/` | 同梱データの生成スクリプトと天球シミュレータ |

### アプリ内の責務

| パス | 責務 |
|---|---|
| `ui/GlassesApp.kt` | 4 画面の遷移。画面は `AppScreen` で表し、整数を増やさない |
| `ui/CalibrationScreen.kt` | 方位合わせの唯一の実装。観測画面へ同じ処理を重ねない |
| `ui/StarMapScreen.kt` | 観測セッションの調停。描画・キャンバス変換・補正計算は下記へ委譲する |
| `ui/ObservationComponents.kt` / `AppTheme.kt` | 星座・衛星で共通の表示部品と色 |
| `starmap/GlassCanvasFrame.kt` | パネル寸法、画像バッファ、テキスト制限、RLEサイズ見積り |
| `starmap/ObservationDefaults.kt` / `Directions.kt` | 観測の既定値と方位表現 |
| `starmap/YawDriftCorrector.kt` | Android 非依存のヨードリフト補正。変更時は JVM テストも更新する |
| `starmap/CelestialAlignment.kt` / `AlignmentTargets.kt` / `Ephemeris.kt` | 天体アライメント（方位合わせの段階2）。Android 非依存。**月・惑星の位置はここだけ** |
| `starmap/MagneticQuality.kt` | 磁気の歪みの検証。OS の信頼度を信じない |
| `starmap/RollEstimator.kt` / `AccelAxisProbe.kt` | ロールと、その軸割り当ての自動判定。**既定オフ** |

## 開発コマンド

```bash
cd samples/kmp
./gradlew :app:installDebug              # 実機にインストール
./gradlew :app:testDebugUnitTest         # JVM テスト（座標変換・SGP4・AI 周り）
./gradlew :app:assembleDebug             # Debug APK を生成
```

```bash
python3 tools/build-star-catalog.py      # data/ の星表を作り直す
python3 tools/build-satellites.py        # data/ の TLE を取り直す
python3 tools/build-simulator.py --check # 天球シミュレータ生成物の差分を検査
```

### 前提

- JDK 17 / Gradle 8.10.2（wrapper 同梱）/ Kotlin 2.3.10 / AGP 8.7.0
- Android `minSdk 31` / `compileSdk 36` / `targetSdk 36`
- **BLE 実機が必須。エミュレータでは動作確認できない**
- SDK は private な GitHub Packages 配布。**`read:packages` の PAT が無いとビルドが落ちる**
- `.editorconfig`（4スペース / 120桁 / intellij_idea）は全 Kotlin に効く
- **`:app:lintDebug` は現在ツール側でクラッシュする。** AGP 8.7.0 と Kotlin 2.3.10 の解析 API が合わず、
  `RememberInComposition` / `NullSafeMutableLiveData` detector が `IncompatibleClassChangeError` になる。
  detector を無効化して通したことにせず、ツールチェーン更新時に戻す

## 上流 SDK との同期

- 上流 = [jig-SABERA/sabera-sdk](https://github.com/jig-SABERA/sabera-sdk) /
  公開ドキュメント = <https://jig-sabera.github.io/sabera-sdk/>
- **`f3db995`（SDK 0.6.0）時点まで取り込み済み**
- どのメソッドがどの版から使えるかは上流リポジトリの `docs/api-history.md`
- **iOS は追わない。** `Package.swift` は上流でも SDK 0.0.10 のままで、team-e では撤去した

### SDK には `sources.jar` が付いている（逆アセンブルより先にこれを読む）

**バイナリ配布だが中身は読める。** Gradle Module Metadata に
`releaseSourcesElements-published` が正式なバリアントとして宣言されているので、
**`read:packages` の PAT があれば誰でも取れる**（0.0.10 以降のどの版にもある）。

```bash
cd ~/.gradle/caches/modules-2/files-2.1/jp.jig.sabera.app.sdk/sabera-app-core-android/0.6.0
unzip -o */*-sources.jar -d /tmp/sabera-src && ls /tmp/sabera-src/commonMain/app/jigglass/glass
```

- 中身は**実装ごと入った Kotlin ソース**。`PacketCommandUtils.kt` は 1,937 行あり、
  **電文の組み立てが全部読める**（コマンド表・TLV・分割送信）
- Android Studio なら依存に付いているので、そのまま定義へ飛べる
- **`javap` や逆アセンブルは要らない**
- **まだソースを読み直していない `要確認` が残っている** — `cancelPendingPackets` の実体、
  `parseResponse` の応答の受け取り先、`FEATURE_VERSION` の読み出し経路。
  **まとめて調べる価値が高い**

### SDK を更新する手順

1. `samples/kmp/app/build.gradle.kts` の SDK バージョンを更新する
2. 上流の公開ドキュメント、`docs/api-history.md`、取得した `sources.jar` で破壊的変更を確認する
3. 必要な変更だけを team-e のアプリへ手動で反映する
4. `:app:testDebugUnitTest` と `:app:assembleDebug` を実行する
5. BLE やグラス表示に関わる変更は実機で確認し、未確認ならその旨を文書に残す

上流の `docs/`、`samples/`、`scripts/` をディレクトリ単位で checkout しない。このリポジトリは
team-e アプリに必要なものだけを保持し、上流のサンプル・SDK ドキュメントサイトは追跡しない。

## SDK のアーキテクチャ

依存 = `jp.jig.sabera.app.sdk:sabera-app-core`（バイナリ配布）。
**接続のライフサイクルは一本道で、途中を飛ばせない。**

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
- `GlassClient` は切断手段を持たない（`GlassClientInternal` に隔離）。
  **UI 層が接続状態を抱えないための制約なので回避しない**
- `gestureEvents` は `SharedFlow`。**購読開始前のジェスチャーは受け取れない**
- `setDevicePersistence` を省くとインメモリになり、プロセスをまたぐと接続先を忘れる
- コンテンツはページを開いてから送る。**例外 = 分割レイアウトと自由配置キャンバス**
  （送るだけで画面が切り替わる）

## グラスに何を出せるか

**team-e の仕様を左右する最重要事項。** 数値と制約の詳細は
**[グラス出力の制約](docs/team-e/glass-output.md)** に集約してある。ここでは要点だけ。

| 出し先 | API | 上限 | ファーム要件 |
|---|---|---|---|
| **自由配置キャンバス（本命）** | `sendCanvasImage` / `sendCanvasElements` | **画像 id 0..7・バッファ 380,000B / テキスト 8 要素・190B** | **2.2.0**（画像）/ 2.1.0（テキスト） |
| 分割レイアウト | `sendLayout` / `sendLayoutTexts` | テキスト合計 190B・4 分割 | 2.0.0 |
| 画像表示ページ（退路） | `enterImageDisplayPage` ＋ `sendImage` | **196×196** | なし |
| 汎用テキストページ | `enterEmptyScreenPage` ＋ `sendEmptyScreenContent` | 200B 超は分割して送られる | なし |
| ナビページ | `enterNavigationPage` / `sendNavi` / `sendNaviLargeImage` | 地図 255 / 上流サンプルは 240×240 | なし |

**team-e が使うのはキャンバスだけ。** 星図を `sendCanvasImage` で置き、星座名を
`sendCanvasElements` のテキストで手前に重ねる。
**画像表示ページ（196×196、星図だけ）の退路は設計済みだが、アプリには未実装。**

絶対に外せない数値：

- **パネルは 576×360。画像 1 枚の実用最大は 528×330**（576×360 は画素だけで 414,720 で入らない）
- **渡すのは 1 画素 1 バイトのグレースケール。** 量子化（3bit）と RLE 圧縮は **SDK が行う**
- **映るのは緑の 8 階調。黒は透明**（波導ディスプレイ）
- **転送中は前の絵が消える。** 首が止まってから送る（0.4 秒静止 ＋ 6° 以上のずれ）
- **ナビ表示中はキャンバス画像が使えない**（バッファを共有している）
- **リモコンの戻る操作でキャンバスは閉じられる**が、**閉じられたことに気づけない**
  （0.5.0 でリスナーが撤去された）。いつでも送り直せる作りにする

その他の版の動き：

- **0.0.14 で `enterAIPage` / `enterMeetingPage` / `enterNotificationPage` が撤去された**（ファーム非対応）
- **0.5.0 でアプリ本体に実装が無いメソッドがまとめて撤去された** — `sendMeeting` /
  `sendAIContent` / `sendAiChatSender` / `sendEmptyScreenStatus` / `sendTeleprompterGenerating` /
  `requestLog` / `requestNotificationCountSync` と、**電源・リモコンのイベントリスナー4つ**
- Teleprompter は行送り・進捗・時刻の API が揃っている（`sendTeleprompterLine` など）

## 入力を取る

### 6DoF センサー（0.1.0）

```kotlin
fun startImuData() / fun stopImuData()
val imuData: SharedFlow<CommandManager.ImuData>
val imuDataStarted: StateFlow<Boolean>
```

- **`startImuData()` を呼ぶまで `imuData` には何も流れない**
- 1 サンプル = `accelX/Y/ZMilliG` / `gyroX/Y/ZDps` / `pitchDegrees` / `yawDegrees` / `timestampMs`
- **`timestampMs` は AR 起動からの経過時間。並べ替えと間隔の計算は受信時刻ではなくこれを使う**
- **送信キューが詰まるとグラス側がサンプルを捨てる**（実測 10Hz 程度。
  **画像を送っている間も止まらない**ので「途切れたら転送中」という見分け方はできない）
- **ロールは融合値として提供されない。** 必要なら 3 軸加速度から自前で出す
- **ヨーは AR 起動基準の相対値。** 磁力計が無いので、絶対方位にはキャリブレーションが要る
- `FEATURE_VERSION 2.0.0` 以上。切断するとグラス側で止まるので、再接続後は呼び直す
- `enterImuDebugPage()` は**ページを開くだけ**で、値はこの API から取る

### マイクの PCM ストリーミング（0.3.0、0.3.1 で増幅）

```kotlin
fun startMicStreaming() / fun stopMicStreaming()
val micAudio: SharedFlow<ByteArray>
val micStreaming: StateFlow<Boolean>
```

- **`PCM16` リトルエンディアン / 16kHz / モノラル。** 1 チャンク 640 バイト（20ms）
- デバイス世代で形式が変わる（Ogg Opus / record stream）が、**判別とデコードは SDK 側**
- グラスの録音は小さいため、**SDK が 3 倍に持ち上げてから流す**
- **購読が遅れると古いデータから捨てる。** 録音として貯めるなら受け取り側でバッファする
- 文字起こし API に投げるなら **WAV ヘッダ（44 バイト）を付ける**。リサンプルは不要
- 24kHz を要求するリアルタイム API には 16kHz → 24kHz のリサンプルが必要
- 止めるまでマイクは開いたまま

### ジェスチャー

`gestureEvents` で `SINGLE_TAP` / `DOUBLE_TAP` / `HOLD`。**3 枠すべて埋まっている**
（[画面遷移とジェスチャー](docs/team-e/app-flow.md)）。

**リモコンのイベントリスナーは 0.5.0 で撤去された。** `RemoteControlListener`
（`onPrev` / `onNext` / `onEsc`）ごと 0.6.0 の AAR から消えているので、
**リモコン操作をアプリで拾う手段は無い**。

### 逃げ道 — 生の電文

`GlassClient.sendCommand(ByteArray)` / `sendCommandList` / `sendText`。
**電文の仕様は `sources.jar` の `PacketCommandUtils` で読める**が、
**読めることと勝手に投げてよいことは別。使う前に SDK チームに聞く。**
公開 API に無い電文はファーム側の想定外で、壊し方が分からない。

`cancelPendingPackets()` は名前のとおりなら**古い星図フレームを積ませずに捨てられる**。
`要確認` 実際の挙動。

## 取れないもの（0.6.0 時点）

| | 状況 |
|---|---|
| **カメラ映像** | グラスから画像は取れない |
| **絶対方位** | 6DoF に磁力計は無く、ヨーは起動基準の相対値でドリフトする |
| **音声出力** | **グラスから音は出せない。API が無いのではなく、ハードにスピーカーが無い** |

音声出力については、**4 層すべてで道が無いことを確認済み**（2026-08-20）：

| 層 | 確認したこと |
|---|---|
| コマンド表 | `PacketCommandUtils.CMDKey` の全 opcode に**音声出力の命令が無い**。`MIC_COMMAND 0x09` は入力専用 |
| BLE サービス | `AUDIO_SERVICE_UUID` にあるのは `AUDIO_NOTIFY_UUID` だけ。**WRITE 特性が無い** |
| 同梱ネイティブ | `jni/*/libopus.so` と `libopusdecoder.so` の**デコーダのみ**。エンコーダが無い |
| 実機 | 公開するのは**バッテリーサービスだけ**。A2DP / HFP / LE Audio のどれにも現れず、デバイスクラスは `0x001F00`（uncategorized＝音響機器ではない） |

- **実機にスピーカーが無いことは team-e が現物で確認した。** 鳴らす先が存在しない
- **音はスマホから鳴らす。** 夜の屋外でスピーカーなら同伴者にも聞こえる
- スマホに繋いだ Bluetooth イヤホンへ回す案は**見送った**（体験は近いが機材が増える）

方位を外から入れる口は `sendNaviCourse(courseDegrees)` にあるが、
**補正後の方位は `imuData` の `yawDegrees` に返らない**（2026-08-20 に実機で確認）。

- `course` を 2 秒おきに 5 回送っても、ヨーは**送った値から離れる方向**へ等速で動いた ＝ ただのドリフト
- ナビページに入って `sendNaviStatus(START)` にしても同じ
- **`enterNavigationPage()` でヨーの原点がリセットされる**（3 秒で 64.7° 飛んだ）。
  上流ドキュメントに無い挙動なので、ナビページに入るときは注意
- そもそも**送る値を作る工程がキャリブレーションそのもの**なので、
  返っていたとしても答えにはならなかった

**ヨードリフト率は約 44°/分（0.73°/秒）と実測し、対策を入れた。**
`yawDegrees` をそのまま使うと**星座の同定（±20°）が 27 秒で破れる**。

- **報告される `gyroZDps` のバイアスを引いても直らない**（平均 0.058°/秒 で 12.7 倍ちがう）
- **機体座標は `gyroXDps` が鉛直軸**、符号はヨーと同じ（90° を 7 回まわして確認）。
  ただし**自前積分は 10Hz の取りこぼしで回転 90° あたり 5〜15° 足りない**
- **採った方法 = ジャイロの大きさが 2°/秒 を超えている間だけ Δ`yawDegrees` を足し、
  そこから実測のドリフト率も引く。** 静止中は何も足さない。
  ドリフト率は**静止 5 秒ごとにアプリが自分で測る**（実測 −0.735〜−0.744°/秒）
- **実測で 44.5°/分 → 0.0°/分**（静止 4 分＋90° の回転 8 回）。
  ドリフトによる星図の再送（8.5 秒ごと）も止まった
- **ボトルネックは地磁気（±5〜15°）に移った。** 星座の同定（±20°）は成立、
  星図の重ね合わせ（±2〜3°）には天体アライメントが要る

詳細は [座標変換パイプライン ④](docs/team-e/coordinate-system.md)。

## グラスの設定を書き換える（`sendSetting`）

```kotlin
fun sendSetting(name: String, value: Int)      // Boolean / String / ByteArray の overload もある
fun requestSettingSync()                        // 全設定値の送信を要求。応答は parseResponse
fun requestSystemStatus()                       // バッテリー・装着状態・充電状態
fun sendWakeupTiltThreshold(degrees: Int)       // 見上げで起きる傾きのしきい値（0..65535）
```

**上流ドキュメントに設定キーの一覧が無い。** `sources.jar` の `CommandManager.SettingKey`
から拾った 17 個で、**0.6.0 の AAR でも変わっていない**（値は定数名と同じ。
`NOTIFICATION_CONTENT_MASK` だけ実値が `NOTIF_CONTENT_MASK`）。

観測に効くものは [グラス出力の制約](docs/team-e/glass-output.md) にまとめた。残りは未調査：
`AR_SYSTEM_MODE` / `AR_NAME` / `AR_TYPE` / `AR_VERSION` / `INSCRIPTION_MODE` / `RCP_MAC` / `RST`。

- `BRIGHTNESS_LEVEL` は **0..4、大きいほど明るい**。
  [team-f の実機調査](https://github.com/jigintern/sabera-team-f/pull/11)では0..4だけ受理され、
  設定後の再描画で見た目へ反映された。`BRIGHTNESS_AUTO = true` の間は手動値が無効になる
- それ以外の値の範囲・単位・書き換え可否は未確認。キー名が読めただけで、意味は推測
- **読み出しの経路が不明。** `requestSettingSync()` / `requestSystemStatus()` の応答は
  `parseResponse(ByteArray)` で受けるが、パース後の値がどこに出るのか公開 API から辿れない
- `要確認` **SDK チームに聞く価値が高い。** 特に明るさの公式な値域、消灯時間、
  `FEATURE_VERSION` の読み方

## 設計への含意

- 星座の特定 = **スマホのセンサー（方位・傾き・位置・時刻）から計算**
- **ピッチとロールはグラスから絶対値で取れる**（加速度計が重力を測る）。
  **方位（ヨー）だけは絶対基準が無い**ので、そこを方位合わせで埋める
  → [座標変換パイプライン](docs/team-e/coordinate-system.md)
- **スマホのコンパスでは代替できない。** スマホの磁気が示すのはスマホの方位で、
  頭とスマホの相対姿勢は未知
- グラスへの出力 = **キャンバスに星図画像＋星座名ラベル**
  → [グラス出力の制約](docs/team-e/glass-output.md)
- 「AI にお願いする」の音声入力は**グラスのマイクで成立する**（PCM16 / 16kHz を WAV に包むだけ）
- **画像送信が使えない事態に備え、テキストだけでも成立する経路を設計に残す（アプリ未実装）**
- → **星座特定・解説生成とグラス出力を分離する。** 出力層だけ差し替えられる形にしておく

## エージェントへの指示

- **RLE エンコーダを自前で書かない**（0.0.12 で SDK 側に入った）
- **Opus のデコーダを自前で書かない**（0.3.0 で SDK 側に入った）
- **キャンバス画像は枚数ではなく面積で設計する。** id は 8 枚までだが、
  置ける総量はバッファ 380,000 バイトで `width * height * 2` の合計で数える
- **転送量は面積でほぼ決まる。** 3bit RLE は真っ黒でも 32 画素で 1 バイト使うので
  `面積 / 32` が下限。**528×330 の星図は圧縮後 6〜7KB で、その 8 割は真っ黒な背景**
- **1 パケット（200 バイト）は実測 8〜9ms。** 528×330 の 1 枚で 332〜390ms
  （2026-08-20 に Pixel 9a で 12 回。それまでの見積り 20ms/本 は倍以上の過大だった）
- **「なめらかに動かす」は成立しない。** 全画面を毎回送り直す方式なので、
  視線が一定以上動いたときだけ送り直す
- **0.6.0 の直列化は「積んでよい」という意味ではない。** チャンクは混ざらなくなったが、
  送信の呼び出しは積むだけで返るので、転送が追いつかないと古いフレームが順番待ちで残る
- **`sendCanvasImage` に 576×360 を渡すと必ず `require` で落ちる**（ファームではなく SDK の制限）
- **手元のファームはキャンバス画像が動くが、`FEATURE_VERSION` の数値は未取得。**
  配布先のグラスが同じとは限らないので、**退路（196×196）の設計は残す。アプリ実装は未着手**
- **`sendNaviCourse` を方位問題の解決として扱わない**。**`yawDegrees` に返らないと実測済み**
- **`yawDegrees` をそのまま方位に使わない。** 静止中に 44°/分 流れる。
  **ジャイロの大きさで動きを判定し、動いている間だけ差分を足す**（実装済み）
- **画素数と画角を混同しない。** パネルが 576×360 と分かっても、視野の何度を占めるかは別問題
- **`SettingKey` の一覧は `sources.jar` から読み取ったもの**で、値の意味と範囲は未確認。
  **動作を断定しない**

## 文書と CI

- `docs/team-e/` は通常の Markdown で管理する。SDK API の説明は複製せず、上流の公開ドキュメントを参照する
- CI（`.github/workflows/checks.yml`）は `python3 tools/build-simulator.py --check` で
  天球シミュレータの生成物が最新か検査する
- private SDK の取得に PAT が必要なため、アプリの JVM テストと APK ビルドは開発者の手元で実行する
- `data/**` と `tools/simulator/index.html` は生成物。直接編集せず、対応する `tools/build-*.py` を使う
- GitHub Pages は公開しない。SDK ドキュメントは上流が公開している

## 決まったこと

実機で動かして確定したもの。詳細は [docs/team-e/index.md](docs/team-e/index.md)。

| 論点 | 決定 |
|---|---|
| **星図の出し先と大きさ** | キャンバスに **528×330**。等級は「明るさ ＋ 点の大きさ」で表し、**点は丸く打つ**（四角だと 17×17 の塊に見える） |
| **星座名** | 画像に焼かず**キャンバスのテキストで重ねる**。矩形は文字数から取る。重なった名前は視野中心に近いほうを残す。**画像を送ってから名前を送る** |
| **描き直しの条件** | **首が止まってから送る**（0.4 秒静止 ＋ 前の絵から 6° 以上）。動きに追従させると点滅にしかならない |
| **姿勢と方位** | 仰角と方位の相対値はグラス、絶対方位の基準はスマホ。**グラスに十字を出してスマホのマーカーと重ねてもらう**。残るのは地磁気そのものの誤差 |
| **観測地** | スマホの測位（融合 → GPS → 基地局）。取れないときだけ手入力 |
| **パーミッション** | `ACCESS_FINE_LOCATION` ＋ `ACCESS_COARSE_LOCATION` を起動時にまとめて聞く |
| **星表** | 自前計算。データはリポジトリ同梱の `data/`（d3-celestial / XHIP。帰属は `NOTICE`） |
| **星座判定** | 視線を B1875.0 へ戻し、**Roman (1987) の IAU 境界表 357 行**で中心の星座を一意に決める |
| **スマホ側 UI** | ホーム → 接続 → 方位合わせ → 星図の 4 画面。星図の画面が本体で、上部バーの「衛星モード」で星座 ⇄ 人工衛星、「設定」で明るさ・音・調整・ログを畳む |
| **AI 解説** | `SINGLE_TAP` で開始／停止。**星図画像 ＋ 端末が計算した星座名 ＋ 位置・方位・仰角・日時**を OpenAI へ送り、SSEで届いた文を逐次表示し、句点単位で読み上げる |
| **読み上げの声** | **`gpt-4o-mini-tts` / `alloy`**（聴き比べで選定）。`instructions` は**「演じずに淡々と」だけ**。生 PCM は奇数チャンクを整列し、**1文を受信してから静的再生**。現在文の再生中に次文を先読みする。**圏外・キー未設定・失敗時は端末の `TextToSpeech` に落ちる** |
| **音の鳴らし先** | **スマホのスピーカー**（グラスにスピーカーが無いので選択の余地がない） |
| **BGM** | **解説していない間も鳴らす。** 曲は**太陽高度**で薄暮／夜を切り替える（時計ではない）。**解説中は 28% まで自動で絞る**。音量は設定パネルのつまみで端末に記憶。出処 = incompetech.com（CC BY 4.0） |
| **解説文の表示先** | グラスには出さない（190 バイトに入らず、星図に重ねると星が読めない）。**スマホ画面と音声**へ |
| **API キー** | リポジトリ直下の `.env`（`.gitignore` 済み）。**無くてもビルドは通す** |
| **人工衛星モード** | 星座と**排他**。`DOUBLE_TAP` で切り替え。SGP4/SDP4 は自前移植。**衛星モードには星を描かない** |

AI 解説で外せない判断：

- **画像だけにしない。** 緑 8 階調の点描を読み違えても検知できないが、星座名は確定値なので
  併せて渡せば同定を間違えようがない。**画像は 3bit に落としてから送る**
  （8bit のままだと実機では見えない星まで写り、**見えていないものの解説**が返る）
- **最初の一言「〇〇座ですね」は LLM を待たずに喋る。** 生成の 1〜3 秒はこれで埋まる
- **本文はSSEで受け取り、句点まで届いた文から読み上げる。** 通信断では完了した文を残し、
  不完全な末尾だけ捨てる。受信後に再試行すると二重になるので、再試行は受信前の1回だけ
- **タップして無反応が一番よくない。** 未キャリブレーション・地面向き・圏外・キー未設定の
  どの経路でも必ず何か喋る
- **声の質は雰囲気の問題だが、黙るのは機能の欠落。** AI 音声が使えないときは
  必ず端末の `TextToSpeech` に落とす。**棒読みでも喋るほうが上**
- **「〇〇座ですね」はタップより先に合成しておく**（星図を送った時点で星座は分かっている）。
  短い定型文だけ `cacheDir` に残すので、2 回目からは通信も要らない
- **モデルの記憶だけで距離・神話を補わせない。** IAU境界の星座名、視野内と計算した固有名星、
  **視野内の月・惑星**、等級・角距離・校正誤差を根拠として渡し、
  視野外の星名・視野に無い天体・未提供の外部知識を含む文は読み上げない
  （「月」は日付にも出る字なので、そこで誤爆しない照合にしてある）
- **プロンプトの箇条書きを増やすほど 1 つずつは薄まる**（11 個並べた版は末尾 2 つが無視された）。
  **守らせたい注意は依頼文の末尾に置く**
- **推論は切る（`OPENAI_REASONING_EFFORT=none`）。** 推論トークンは出力と枠を共用するので、
  切らないと**本文が空で返る**。従来プロンプト（4〜5 文・250 文字）で実測すると
  **4 回中 4 回が空**（`finish_reason: length`）。切ると 4/4 成功して 2〜3 秒で返る。
  `max_completion_tokens` も 400 → 2000 に広げてある。
  **値が空なら送らない**（推論を持たないモデルに送ると 400 で弾かれる）
- **失敗の理由を取り違えて喋らない。** 「本文が空」「API エラー」「圏外」を別の文にする。
  全部「通信ができない」にしていたときは、**通信できているのに圏外だと思わせていた**うえ、
  モデル名の打ち間違いもキー切れも同じ文言に化けて原因が追えなかった

## 未決定事項

決まったらこのファイルを更新する。**エージェントは勝手に埋めない。**

### ジェスチャーと入力

- **3 枠（`SINGLE_TAP` / `DOUBLE_TAP` / `HOLD`）がすべて埋まっている。**
  「もっと詳しく」は行き先を失った。どちらかをスマホ側のボタンに追い出すことになる
- 追加質問の入力経路 — グラスのマイクかスマホ側か
  （最初の一言は `SINGLE_TAP` そのものが質問なので、音声入力は要らない）
- 発話の区切りをどう決めるか（`micAudio` は流れ続ける）
- **マイクと星図が同じ BLE を食い合う。** 音声を流しながら画像を送れるか

### 星座と衛星

- ヒステリシスの内側マージンと保持時間
- **天体アライメントに使う基準天体**（月・明るい星）と、その選び方
- 衛星のパス予報（いま空にいない機体が次に来るのはいつか）
- **星が無い空で方角の手がかりが足りるか**（衛星モードは星を描かない）

### 出力とファーム

- **`FEATURE_VERSION` が読めないので、退路への切り替えをどう判定するか**
  （「送って反応を見る」か「整備画面で選ばせる」か）
- **0.6.0 のキャンバス画像 8 枚を使うか。** 星図を分割して動いた部分だけ送り直せるが、
  バッファは面積の合計で数えるので総量は減らない
- **首を振っている間に漏れるドリフト**（動いている時間 × 0.74°/秒）を詰めるか。
  2 秒の首振りで 1.5° なので、いまは放置している
- **消灯時間・通知の抑止**をアプリが書き換えるか、ユーザーに任せるか。
  明るさは自動調整を使わず、設定画面の手動5段階スライダーから送り、直前の星図を再送する。
  キャンバスでの反映はteam-e実機で確認待ち

### アプリ

- **BGM と読み上げのつり合いを実機で詰める。** 既定は BGM 75%・解説 100%・絞り 28% だが、
  **合わせたのは Mac のスピーカー**。本番はスマホのスピーカーで屋外なので、
  **低音の多い曲（Fluidscape）は印象が変わる可能性が高い**
- **新しいAI音声経路を実機で詰める。** PCM は1文を全受信してから再生し、再生中に次文を先読みする。
  1バイトずれとアンダーランを避ける変更後は**夜の屋外で試していない**。ログの受信時間・PCM長・
  再生時間・underrunCountを確認する
- **観測地の測位を続けるか**（いまは画面に入ったとき 1 回だけ）
- **本番配布するなら OpenAI をバックエンド経由にする必要がある**（いまは APK にキーが埋まる）

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
- 秘密情報（PAT、API キー）をコミットしない
- ライセンス — サンプル・ラッパーコードは Apache License 2.0。
  **SDK 本体（`jp.jig.sabera.app.sdk:*`）は対象外**で別途 SDK 利用規約
