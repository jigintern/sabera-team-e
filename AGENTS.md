# AGENTS.md

このリポジトリで作業する AI エージェント（Claude Code / Codex など）向けの共通ガイド。
**ここは禁止事項と規約だけ。** 仕様は [docs/team-e/00_index.md](docs/team-e/00_index.md)、
人間向けの手順は [CONTRIBUTING.md](CONTRIBUTING.md)。

## このアプリ

- **SABERA** = スマートグラス。このリポジトリ = SABERA App SDK で **team-e** がアプリを作る場所
- 目標 = **空にかざしたグラスの視界と星座を重ね、AI に頼むと今見えている星座を解説してくれるアプリ**
- **現在のフェーズ = 実装中。** アプリ本体は `samples/kmp/app`。
  実機で動いているものとテストだけのものは [00_index.md](docs/team-e/00_index.md) の表で分けている

## 原則

- **頼まれていないアプリ機能を先回りして実装しない**
- **未決定事項を勝手に埋めない。** [00_index.md](docs/team-e/00_index.md) に追記するか質問する
- **実機（BLE）でしか確かめられないことが多い。確かめていないことを「動く」と書かない**
- **精度と UX がぶつかったら UX を採る。** 使う人は天文の初心者で、要るのは
  「どのあたりに何があるか」。星座の同定（±20°）で足りる。
  **精度はユーザーに何もさせない範囲で最大化する**（ドリフト補正・IAU 境界判定・静止区間の平均）
- **決まったことも踏んだ失敗も、チャットで終わらせずリポジトリに残す**（下の地図の置き場所へ）

## ドキュメントの地図

| 知りたいこと | 読む先 |
|---|---|
| **決まったこと / 未決定事項 / 実装状況** | [00_index.md](docs/team-e/00_index.md) |
| **何ができないか**（SDK・ハードの制約） | [01_sdk.md](docs/team-e/01_sdk.md) |
| 何をどれだけ出せるか（画像・テキスト・転送） | [02_glass-output.md](docs/team-e/02_glass-output.md) |
| 座標変換・方位合わせ・星座判定 | [03_coordinate-system.md](docs/team-e/03_coordinate-system.md) |
| **星図に何をどう描くか**（点・絵・空の濃さ） | [04_star-map-drawing.md](docs/team-e/04_star-map-drawing.md) |
| 画面遷移・ジェスチャー・スマホ UI | [05_app-flow.md](docs/team-e/05_app-flow.md) |
| **何を喋るか**（解説・一口メモ・声の質問） | [06_narration.md](docs/team-e/06_narration.md) |
| 声と BGM の鳴らし方 | [07_sound.md](docs/team-e/07_sound.md) |
| 人工衛星（軌道・選定・描き方） | [08_satellites.md](docs/team-e/08_satellites.md) / [09_satellite-drawing.md](docs/team-e/09_satellite-drawing.md) |
| **どこに何のコードがあるか・ビルドの前提** | [10_code-map.md](docs/team-e/10_code-map.md) |
| **星座ガイド**（台本・即興ガイド・受動再生） | [16_guide.md](docs/team-e/16_guide.md) |
| **踏んだ落とし穴**（実機・実装） | [11_pitfalls.md](docs/team-e/11_pitfalls.md) |
| **実機で測った数字** | [12_measurements.md](docs/team-e/12_measurements.md) / [15_yaw-drift.md](docs/team-e/15_yaw-drift.md) |
| 実機で何を確かめるか | [13_field-check.md](docs/team-e/13_field-check.md) |

## やってはいけないこと

**理由と再発の記録は右の列にある。** 迷ったら読みに行く。

