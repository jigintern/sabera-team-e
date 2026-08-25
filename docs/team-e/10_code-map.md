# コードとデータの地図

**どこに何があり、同梱データがどう作られるか。** 仕様は [00_index.md](00_index.md)、
禁止事項は [AGENTS.md](../../AGENTS.md)。

## リポジトリ

| パス | 中身 |
|---|---|
| `samples/kmp/app/` | **team-e のアプリ本体**（Kotlin + Compose）。ここを書き換えて育てる |
| `docs/team-e/` | **team-e の仕様書** |
| `docs/github-pat.md` | private SDK を取得するための GitHub PAT 設定 |
| `data/` | 同梱データ（星表・星座線・解説文・流星群・TLE）。**すべて生成物** |
| `tools/` | 同梱データの生成スクリプトと、実機ログの取り出し |

## パッケージ

**「何に答える場所か」で切っている。** 迷ったら、そのコードが壊れたときに何が起きるかで決める
（星が違う位置に出る → `sky`、グラスに出ない → `glass`）。

| パッケージ | 責務 |
|---|---|
| `sky/` | **空の計算。Android に触らない**（JVM テストで数字を固定できる）。座標変換・太陽・月惑星・IAU 境界・観測の既定値・天体案内の状態 |
| `catalog/` | 同梱データ（`data/`）を読む。星表・88 星座の解説文・流星群 |
| `satellite/` | 軌道計算（SGP4/SDP4）と、いつどこに見えるか |
| `glass/` | **グラスへの出力。パネルの制約は全部ここ。** 星図を焼く・文字を組む・明るさを送る |
| `alignment/` | **星図を空に合わせるために端末が測るもの。** 方位・傾き・磁気の歪み・観測地 |
| `narration/` | **何を喋るか。** 解説の組み立て、一口メモ、声の質問の入口・出口の検査 |
| `guide/` | **星座ガイドの台本。** 形と読み書き・再生前の解決・即興ガイドの組み立て・詳細ガイドの編集・配る形。**Android に触るのは保存だけ** |
| `voice/` | **どう鳴らす・どう録るか。** AI 音声、端末の読み上げ、グラスのマイク |
| `openai/` | **通信するのはここだけ。** 圏外で何が失われるかがここを見れば分かる |
| `sound/` | BGM。**場面（`BgmScene`）と曲（`BgmTrack`）を分けてある**。選曲は `BgmPlaylist` |
| `support/` | どこにも属さない道具（観測ログ・声のやり取りの履歴・音量の持ち上げ・重複送信の抑止） |
| `ui/` | 4 画面（ホーム・接続・方位合わせ・星図）。`ui/component/` は部品と色 |

## 外せない置き場所

**同じ処理を 2 か所に増やさない。**

