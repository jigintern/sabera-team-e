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

**仕様は [docs/team-e/](docs/team-e/) にあり、ファイル名の数字が読み順。**
番号は 10 ごとの帯で役目を表す — **10 制約 / 20 仕組み / 30 機能 / 40 衛星 / 50 コード /
60 実機確認 / 70 記録**。足すときはその帯の末尾に続ける。

| 知りたいこと | 読む先 |
|---|---|
| **言葉の定義**（用語集） | [CONTEXT.md](CONTEXT.md) |
| **決まったこと / 未決定事項**・ドキュメントの地図 | [00_index.md](docs/team-e/00_index.md) |
| **いまどこまで動いているか**（実機で確認済みか） | [01_status.md](docs/team-e/01_status.md) |
| **何ができないか**（SDK・ハードの制約） | [10_sdk.md](docs/team-e/10_sdk.md) |
| 何をどれだけ出せるか（画像・テキスト・転送） | [11_glass-output.md](docs/team-e/11_glass-output.md) |
| 座標変換・方位合わせ・星座判定 | [20_coordinate-system.md](docs/team-e/20_coordinate-system.md) |
| **星図に何をどう描くか**（点・絵・空の濃さ） | [21_star-map-drawing.md](docs/team-e/21_star-map-drawing.md) |
| 役割分担・画面遷移 | [30_app-flow.md](docs/team-e/30_app-flow.md) |
| **何を触ると何が起きるか**（3 枠・字幕送り） | [31_gestures.md](docs/team-e/31_gestures.md) |
| グラスに出す画面（星図・挨拶・読み込み・解説） | [32_glass-screens.md](docs/team-e/32_glass-screens.md) |
| スマホ UI・設定の並べ方・通知の入切 | [33_phone-ui.md](docs/team-e/33_phone-ui.md) |
| **何を喋るか**（解説・一口メモ・声の質問） | [34_narration.md](docs/team-e/34_narration.md) |
| 声と BGM の鳴らし方 | [35_sound.md](docs/team-e/35_sound.md) |
| **天体の案内**（矢印と文字で首を導く） | [36_object-guidance.md](docs/team-e/36_object-guidance.md) |
| **星座ガイド**（台本・即興ガイド・受動再生） | [37_guide.md](docs/team-e/37_guide.md) |
| **星空の再現**（場所・日時の指定・深い時代） | [38_sky-simulation.md](docs/team-e/38_sky-simulation.md) |
| 人工衛星（軌道・選定・描き方） | [40_satellites.md](docs/team-e/40_satellites.md) / [41_satellite-drawing.md](docs/team-e/41_satellite-drawing.md) |
| **どこに何のコードがあるか・ビルドの前提** | [50_code-map.md](docs/team-e/50_code-map.md) |
| 実機で何を確かめるか | [60_field-check.md](docs/team-e/60_field-check.md) |
| **実機で測った数字** | [70_measurements.md](docs/team-e/70_measurements.md) / [71_yaw-drift.md](docs/team-e/71_yaw-drift.md) |
| **踏んだ落とし穴**（実機・実装） | [72_pitfalls.md](docs/team-e/72_pitfalls.md) |
| **見送った案と残っている宿題** | [73_backlog.md](docs/team-e/73_backlog.md) |
| 星図を空に重ねる精度（**見送った計画**） | [74_alignment-accuracy.md](docs/team-e/74_alignment-accuracy.md) |

## やってはいけないこと

**理由と再発の記録は右の列にある。** 迷ったら読みに行く。