| やらない | なぜ | 詳細 |
|---|---|---|
| 確かめていないことを「動く」と書く | 実機とテストの区別が消えると、次の人が実機確認を飛ばす | [index](docs/team-e/00_index.md) |
| 未決定事項を勝手に埋める | 決めた記録が残らないと同じ議論を繰り返す | [index](docs/team-e/00_index.md) |
| `data/**` を直接編集する | すべて生成物。次の生成で消える | [code-map](docs/team-e/10_code-map.md) |
| 解説文を AI に生成させる（**再生時**） | **星を見に行く場所ほど電波が届かない。** 88 星座ぶん同梱してある。**ガイドの台本を作るときだけ例外**（4 条件） | [narration](docs/team-e/06_narration.md) / [guide](docs/team-e/16_guide.md) |
| 声で聞き取った文を指示として扱う | 喋るだけで解説員の役割を上書きできてしまう（#38） | [narration](docs/team-e/06_narration.md) |
| 話題を絞って断る | 「ISS って何？」に一言も答えられなかった。**迷ったら答えるほうへ倒す** | [narration](docs/team-e/06_narration.md) |
| 天文の言葉をそのまま喋らせる | 初心者には何をすればよいか分からない（`SkyTipsTest` が検査） | [narration](docs/team-e/06_narration.md) |
| 首の向きを命令に使う | 星図を出している間は**頭の向き＝見ている空**。例外は解説画面の字幕送りだけ | [pitfalls](docs/team-e/11_pitfalls.md) |
| `yawDegrees` をそのまま方位に使う | 静止中に **44°/分**流れる。`sendNaviCourse` も答えにならない | [pitfalls](docs/team-e/11_pitfalls.md) |
| RLE / Opus のコーデックを自前で書く | SDK 0.0.12 / 0.3.0 で入った | [pitfalls](docs/team-e/11_pitfalls.md) |
| 画像を「枚数」で設計する／回るものを画像で描く | 先に尽きるのはバッファ。全画面 1 枚は 332〜390ms かかり点滅になる | [pitfalls](docs/team-e/11_pitfalls.md) |
| 絵と根拠を別々に計算する | 「オリオン座」と出ているのに別の星座を喋った（#37） | [pitfalls](docs/team-e/11_pitfalls.md) |
| `optString` を JSON の null に使う／`coroutineScope` の中で `SupervisorJob` を作る | **テストで落ちず実機だけで壊れる／テストが返ってこなくなる** | [pitfalls](docs/team-e/11_pitfalls.md) |
| lint の detector を無効化して通す | ツールチェーンの不整合を隠すだけ | [code-map](docs/team-e/10_code-map.md) |
| `Co-Authored-By` にエージェントを入れる | コミットの作者は人間 | 下の規約 |
| 秘密情報（PAT・API キー）をコミットする | `.env` は `.gitignore` 済み | [CONTRIBUTING](CONTRIBUTING.md) |

## 開発コマンド

```bash
cd samples/kmp
./gradlew :app:installDebug              # 実機にインストール
./gradlew :app:testDebugUnitTest         # JVM テスト（座標変換・SGP4・AI 周り）
./gradlew :app:assembleDebug             # Debug APK を生成

tools/pull-session-log.sh                # 実機の観測ログを取り出して要約する（--logcat / --clear）
```

同梱データの作り直しとビルドの前提は [10_code-map.md](docs/team-e/10_code-map.md)。

## 外せない数値

**詳細と測った条件は [12_measurements.md](docs/team-e/12_measurements.md)。**

| | |
|---|---|
| パネル | **576×360**。画像 1 枚は **544×340 を試し、入らなければ 528×330** |
| 画像バッファ | **380,000 バイト**（`width * height * 2` の合計で数える） |
| テキスト | **8 要素・合計 190 バイト** |
| 転送 | 1 パケット 200 バイトで**実測 8〜9ms**。528×330 の 1 枚で 332〜390ms |
| 描き直し | **0.18 秒静止 ＋ 前の絵から 6° 以上**。動きに追従させると点滅にしかならない |
| 先出し | **減速に入ったら「止まる先」へ 1 枚**（割引 0.6・頭打ち 8°・間隔 1.2 秒）。**実機未確認** |
| 画角 | **仮の 35° 固定・未実測**（`ObservationDefaults.STAR_MAP_FOV_DEG`） |
| 解説画面 | **1 枚 3 行・1 行 17 文字**を 1 行ずつ上へ流す（189 バイト・1 電文）。**見出しは 1 枚目だけ** |
| 台本の QR | 1 枚 **2,953 バイト**（version 40・誤り訂正 L・生バイト）。本文 200 字で **10 段**。Base64 を挟むと 5 段に落ちる |
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
- **ドキュメントは箇条書きと表で書く。** 1 つの文書は 1 つの役目だけを持ち、
  同じことを 2 か所に書かない（**この AGENTS.md は 200 行を超えない**）
- **`docs/team-e/` のファイル名の数字は読み順。** 足すときは末尾に番号を続けるか、まとめて振り直す
- SDK API の説明は複製せず、[上流の公開ドキュメント](https://jig-sabera.github.io/sabera-sdk/)を参照する
- 秘密情報（PAT、API キー）をコミットしない
- ライセンス — サンプル・ラッパーコードは Apache License 2.0。
  **SDK 本体（`jp.jig.sabera.app.sdk:*`）は対象外**で別途 SDK 利用規約