| ファイル | 責務 |
|---|---|
| `ui/GlassesApp.kt` | 4 画面の遷移と戻るキー。画面は `AppScreen` で表し、整数を増やさない |
| `ui/CalibrationScreen.kt` | 方位合わせの唯一の実装。観測画面へ同じ処理を重ねない |
| `ui/StarMapScreen.kt` | 観測セッションの調停。描画・キャンバス変換・補正計算は下へ委譲する |
| `ui/component/ObservationSettings.kt` | 設定パネルの区画。**見出しの中身を見出しどおりにする** |
| `ui/GuideScreen.kt` | 台本の一覧と即興ガイド。**グラスをつなぐ前に通る**ので、接続の外側に置く。詳細エディタは**いちばん下に畳む** |
| `ui/AuthoredGuideScreen.kt` | 詳細エディタ（toB）。想定した空・候補・AI 対話・段の編集と並べ替え |
| `ui/GuideShareScreen.kt` | 配る。段の ON/OFF・編集の可否・QR・ファイル書き出し |
| `ui/GuideImportScreen.kt` | 受け取る。**入れる前に中身を見せる**（何を喋るのか分からないまま入れさせない） |
| `ui/component/ReorderableColumn.kt` | 自前のドラッグ並べ替え。**掴んでいる間は並びを変えない**（index が変わると指を離す前に切れる） |
| `ui/component/QrScanner.kt` | CameraX で QR を探す。**独自 Activity を持ち込まない**（画面の向きの縦固定と衝突する） |
| `ui/component/GuideControls.kt` | ガイドを選ぶダイアログと進み具合。**始める口は観測画面の畳んだ側 1 つだけ** |
| `res/drawable-nodpi/hoshishirube_logo.png` / `hoshishirube_mark.png` | 採用ロゴの実装用素材。横組みはホームとグラス、マーク単体はランチャーで使う |
| `glass/GlassCanvas.kt` | パネル寸法、画像バッファ、テキスト制限、RLE サイズ見積り |
| `glass/GlassTextArt.kt` | **ロゴと文字を画像に焼く。** テキスト枠では専用字形と本文を組み分けられない。**動かないものにだけ使う** |
| `glass/GlassTextPage.kt` | **解説専用画面の組版**（#40）。1 枚 3 行を**1 行ずつ上へ流す**。行は動かさず、文字は伸びる方向にしか変えない |
| `glass/StarMap.kt` | 絵とラベルを 1 つの器で持つ。**解説の主役は `constellationNames()` の先頭**（#37） |
| `glass/GuidanceIndicator.kt` | 天体案内の矢印・到着リングの共通形状。グラスとスマホのプレビューを同じ向き・比率にする（#61） |
| `glass/GuidanceOverlay.kt` / `GuidanceOverlaySender.kt` | 共通形状を小画像へ焼き（左右120×56・上下56×120・到着80×80）、**全画面を送らず、この小画像だけ替える**。**枠が変わるときは先に消す**（#46・#61） |
| `catalog/ConstellationLore.kt` | 88 星座の解説文。**解説に通信を使わない**（`data/constellation-lore.json`） |
| `catalog/MeteorShowers.kt` | 流星群の引き当て。**日付だけで決まる**ので通信も要らない（年をまたぐ群がある） |
| `sky/ObservationDefaults.kt` / `Directions.kt` | 観測の既定値と方位表現 |
| `sky/CelestialGuidance.kt` | 案内対象と、案内要求を取り出す固定ルール（#46） |
| `sky/GuidanceTracker.kt` | 左右→上下の段階、5°/8°のヒステリシス、到着と60秒の状態遷移（#46・#61） |
| `sky/ObservationMode.kt` | 現在の空と、固定した場所・時刻。**時刻のつまみの位置もここで出す**（#45） |
| `sky/Timelapse.kt` | 時代を送る途中の空。**年だけを補間**し、月日と時刻は目的地に固定する（#45） |
| `glass/TimelapseSender.kt` | 240×160 の窓を**画像 id 2 枚で交互に**送る。**置いてから消す**ので途中が空にならない（#45） |
| `sky/CityCatalog.kt` / `SkyCommand.kt` | 同梱 18 都市と IANA タイムゾーン、音声から**許可済み 4 操作だけ**を取り出す（#45） |
| `sky/Ephemeris.kt` | 月と 8 惑星の位置計算。**天体の位置はここだけ** |
| `alignment/YawDriftCorrector.kt` | Android 非依存のヨードリフト補正。変更時は JVM テストも更新する |
| `alignment/MagneticQuality.kt` | 磁気の歪みの検証。OS の信頼度を信じない |
| `alignment/CompassGate.kt` | 磁気精度で止めるかどうか。**8 の字で止めるが、止めっぱなしにはしない**（#65） |
| `alignment/HeadFlick.kt` | 首の上下フリック。**解説画面の字幕送り専用**（星図では首は見る向きのまま） |
| `narration/AskGuard.kt` | 声の質問の検査。**聞き取った文は指示ではなくデータ**（#38） |
| `narration/SkyTips.kt` | 読み込み画面の一言。**通信も生成も要らない**（時刻と場所から端末が組む） |
| `guide/StarGuide.kt` | 台本の形と JSON。**方角は持たせない**（再生時に引き直す） |
| `guide/GuidePlan.kt` | 台本をいまの空へ突き合わせる。**出ていない星座を飛ばす** |
| `guide/ImpromptuGuide.kt` | 即興ガイドの選定と文面。**選ぶのは端末**（AI に星座を選ばせない） |
| `guide/GuideMaker.kt` | **AI と同梱の切り替えはここだけ。** 失敗すれば必ず同梱へ落ちる |
| `guide/GuideStore.kt` | 台本を `filesDir/guides/` に残す。**`data/` には置かない** |
| `guide/GuideSchedule.kt` | 段ごとの推定時刻と、その時刻で見えるかの判定。**一点ではなく幅で見る** |
| `guide/GuideCodec.kt` | QR とファイルの出入口。圧縮と**形の上限**。**中身は見ない** |
| `guide/AuthoredGuide.kt` | 編集中の台本（toB）。並べ替え・段の増減・上限の判定 |
| `guide/GuideAsk.kt` | toB の対話で**通信の前に端末が断る**ところ。`narration/AskGuard` と同じ位置づけ |
| `openai/OpenAiGuide.kt` | 即興ガイドの文を書かせる（一往復）。**「解説文を AI に生成させない」の例外**（[guide](16_guide.md) の 4 条件） |
| `openai/OpenAiGuideChat.kt` | 詳細ガイドを対話で作らせる（履歴を積む）。**候補の中からしか選ばせない** |
| `support/QrCode.kt` | QR の生成と解読。**文字ではなく生バイトを運ぶ**（ISO-8859-1 経由でバイトモードにする） |
| `support/Connectivity.kt` | いま通信できるか。**聞くのは台本を作る画面だけ**（再生中は通信しない） |
| `support/AskHistory.kt` | 声のやり取りの履歴。設定パネルから読み直す |
| `support/LoudnessBoost.kt` | つまみの上限（1.0）から先の音量。**読み上げと BGM に同じ量をかける** |

## 同梱データ（`data/` は生成物）

**直接編集しない。** 対応するスクリプトを直して作り直す。