| やらない | なぜ | 詳細 |
|---|---|---|
| 確かめていないことを「動く」と書く | 実機とテストの区別が消えると、次の人が実機確認を飛ばす | [status](docs/team-e/01_status.md) |
| 未決定事項を勝手に埋める | 決めた記録が残らないと同じ議論を繰り返す | [index](docs/team-e/00_index.md) |
| `data/**` を直接編集する | すべて生成物。次の生成で消える | [code-map](docs/team-e/50_code-map.md) |
| 解説文を AI に生成させる（**再生時**） | **星を見に行く場所ほど電波が届かない。** 88 星座ぶん同梱してある。**ガイドの台本を作るときだけ例外**（4 条件） | [narration](docs/team-e/34_narration.md) / [guide](docs/team-e/37_guide.md) |
| 声で聞き取った文を指示として扱う | 喋るだけで解説員の役割を上書きできてしまう（#38） | [narration](docs/team-e/34_narration.md) |
| 話題を絞って断る | 「ISS って何？」に一言も答えられなかった。**迷ったら答えるほうへ倒す** | [narration](docs/team-e/34_narration.md) |
| 天文の言葉をそのまま喋らせる | 初心者には何をすればよいか分からない（`SkyTipsTest` が検査） | [narration](docs/team-e/34_narration.md) |
| 首の向きを命令に使う | 星図を出している間は**頭の向き＝見ている空**。例外は解説画面の字幕送りだけ | [gestures](docs/team-e/31_gestures.md) / [pitfalls](docs/team-e/72_pitfalls.md) |
| `yawDegrees` をそのまま方位に使う | 静止中に **44°/分**流れる。`sendNaviCourse` も答えにならない | [pitfalls](docs/team-e/72_pitfalls.md) |
| RLE / Opus のコーデックを自前で書く | SDK 0.0.12 / 0.3.0 で入った | [pitfalls](docs/team-e/72_pitfalls.md) |
| 画像を「枚数」で設計する／回るものを画像で描く | 先に尽きるのはバッファ。全画面 1 枚は 332〜390ms かかり点滅になる | [pitfalls](docs/team-e/72_pitfalls.md) |
| 絵と根拠を別々に計算する | 「オリオン座」と出ているのに別の星座を喋った（#37） | [pitfalls](docs/team-e/72_pitfalls.md) |
| `optString` を JSON の null に使う／`coroutineScope` の中で `SupervisorJob` を作る | **テストで落ちず実機だけで壊れる／テストが返ってこなくなる** | [pitfalls](docs/team-e/72_pitfalls.md) |
| lint の detector を無効化して通す | ツールチェーンの不整合を隠すだけ | [code-map](docs/team-e/50_code-map.md) |
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

同梱データの作り直しとビルドの前提は [50_code-map.md](docs/team-e/50_code-map.md)。

## 外せない数値

**正本は [70_measurements.md](docs/team-e/70_measurements.md)。** ここは毎回読む早見表で、
食い違ったら台帳のほうが正しい（測った条件も向こうにある）。

| | |
|---|---|
| パネル | **576×360**。画像 1 枚は **544×340 を試し、入らなければ 528×330** |
| 画像バッファ | **380,000 バイト**（`width * height * 2` **＋圧縮後サイズ**で数える） |
| テキスト | **8 要素・合計 190 バイト** |
| 転送 | 1 パケット 200 バイトで**実測 8〜9ms**。528×330 の 1 枚で 332〜390ms |
| 描き直し | **0.18 秒静止 ＋ 前の絵から 6° 以上**。動きに追従させると点滅にしかならない |
| 先出し | **減速に入ったら「止まる先」へ 1 枚**（割引 0.6・頭打ち 8°・間隔 1.2 秒）。**実機未確認** |
| 画角 | **仮の 35° 固定・未実測**（`ObservationDefaults.STAR_MAP_FOV_DEG`） |
| 解説画面 | **1 枚 3 行・1 行 17 文字**を 1 行ずつ上へ流す（189 バイト・1 電文）。**見出しは 1 枚目だけ** |
| 台本の QR | 1 枚 **2,953 バイト**（version 40・誤り訂正 L・生バイト）。本文 200 字で **10 段**。Base64 を挟むと 5 段に落ちる |
| ヨードリフト | 静止中 **−44°/分**（負が正常）。補正込みで実測 0.0°/分 |
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
- **`docs/team-e/` のファイル名の数字は読み順**で、**10 ごとの帯が役目**を表す
  （上の地図）。足すときは**その帯の末尾**に続ける。帯が埋まったらまとめて振り直す
- SDK API の説明は複製せず、[上流の公開ドキュメント](https://jig-sabera.github.io/sabera-sdk/)を参照する
- 秘密情報（PAT、API キー）をコミットしない
- ライセンス — サンプル・ラッパーコードは Apache License 2.0。
  **SDK 本体（`jp.jig.sabera.app.sdk:*`）は対象外**で別途 SDK 利用規約
