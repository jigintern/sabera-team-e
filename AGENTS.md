# AGENTS.md

このリポジトリで作業する AI エージェント（Claude Code / Codex など）向けの共通ガイド。
人間向けの手順は [CONTRIBUTING.md](CONTRIBUTING.md)、仕様は [docs/team-e/](docs/team-e/)。

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
| `samples/kmp/snippets/` | ドキュメント用コード例。**アプリではない**（壊すと CI が落ちる） |
| `docs/team-e/` | **team-e の仕様書** |
| `docs/` の他 | SDK ドキュメントサイト（Jekyll / just-the-docs）。公開せず手元で読む |
| `docs/api/` | SDK API リファレンス。雛形は `scripts/gen-api-docs.py` が生成 |
| `data/` | 同梱データ（星表・星座線・TLE）。**すべて生成物** |
| `tools/` | 同梱データの生成スクリプトと天球シミュレータ |
| `scripts/` | ドキュメント生成・同期スクリプト（Python 3） |

## 開発コマンド

```bash
cd samples/kmp
./gradlew :app:installDebug              # 実機にインストール
./gradlew :app:testDebugUnitTest         # JVM テスト（座標変換・SGP4・AI 周り）
./gradlew :snippets:compileDebugKotlin   # ドキュメントのコード例をコンパイル
./gradlew :snippets:ktlintCheck          # コード例の lint（:app は対象外）
```

```bash
cd docs && bundle exec jekyll serve      # http://127.0.0.1:4000/
python3 scripts/sync-snippets.py         # Kotlin のコード例を docs/ へ写す
python3 scripts/sync-snippets.py --check # 差分を検査（CI と同じ）
python3 scripts/gen-api-docs.py          # API ページの雛形を生成（既存本文は保持）
python3 tools/build-star-catalog.py      # data/ の星表を作り直す
python3 tools/build-satellites.py        # data/ の TLE を取り直す
```

### 前提

- JDK 17 / Gradle 8.10.2（wrapper 同梱）/ Kotlin 2.3.10 / AGP 8.7.0
- Android `minSdk 31` / `compileSdk 36` / `targetSdk 36`
- **BLE 実機が必須。エミュレータでは動作確認できない**
- SDK は private な GitHub Packages 配布。**`read:packages` の PAT が無いとビルドが落ちる**
- `.editorconfig`（4スペース / 120桁 / intellij_idea）は全 Kotlin に効く

## 上流 SDK との同期

