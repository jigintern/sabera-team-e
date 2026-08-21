# AGENTS.md

このリポジトリで作業する AI エージェント（Claude Code / Codex など）向けの共通ガイド。
**ここは禁止事項と規約だけ。** 仕様は [docs/team-e/index.md](docs/team-e/index.md)、
人間向けの手順は [CONTRIBUTING.md](CONTRIBUTING.md)。

| 知りたいこと | 読む先 |
|---|---|
| **何ができないか**（SDK・ハードの制約） | [docs/team-e/sdk.md](docs/team-e/sdk.md) |
| 何をどれだけ出せるか（数値） | [docs/team-e/glass-output.md](docs/team-e/glass-output.md) |
| 座標変換・方位合わせ・星座判定 | [docs/team-e/coordinate-system.md](docs/team-e/coordinate-system.md) |
| 画面遷移・ジェスチャー・AI 解説 | [docs/team-e/app-flow.md](docs/team-e/app-flow.md) |
| **決まったこと / 未決定事項** | [docs/team-e/index.md](docs/team-e/index.md) |
| 実機で何を確かめるか | [docs/team-e/field-check.md](docs/team-e/field-check.md) |

## プロジェクト

- **SABERA** = スマートグラス。このリポジトリ = SABERA App SDK で **team-e** がアプリを作る場所
- 目標 = **空にかざしたグラスの視界と星座を重ね、AI に頼むと今見えている星座を解説してくれるアプリ**
- 続けて**人工衛星モード**（星座と切り替えて、いま通っている衛星を星図と同じ座標で出す。
  **星座とは排他で、衛星モードには星を描かない**）

**現在のフェーズ = 実装中。** `samples/kmp/app` がアプリ本体で、実機で動いているものと
テストだけのものは [index.md](docs/team-e/index.md) の表で分けている。

## 原則

- **頼まれていないアプリ機能を先回りして実装しない**
- **未決定事項を勝手に埋めない。** [index.md](docs/team-e/index.md) に追記するか質問する
- **実機（BLE）でしか確かめられないことが多い。確かめていないことを「動く」と書かない**
- **精度と UX がぶつかったら UX を採る。** 使う人は天文の初心者で、要るのは
  「どのあたりに何があるか」。星座の同定（±20°）で足りる。
  **精度はユーザーに何もさせない範囲で最大化する**（ドリフト補正・IAU 境界判定・静止区間の平均）

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
| `starmap/Ephemeris.kt` | 月と 8 惑星の位置計算。Android 非依存。**天体の位置はここだけ** |
| `starmap/MagneticQuality.kt` | 磁気の歪みの検証。OS の信頼度を信じない |

## 開発コマンド

```bash
cd samples/kmp
./gradlew :app:installDebug              # 実機にインストール
./gradlew :app:testDebugUnitTest         # JVM テスト（座標変換・SGP4・AI 周り）
./gradlew :app:assembleDebug             # Debug APK を生成
```

```bash
tools/pull-session-log.sh                # 実機の観測ログを取り出して要約する（--logcat / --clear）
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

## エージェントへの指示

- **`optString` を JSON の null に使わない。** Android の org.json は**文字列 "null" を返す**が、
  JVM テストで使う本物の org.json は空を返す。**この取り違えはテストで絶対に落ちず、実機だけで壊れる**
  （実際に OpenAI の `refusal: null` を拒否と読み、本文を毎回捨てていた）。`isNull()` で見る
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
- **プロンプトの箇条書きを増やすほど 1 つずつは薄まる**（11 個並べた版は末尾 2 つが無視された）。
  **守らせたい注意は依頼文の末尾に置く**
- **`SettingKey` の一覧は `sources.jar` から読み取ったもの**で、値の意味と範囲は未確認。
  **動作を断定しない**

### 実機で踏んだ落とし穴

- **`async` の失敗は `await` で受け取っても親スコープを巻き込む。** 画面の
  `rememberCoroutineScope()` から素の子として走らせると、**TTS が 1 回失敗しただけで
  6DoF の購読とログが道連れで死に、星図が二度と更新されなくなる**（2026-08-21・圏外）。
  `SupervisorJob` を挟む
- **キャンバスのテキストは、書き換える前に消す。** ファームは新しい矩形しか描き直さないので、
  短い名前へ変えると**前の名前の末尾が残る**（「る」の 1 文字が右上に残った）。
  ただし前の矩形を覆えるなら消さない（毎フレーム消すと衛星の印がちらつく）
- **絵と根拠を別に計算しない。** グラスに出したラベルと AI へ渡す星座名を別々に出していたら、
  **「オリオン座」と出ているのに別の星座を喋った**（#37）
- **仰角で解説を断らない。** 水平あたりでも仰角は負に振れる。「地面を向いています」で
  断ると、見えているものの名前も出ないまま黙る

## 文書と CI

- `docs/team-e/` は通常の Markdown で管理する。SDK API の説明は複製せず、上流の公開ドキュメントを参照する
- CI（`.github/workflows/checks.yml`）は `python3 tools/build-simulator.py --check` で
  天球シミュレータの生成物が最新か検査する
- private SDK の取得に PAT が必要なため、アプリの JVM テストと APK ビルドは開発者の手元で実行する
- `data/**` と `tools/simulator/index.html` は生成物。直接編集せず、対応する `tools/build-*.py` を使う
- GitHub Pages は公開しない。SDK ドキュメントは上流が公開している

## 決まったこと・未決定事項

**[docs/team-e/index.md](docs/team-e/index.md) にある。** 実装の前に必ず読む。
外せない数値だけここに置く：

| | |
|---|---|
| パネル | **576×360**。画像 1 枚の実用最大は **528×330** |
| 画像バッファ | **380,000 バイト**（`width * height * 2` の合計で数える） |
| テキスト | **8 要素・合計 190 バイト** |
| 転送 | 1 パケット 200 バイトで**実測 8〜9ms**。528×330 の 1 枚で 332〜390ms |
| 描き直し | **0.4 秒静止 ＋ 前の絵から 6° 以上**。動きに追従させると点滅にしかならない |
| 画角 | **仮の 35° 固定・未実測**（`ObservationDefaults.STAR_MAP_FOV_DEG`） |
| ヨードリフト | 静止中 **44°/分**。補正込みで実測 0.0°/分 |
| 方位の残差 | 地磁気で **±5〜15°**。星座の同定（±20°）は成立、星図の重ね合わせ（±2〜3°）は**追わない** |

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
