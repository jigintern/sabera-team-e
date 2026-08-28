# SDK とハードの制約

- SDK（`jp.jig.sabera.app.sdk:sabera-app-core`）で何ができて何ができないか、実機のハードに何が無いか
- 数値の詳細 → [グラス出力の制約](11_glass-output.md)
- 禁止事項 → [AGENTS.md](../../AGENTS.md)
- アプリの作り → [00_index.md](00_index.md)

## 上流 SDK との同期

- 上流 = [jig-SABERA/sabera-sdk](https://github.com/jig-SABERA/sabera-sdk) /
  公開ドキュメント = <https://jig-sabera.github.io/sabera-sdk/>
- **`90339c0`（SDK 0.7.3）時点まで取り込み済み**
- どのメソッドがどの版から使えるかは上流リポジトリの `docs/api-history.md`
- **iOS は追わない。** `Package.swift` は上流でも SDK 0.0.10 のままで、team-e では撤去した

### SDK には `sources.jar` が付いている（逆アセンブルより先にこれを読む）

- バイナリ配布だが中身は読める
- Gradle Module Metadata が `releaseSourcesElements-published` を正式なバリアントとして宣言している
- **`read:packages` の PAT があれば誰でも取れる**（0.0.10 以降のどの版にもある）

```bash
cd ~/.gradle/caches/modules-2/files-2.1/jp.jig.sabera.app.sdk/sabera-app-core-android/0.7.3
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

1. `samples/kmp/gradle/libs.versions.toml` の `saberaSdk` を更新する
2. 上流の公開ドキュメント、`docs/api-history.md`、取得した `sources.jar` で破壊的変更を確認する
3. 必要な変更だけを team-e のアプリへ手動で反映する
4. `:app:testDebugUnitTest` と `:app:assembleDebug` を実行する
5. BLE やグラス表示に関わる変更は実機で確認し、未確認ならその旨を文書に残す

- 上流の `docs/` / `samples/` / `scripts/` をディレクトリ単位で checkout しない
- このリポジトリは team-e アプリに必要なものだけを持ち、上流のサンプルとドキュメントサイトは追跡しない

## SDK のアーキテクチャ

- 依存 = `jp.jig.sabera.app.sdk:sabera-app-core`（バイナリ配布）
- **接続のライフサイクルは一本道で、途中を飛ばせない**

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

### 設計上の約束ごと

- `showAutomaticSelectionDialog()` の第1引数は **Activity**。Application Context だとダイアログが出ない
- `GlassClient` は切断手段を持たない（`GlassClientInternal` に隔離）。
  **UI 層が接続状態を抱えないための制約なので回避しない**
- **`disconnect()` は CDM の登録を「全部」消す。** 1 台だけ残す手段は無い
  （デバイス指定版の `unregisterOthers` は private）。`lastDeviceId` も null になるので、
  **次に開くと端末選択からやり直し**になる。繋ぎ先を選び直す唯一の手段でもある（#129）
- **登録の削除は SDK 側の `try` の外にある。** 切断そのものの失敗は握り潰されるが、
  **`BLUETOOTH_CONNECT` が無いと `SecurityException` が呼び出し元まで飛ぶ**
- `disconnectAndClearBond()` は bond まで消すが、**中身は隠し API のリフレクション**
  （`removeBond`）。Android のバージョンで壊れうるので使わない
- `gestureEvents` は `SharedFlow`。**購読開始前のジェスチャーは受け取れない**
- `setDevicePersistence` を省くとインメモリになり、プロセスをまたぐと接続先を忘れる
- コンテンツはページを開いてから送る。**例外 = 分割レイアウトと自由配置キャンバス**
  （送るだけで画面が切り替わる）
- **AAR の manifest は空**（`<uses-sdk>` だけ）。`BleCompanionDeviceService` の宣言は
  **アプリ側の manifest が持っている**。マージで入ってくると思って消さない
- **AAR は難読化されている。** ソース jar に見えるクラスが AAR にあるとは限らない。
  名前が残っているのは `BleCompanionDeviceService` と `BleDeviceSelector` だけで、
  **`BluetoothStateReceiver` は潰されていて manifest から名指しできない**（[落とし穴](72_pitfalls.md)）。
  そのため **Bluetooth を ON に戻したときの自動再接続（`connectToLastDevice`）は走らない**

## グラスに何を出せるか

- ここは要点だけ。数値と制約の詳細 → **[グラス出力の制約](11_glass-output.md)**

| 出し先 | API | 上限 | ファーム要件 |
|---|---|---|---|
| **自由配置キャンバス（本命）** | `sendCanvasImage` / `sendCanvasElements` | **画像 id 0..7・バッファ 380,000B / テキスト 8 要素・190B** | **2.2.0**（画像）/ 2.1.0（テキスト） |
| 分割レイアウト | `sendLayout` / `sendLayoutTexts` | テキスト合計 190B・4 分割 | 2.0.0 |
| 画像表示ページ（退路） | `enterImageDisplayPage` ＋ `sendImage` | **196×196** | なし |
| 汎用テキストページ | `enterEmptyScreenPage` ＋ `sendEmptyScreenContent` | 200B 超は分割して送られる | なし |
| ナビページ | `enterNavigationPage` / `sendNavi` / `sendNaviLargeImage` | 地図 255 / 上流サンプルは 240×240 | なし |

- **team-e が使うのはキャンバスだけ。** 星図を `sendCanvasImage` で置き、星座名を `sendCanvasElements` のテキストで手前に重ねる
- **画像表示ページ（196×196、星図だけ）の退路は設計済みだが、アプリには未実装**

### 絶対に外せない数値

- **パネルは 576×360。画像 1 枚の実用最大は 528×330**（576×360 は画素だけで 414,720 で入らない）
- **渡すのは 1 画素 1 バイトのグレースケール。** 量子化（3bit）と RLE 圧縮は **SDK が行う**
- **映るのは緑の 8 階調。黒は透明**（波導ディスプレイ）
- **転送中は前の絵が消える。** 首が止まってから送る（0.18 秒静止 ＋ 6° 以上のずれ）
- **ナビ表示中はキャンバス画像が使えない**（バッファを共有している）
- **リモコンの戻る操作でキャンバスは閉じられる**が、**閉じられたことに気づけない**
  （0.5.0 でリスナーが撤去された）。いつでも送り直せる作りにする

### 版ごとの動き

- **0.7.0 で充電状態の `charging` が増えた。** 接続時に SDK が状態を要求するため、
  アプリは購読するだけでよい。ただし **0.7.0 の AAR は Opus のネイティブライブラリが欠け、
  `startMicStreaming` で落ちる。0.7.3 で直ったため、0.7.0 は使わない**
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

- `gestureEvents` で `SINGLE_TAP` / `DOUBLE_TAP` / `HOLD` が届く
- **3 枠すべて埋まっている**（[ジェスチャーと首の操作](31_gestures.md)）
- SDK 0.7.3 の `GestureType` はこの3値だけで、**押下開始・押下中・指を離したイベントが無い**
- `HOLD` は確定後に1回届くだけなので、アプリは**実際に触れている時間を測れない**
- したがって「ホールド中だけ録音し、離したら終了」は公開 API では実装できない。
  現在は **`HOLD` で開始し、もう一度の `HOLD` で送信**する。押し忘れは30秒で安全終了する
- **リモコンのイベントリスナーは 0.5.0 で撤去された。** `RemoteControlListener`（`onPrev` / `onNext` / `onEsc`）ごと 0.7.3 の AAR にも無い
- **リモコン操作をアプリで拾う手段は無い**

### 逃げ道 — 生の電文

- 口は `GlassClient.sendCommand(ByteArray)` / `sendCommandList` / `sendText`
- **電文の仕様は `sources.jar` の `PacketCommandUtils` で読める**
- **読めることと勝手に投げてよいことは別。使う前に SDK チームに聞く**（公開 API に無い電文はファーム側の想定外で、壊し方が分からない）
- `cancelPendingPackets()` は名前のとおりなら**古い星図フレームを積ませずに捨てられる**。`要確認` 実際の挙動

## 取れないもの（0.7.3 時点）

| | 状況 |
|---|---|
| **カメラ映像** | グラスから画像は取れない |
| **絶対方位** | 6DoF に磁力計は無く、ヨーは起動基準の相対値でドリフトする |
| **音声出力** | **グラスから音は出せない。API が無いのではなく、ハードにスピーカーが無い** |

- 音声出力は **4 層すべてで道が無いことを確認済み**（2026-08-20）

| 層 | 確認したこと |
|---|---|
| コマンド表 | `PacketCommandUtils.CMDKey` の全 opcode に**音声出力の命令が無い**。`MIC_COMMAND 0x09` は入力専用 |
| BLE サービス | `AUDIO_SERVICE_UUID` にあるのは `AUDIO_NOTIFY_UUID` だけ。**WRITE 特性が無い** |
| 同梱ネイティブ | `jni/*/libopus.so` と `libopusdecoder.so` の**デコーダのみ**。エンコーダが無い |
| 実機 | 公開するのは**バッテリーサービスだけ**。A2DP / HFP / LE Audio のどれにも現れず、デバイスクラスは `0x001F00`（uncategorized＝音響機器ではない） |

- **実機にスピーカーが無いことは team-e が現物で確認した。** 鳴らす先が存在しない
- **音はスマホから鳴らす。** 夜の屋外でスピーカーなら同伴者にも聞こえる
- スマホに繋いだ Bluetooth イヤホンへ回す案は**見送った**（体験は近いが機材が増える）
- 方位を外から入れる口は `sendNaviCourse(courseDegrees)` にある
- ただし**補正後の方位は `imuData` の `yawDegrees` に返らない**（2026-08-20 に実機で確認）
- `course` を 2 秒おきに 5 回送っても、ヨーは**送った値から離れる方向**へ等速で動いた ＝ ただのドリフト
- ナビページに入って `sendNaviStatus(START)` にしても同じ
- **`enterNavigationPage()` でヨーの原点がリセットされる**（3 秒で 64.7° 飛んだ）。
  上流ドキュメントに無い挙動なので、ナビページに入るときは注意
- そもそも**送る値を作る工程がキャリブレーションそのもの**なので、
  返っていたとしても答えにはならなかった
- **ヨードリフト率は約 44°/分（0.73°/秒）と実測し、対策を入れた**
- `yawDegrees` をそのまま使うと**星座の同定（±20°）が 27 秒で破れる**
- **報告される `gyroZDps` のバイアスを引いても直らない**（平均 0.058°/秒 で 12.7 倍ちがう）
- **機体座標は `gyroXDps` が鉛直軸**、符号はヨーと同じ（90° を 7 回まわして確認）。
  ただし**自前積分は 10Hz の取りこぼしで回転 90° あたり 5〜15° 足りない**
- **採った方法 = ジャイロの大きさが 2°/秒 を超えている間だけ Δ`yawDegrees` を足し、
  そこから実測のドリフト率も引く。** 静止中は何も足さない。
  ドリフト率は**静止 5 秒ごとにアプリが自分で測る**（実測 −0.735〜−0.744°/秒）
- **実測で 44.5°/分 → 0.0°/分**（静止 4 分＋90° の回転 8 回）。
  ドリフトによる星図の再送（8.5 秒ごと）も止まった
- **ボトルネックは地磁気（±5〜15°）に移った。** 星座の同定（±20°）は成立するので、
  **ここで止める**。星図をぴったり重ねる（±2〜3°）ための天体アライメントは
  **UX を優先して撤去した**（下の「精度と UX」）
- 詳細 → [座標変換パイプライン ④](20_coordinate-system.md)

## グラスの設定を書き換える（`sendSetting`）

```kotlin
fun sendSetting(name: String, value: Int)      // Boolean / String / ByteArray の overload もある
fun requestSettingSync()                        // 全設定値の送信を要求。応答は parseResponse
fun requestSystemStatus()                       // バッテリー・装着状態・充電状態
fun sendWakeupTiltThreshold(degrees: Int)       // 見上げで起きる傾きのしきい値（0..65535）
```

- **上流ドキュメントに設定キーの一覧が無い**
- `sources.jar` の `CommandManager.SettingKey` から拾った 17 個で、**0.6.0 の AAR でも変わっていない**（値は定数名と同じ。`NOTIFICATION_CONTENT_MASK` だけ実値が `NOTIF_CONTENT_MASK`）
- 観測に効くもの → [グラス出力の制約](11_glass-output.md)
- 未調査 — `AR_SYSTEM_MODE` / `AR_NAME` / `AR_TYPE` / `AR_VERSION` / `INSCRIPTION_MODE` / `RCP_MAC` / `RST`
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
  → [座標変換パイプライン](20_coordinate-system.md)
- **スマホのコンパスでは代替できない。** スマホの磁気が示すのはスマホの方位で、
  頭とスマホの相対姿勢は未知
- グラスへの出力 = **キャンバスに星図画像＋星座名ラベル**
  → [グラス出力の制約](11_glass-output.md)
- 「AI にお願いする」の音声入力は**グラスのマイクで成立する**（PCM16 / 16kHz を WAV に包むだけ）
- **画像送信が使えない事態に備え、テキストだけでも成立する経路を設計に残す（アプリ未実装）**
- → **星座特定・解説生成とグラス出力を分離する。** 出力層だけ差し替えられる形にしておく