- 上流 = [jig-SABERA/sabera-sdk](https://github.com/jig-SABERA/sabera-sdk) /
  公開ドキュメント = <https://jig-sabera.github.io/sabera-sdk/>
- **`f3db995`（SDK 0.6.0）時点まで取り込み済み**
- どのメソッドがどの版から使えるかは上流の `docs/api-history.md`
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

### 取り込み直す手順

```bash
git remote add upstream https://github.com/jig-SABERA/sabera-sdk   # 初回のみ
git remote set-url --push upstream no_push                          # 誤 push 防止
git fetch upstream
git checkout upstream/main -- docs samples scripts

# 上流が消したファイルは checkout では消えない。残ったものを洗い出す
comm -23 <(git ls-files docs samples scripts | sort) \
         <(git ls-tree -r --name-only upstream/main -- docs samples scripts | sort)
# ↑ team-e 所有のファイルが混ざっていないか見てから git rm

# 上流は docs/_site を追跡しているが team-e では追跡しない
git rm -r --cached docs/_site && rm -rf docs/_site
```

**checkout の対象に入れてはいけないもの**（上書きすると team-e の変更が消える）：

| パス | 理由 |
|---|---|
| `README.md` / `AGENTS.md` / `CLAUDE.md` / `CONTRIBUTING.md` | team-e 独自ファイル |
| `docs/team-e/` | team-e の仕様書 |
| `.github/workflows/docs.yml` | team-e 側で CI 方針を変えている（後述） |
| `NOTICE` | team-e が星表データ（d3-celestial / XHIP）の帰属を足している |
| `.gitignore` | `docs/_site/` と `tools/.cache/` の除外は team-e 側の追加 |

- `LICENSE` は上流が変えたときだけ個別に取り込む
- **上の `comm` は毎回走らせる。** `git checkout <tree> -- <path>` は上流で削除された
  ファイルを消さないので、0.0.14 で撤去された `enter-ai-page.md` が居残った実例がある
- **team-e が撤去したものが復活する。** 使わない上流サンプル画面（`CommandScreen` など 12 ファイル）と
  `samples/flutter/` は削除済みなので、`comm` の逆向き（上流にあって手元に無い）も確認する。
  **`samples/flutter/` は上流で今も開発が続いている**（jig-SABERA/sabera-sdk#20）ので、
  取り込むたびに戻ってくる
- **`Package.swift` は checkout の対象外なので復活しない**（`docs samples scripts` に入っていない）。
  **広い範囲を checkout するときだけ気をつける**

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
`sendCanvasElements` のテキストで手前に重ねる。**退路は画像表示ページ（196×196、星図だけ）。**

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

方位については、**外から値を入れる口は `sendNaviCourse(courseDegrees)` にある**。
上流の説明は「端末の GPS 進行方向を送る。グラスは磁力計を持たないので、
この値をジャイロのドリフト補正に使う」。**ファームにヨーの外部補正が入っている証拠。**
ただし**これで方位キャリブレーションが要らなくなるわけではない**：

- **GPS 進行方向は「歩いている向き」で「顔の向き」ではない。** 星を見るときは立ち止まる
- **送る値をどこから得るかという問題がそのまま残る。** `sendNaviCourse` は**答えの置き場**
- 「案内中に送る」とあり、**ナビ状態でないと効かない可能性がある**
- `要確認` **最重要 —** 補正後の方位が `imuData` の `yawDegrees` に返るか

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

- **値の範囲・単位・書き換え可否はすべて未確認。** キー名が読めただけで、意味は推測
- **読み出しの経路が不明。** `requestSettingSync()` / `requestSystemStatus()` の応答は
  `parseResponse(ByteArray)` で受けるが、パース後の値がどこに出るのか公開 API から辿れない
- `要確認` **SDK チームに聞く価値が高い。** 特に明るさ・消灯時間・`FEATURE_VERSION` の読み方

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
- **画像送信が使えない事態に備え、テキストだけでも成立する経路を残す**
- → **星座特定・解説生成とグラス出力を分離する。** 出力層だけ差し替えられる形にしておく

## エージェントへの指示

- **RLE エンコーダを自前で書かない**（0.0.12 で SDK 側に入った）
- **Opus のデコーダを自前で書かない**（0.3.0 で SDK 側に入った）
- **キャンバス画像は枚数ではなく面積で設計する。** id は 8 枚までだが、
  置ける総量はバッファ 380,000 バイトで `width * height * 2` の合計で数える
- **転送量は面積でほぼ決まる。** 3bit RLE は真っ黒でも 32 画素で 1 バイト使うので
  `面積 / 32` が下限。**528×330 の星図は圧縮後 6〜7KB で、その 8 割は真っ黒な背景**
- **「なめらかに動かす」は成立しない。** 全画面を毎回送り直す方式なので、
  視線が一定以上動いたときだけ送り直す
- **0.6.0 の直列化は「積んでよい」という意味ではない。** チャンクは混ざらなくなったが、
  送信の呼び出しは積むだけで返るので、転送が追いつかないと古いフレームが順番待ちで残る
- **`sendCanvasImage` に 576×360 を渡すと必ず `require` で落ちる**（ファームではなく SDK の制限）
- **手元のファームはキャンバス画像が動くが、`FEATURE_VERSION` の数値は未取得。**
  配布先のグラスが同じとは限らないので、**退路（196×196）は残す**
- **`sendNaviCourse` を方位問題の解決として扱わない**。値を作る工程は残る
- **画素数と画角を混同しない。** パネルが 576×360 と分かっても、視野の何度を占めるかは別問題
- **`SettingKey` の一覧は `sources.jar` から読み取ったもの**で、値の意味と範囲は未確認。
  **動作を断定しない**

## ドキュメントサイトの仕組みと CI の落とし穴

CI（`.github/workflows/docs.yml`）が回すもの：

- `python3 scripts/sync-snippets.py --check` — Kotlin のコード例と `docs/` の差分（全 PR と push）
- `bundle exec jekyll build` — サイトがビルドできるか（PR のみ）

**コード例のコンパイルと ktlint は CI から外している**（SDK 取得に PAT が要るため）。
team-e は全員ローカルに PAT を持っているので、検証は手元で行う：

```bash
cd samples/kmp && ./gradlew :snippets:compileDebugKotlin :snippets:ktlintCheck
```

よくある落とし方：

| やったこと | 結果 |
|---|---|
| `samples/kmp/snippets/**` を直して `sync-snippets.py` を忘れる | **CI が落ちる** |
| `docs/api/**` のコードブロックを手で書き換える | **CI が落ちる**（出処は Kotlin 側） |
| `:snippets` をアプリコードの置き場と勘違いして壊す | **CI では気づけない。手元で Gradle を回す** |

編集してはいけない生成物：

- `docs/_data/api_links.yml` — `scripts/gen-api-docs.py` が `SPEC` から生成
- `docs/_site/` — Jekyll のビルド成果物（`.gitignore` 済み）
- `data/**` — `tools/build-*.py` の生成物

その他：

- `docs/**` の Markdown では公開 API 名をバッククォートで囲むだけで自動リンクされる
  （`docs/_plugins/api_autolink.rb`）。`[...](...)` は書かない
- **GitHub Pages への公開はしていない**（上流が <https://jig-sabera.github.io/sabera-sdk/>
  で公開しており、team-e が二重に出す必要がないため）

## 決まったこと

実機で動かして確定したもの。詳細は [docs/team-e/](docs/team-e/)。

| 論点 | 決定 |
|---|---|
| **星図の出し先と大きさ** | キャンバスに **528×330**。等級は「明るさ ＋ 点の大きさ」で表し、**点は丸く打つ**（四角だと 17×17 の塊に見える） |
| **星座名** | 画像に焼かず**キャンバスのテキストで重ねる**。矩形は文字数から取る。重なった名前は視野中心に近いほうを残す。**画像を送ってから名前を送る** |
| **描き直しの条件** | **首が止まってから送る**（0.4 秒静止 ＋ 前の絵から 6° 以上）。動きに追従させると点滅にしかならない |
| **姿勢と方位** | 仰角と方位の相対値はグラス、絶対方位の基準はスマホ。**グラスに十字を出してスマホのマーカーと重ねてもらう**。残るのは地磁気そのものの誤差 |
| **観測地** | スマホの測位（融合 → GPS → 基地局）。取れないときだけ手入力 |
| **パーミッション** | `ACCESS_FINE_LOCATION` ＋ `ACCESS_COARSE_LOCATION` を起動時にまとめて聞く |
| **星表** | 自前計算。データはリポジトリ同梱の `data/`（d3-celestial / XHIP。帰属は `NOTICE`） |
| **スマホ側 UI** | ホーム → 接続 → 方位合わせ → 星図の 4 画面。星図の画面が本体で、上部バーの「衛星モード」で星座 ⇄ 人工衛星、「設定」で調整とログを畳む |
| **AI 解説** | `SINGLE_TAP` で開始／停止。**星図画像 ＋ 端末が計算した星座名 ＋ 位置・方位・仰角・日時**を OpenAI へ送り、返った文を `TextToSpeech` で読み上げる |
| **音の鳴らし先** | **スマホのスピーカー**（グラスにスピーカーが無いので選択の余地がない） |
| **解説文の表示先** | グラスには出さない（190 バイトに入らず、星図に重ねると星が読めない）。**スマホ画面と音声**へ |
| **API キー** | リポジトリ直下の `.env`（`.gitignore` 済み）。**無くてもビルドは通す** |
| **人工衛星モード** | 星座と**排他**。`DOUBLE_TAP` で切り替え。SGP4/SDP4 は自前移植。**衛星モードには星を描かない** |

AI 解説で外せない判断：

- **画像だけにしない。** 緑 8 階調の点描を読み違えても検知できないが、星座名は確定値なので
  併せて渡せば同定を間違えようがない。**画像は 3bit に落としてから送る**
  （8bit のままだと実機では見えない星まで写り、**見えていないものの解説**が返る）
- **最初の一言「〇〇座ですね」は LLM を待たずに喋る。** 生成の 1〜3 秒はこれで埋まる
- **タップして無反応が一番よくない。** 未キャリブレーション・地面向き・圏外・キー未設定の
  どの経路でも必ず何か喋る

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

- **星座の判定を IAU 境界（Roman 1987）に差し替えるか。**
  いまは `constellationsNear()` が星座線までの角距離で近似している
- ヒステリシスの内側マージンと保持時間
- **天体アライメントに使う基準天体**（月・明るい星）と、その選び方
- 衛星のパス予報（いま空にいない機体が次に来るのはいつか）
- **星が無い空で方角の手がかりが足りるか**（衛星モードは星を描かない）

### 出力とファーム

- **`FEATURE_VERSION` が読めないので、退路への切り替えをどう判定するか**
  （「送って反応を見る」か「整備画面で選ばせる」か）
- **1 パケットあたりの転送時間。** 200 バイトずつ送るのは確かだが実時間を測る手段が無い
  （いまは 20ms を初期値に画面のつまみで詰めている）
- **0.6.0 のキャンバス画像 8 枚を使うか。** 星図を分割して動いた部分だけ送り直せるが、
  バッファは面積の合計で数えるので総量は減らない
- `sendNaviCourse` でファームにヨー補正を任せられるか（`imuData` に返るかが未確認）
- **観測中のグラス設定**（明るさ・消灯時間・通知の抑止）をアプリが書き換えるか、ユーザーに任せるか

### アプリ

- **解説をストリーミングに変えるか。** いまは一括で、待ちは「〇〇座ですね」で埋めている
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
