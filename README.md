# SABERA team-e

> jig.jp サマーインターン SABERA コース

## 概要

**空にかざすと視界と星座が重なり、AI に頼むと今見えている星座を解説してくれるアプリ**

- スマホのセンサーとグラスの 6DoF から視野内の星座を割り出す
- グラスに星図と星座名を出す
- ツルを 1 回タップすると、その星座の解説が返ってきて読み上げられる
- **人工衛星モード**に切り替えると、いま空を通っている衛星を同じ座標で出す

## 進捗状況

- 実装は `samples/kmp/app`
- 仕様は [docs/team-e/index.md](docs/team-e/index.md)

| | 状態 |
|---|---|
| 星図をグラスに出す | **実機で確認済み。** キャンバスに 528×330、星座名はテキストで手前に重ねる |
| 首の向きに追従する | **実機で確認済み。** 首が止まってから 1 枚（転送中は前の絵が消える） |
| 方位合わせ | **実機で確認済み。** グラスの十字とスマホのマーカーを重ねる |
| 観測地の測位 | **実機で確認済み。** 融合 → GPS → 基地局。取れなければ手入力 |
| AI 解説と読み上げ | **実機で確認済み。** OpenAIで解説と音声を生成し、失敗時は端末の`TextToSpeech`へ戻る |
| 衛星の軌道計算（SGP4 / SDP4） | **JVM テストのみ。** 参照実装と 4mm 差。24 機＋スターリンク 10,748 機を同梱 |
| 衛星の描画とモード切り替え | **実機未確認** |

## 開発の仕方

1. [CONTRIBUTING.md](CONTRIBUTING.md) を読む
2. **GitHub PAT を設定する**（これが無いとビルドが通らない → [docs/github-pat.md](docs/github-pat.md)）
3. **Android 実機**を用意する（BLE 必須。エミュレータでは動かない）

```bash
cd samples/kmp
./gradlew :app:installDebug
```

- AI 解説を使うなら `cp .env.example .env` して `OPENAI_API_KEY` を書く（**無くてもビルドは通る**）

## リポジトリの歩き方

| パス | 中身 |
|---|---|
| [AGENTS.md](AGENTS.md) | 技術的な前提・SDK の制約・未決定事項。AI エージェント向けだが人間が読んでもよい |
| [CONTRIBUTING.md](CONTRIBUTING.md) | 環境構築・進め方・コミット規約・困ったとき |
| [docs/team-e/index.md](docs/team-e/index.md) | **team-e の仕様書。** 座標変換・グラス出力の制約・画面遷移・人工衛星モード |
| `samples/kmp/app/` | **アプリ本体**（Kotlin + Compose） |
| `data/` | 同梱データ（星表・星座線・TLE）。すべて生成物 |
| `tools/` | 同梱データの生成スクリプトと天球シミュレータ |
| [docs/github-pat.md](docs/github-pat.md) | privateなSDKを取得するためのPAT設定 |

## SDK について

- このアプリは **Sabera App SDK**（`jp.jig.sabera.app.sdk:sabera-app-core`）の上に作る
- 上流 → [jig-SABERA/sabera-sdk](https://github.com/jig-SABERA/sabera-sdk) /
- 公開ドキュメント → **<https://jig-sabera.github.io/sabera-sdk/>**

- **0.6.0 まで取り込み済み**
- team-e が使っている主な API — `sendCanvasImage`（星図）/ `sendCanvasElements`（星座名）/
  `imuData`（6DoF）/ `gestureEvents`（ツルの操作）
- **グラスから音は鳴らせない**（スピーカーが無い）。読み上げはスマホから
- 制約の詳細 → [グラス出力の制約](docs/team-e/glass-output.md)

SDKの使い方・APIリファレンス・追加履歴は
[上流の公開ドキュメント](https://jig-sabera.github.io/sabera-sdk/)を正とする。
このリポジトリには[GitHub PATの作り方](docs/github-pat.md)だけを置く。

### SDK の取得設定

private な GitHub Packages（`jig-SABERA/sabera-sdk-packages`）で配布されている。
**`read:packages` スコープの PAT が要る。** `~/.gradle/gradle.properties` に置く：

```properties
GitHubPackagesUsername=<GitHubのユーザー名>
GitHubPackagesPassword=<read:packages を持つ PAT>
```

**Android 実機のみを対象にしている。** iOS 向けの SPM 定義（`Package.swift`）は
上流でも SDK 0.0.10 のまま追従していないので、team-e では撤去した。

## ライセンス

| 対象 | ライセンス |
|---|---|
| このリポジトリのサンプル・ラッパーコード | [Apache License 2.0](LICENSE) |
| Sabera App SDK 本体（AAR / XCFramework） | **SDK 利用規約**（Apache 2.0 の対象外） |
| 同梱の星表データ（d3-celestial / XHIP） | BSD-3-Clause。帰属は [NOTICE](NOTICE) |