```bash
python3 tools/build-star-catalog.py            # 星表
python3 tools/build-constellation-figures.py   # 星座絵
python3 tools/build-constellation-lore.py      # 88 星座の解説（長さと記号を検査する）
python3 tools/build-asterisms.py               # 大三角・天の川
python3 tools/build-meteor-showers.py          # 流星群
python3 tools/build-satellites.py              # TLE を取り直す
```

BGM だけは `data/` ではなく `res/raw/` に置く（アプリの音源なので）。

```bash
tools/build-bgm.sh                             # 6 曲すべて（incompetech から落とす・要ネットワーク）
tools/build-bgm.sh bgm_ambiment                # 1 曲だけ
python3 tools/pick-bgm-window.py <元曲.mp3>    # 切り出し位置を選び直す
```

- **元曲は 40〜70MB ある。** 落としたものはキャッシュに残るので、2 回目からは切り出しだけ
- 切り出し位置は**耳ではなく数字で選ぶ**。ループの継ぎ目になる 2 か所の音量差が
  いちばん小さく、区間の揺れが少ないところを採る（`pick-bgm-window.py`）
- 曲を足したら **`BgmTrack` と `NOTICE` の両方**に足す。設定パネルの帰属は `BgmTrack` から組み立てる

- **アプリの生きている間 1 回だけ読む**（`support/BundledData.kt`）
- CI（`.github/workflows/checks.yml`）は**手元で完結する生成物**（星座解説・星座絵・大三角）を
  作り直して `data/` に差分が出ないか検査する。**星表と TLE は外部取得が要るので回さない**
- **GitHub Pages は公開しない。** SDK ドキュメントは上流が公開している

## ドキュメント用の画像

**グラスに出ている絵は、実機なしで作り直せる。** 中身はアプリと同じコードで計算し、
色を付けて文字を焼くところだけ JDK 単体実行に分けてある（AWT は Android のユニットテストから触れない）。

```bash
cd samples/kmp && ./gradlew :app:testDebugUnitTest --tests '*DocumentImagesTest*'   # 中身
cd ../.. && java tools/compose-glass-images.java                                    # 絵（docs/images/）
java -Djava.awt.headless=true tools/compose-phone-preview.java                      # スマホのホーム
```

- 見本は **2026-01-01 18:00 JST の鯖江から東の空**（`DocumentImagesTest` が
  オリオン座の高度が 15° に近い時刻を選ぶ）
- **パネルは視野全体ではない。** 広い視界（仮に 92°）の中に、パネルの画角（**仮の 35°**）を
  正面より 11° 上へ置く。四隅の向きを視界へ投影して矩形を出しているので、
  **画角の値を変えれば枠の大きさもそのまま変わる**
- 背景の星は**星図と同じ投影**で置く（`project` / `projectionScale`）。
  **星座線の頂点と空の星が重なる**のが要点で、グラスの絵は塗りつぶさず**光として足す**
  （黒は光らないので下の景色が残る）
- **点の大きさは等級から決め直す。** 星図の点は読ませるために大きく描いてあるので、
  そのまま空の星に使うと実際よりずっと大きく見える
- **街のシルエットと 5 等より暗い星は飾り**（`tools/compose-glass-images.java`・種は固定）。
  位置に意味は無い。**これは写真ではない**ので、パネルのにじみ・明るさ・実機のフォントも出ない
- スマホのホームは `compose-phone-preview.java` が実装と同じロゴ・色・配置から作る。
  **星図画面はBLEがつながらないと進めない**ので、必要なら実機のスクリーンショットを使う
- 図（`docs/team-e/diagrams/*.drawio.svg`）は draw.io でそのまま開いて編集し、上書き保存する。
  CLI から出し直すなら：

```bash
/Applications/draw.io.app/Contents/MacOS/draw.io --no-sandbox -x -f svg \
  --embed-diagram --embed-svg-fonts false --theme light -o 図.drawio.svg 図.drawio
```

## ビルドの前提

- JDK 17 / Gradle 8.10.2（wrapper 同梱）/ Kotlin 2.3.10 / AGP 8.7.0
- 実行時依存は **SDK・coroutines・Compose・activity・lifecycle** に加えて
  **zxing core 1 個 ＋ CameraX 4 個**（台本を QR で配るため）。
  **ZXing Android Embedded と ML Kit は使わない**（理由は [guide](16_guide.md)）
- Android `minSdk 31` / `compileSdk 36` / `targetSdk 36`
- **BLE 実機が必須。エミュレータでは動作確認できない**
- SDK は private な GitHub Packages 配布。**`read:packages` の PAT が無いとビルドが落ちる**
  （[手順](../github-pat.md)）。CI では取れないので、**JVM テストと APK ビルドは手元で回す**
- `.editorconfig`（4 スペース / 120 桁 / intellij_idea）は全 Kotlin に効く
- **`:app:lintDebug` はツール側でクラッシュする。** AGP 8.7.0 と Kotlin 2.3.10 の解析 API が合わず、
  `RememberInComposition` / `NullSafeMutableLiveData` detector が `IncompatibleClassChangeError` になる。
  **detector を無効化して通したことにせず**、ツールチェーン更新時に戻す
