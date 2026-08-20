# SABERA team-e

スマートグラス **SABERA** 向けのアプリを team-e で開発するリポジトリ。

## 作るもの

**空にかざすと視界と星座が重なり、AI に頼むと今見えている星座を解説してくれるアプリ。**

- スマホのセンサー（方位・傾き・位置・時刻）から視野内の星座を割り出す
- グラスに星図と解説を出す
- 「あれ何の星座？」と聞けば、その場で解説が返ってくる

## いまの状況

- **仕様策定フェーズ。** チームでドキュメントと仕様書を詰めている段階で、アプリの実装はまだ始まっていない
- SDK 0.0.11 で **画像送信 API**（`enterImageDisplayPage` / `sendImage`）が追加され、**星図をグラスに出す道が開いた**
  - **0.0.12 で簡素化**され、1 画素 1 バイトのグレースケールを渡すだけでよくなった（量子化と圧縮は SDK 側）
  - ただしサイズ上限 **196x196**、**緑単色の 3bit（8 階調）**という制約は変わらない
- **SDK 0.1.0 で 6DoF が解放された** — `startImuData` / `imuData` でグラスのピッチ・ヨー・加速度・角速度が取れる
  - ただし**磁力計が無いのでヨーはドリフトする**。絶対方位はスマホのコンパスから取る
- **0.0.13 でナビページが増えた** — `sendNaviLargeImage` はもう少し大きい画像（上流サンプルは 240x240）を送れる
- **0.0.14 は破壊的** — ファームが対応していない `enterAIPage` / `enterMeetingPage` / `enterNotificationPage` が撤去された
- **0.1.1 / 0.2.0 でテキストの置き場が広がった** — 分割レイアウト（`sendLayout`）と
  **576×360 の自由配置キャンバス**（`sendCanvas`）。送るだけで画面が切り替わる
- **0.3.0 でグラスのマイクが使いやすくなった** — `startMicStreaming` / `micAudio` で
  **PCM16 / 16kHz / モノラル**が流れる（Opus のデコードは SDK 側）
- **0.4.0 でキャンバスに画像が置ける** — `sendCanvasImage`。**星図とラベルを同じ画面に出せる**
  - テキストは画像の手前に描かれる。ナビ表示中は使えない・`FEATURE_VERSION 2.2.0` 以上
  - **画像バッファは 380,000 バイトまで。** 576×360 は画素だけで 414,720 になるので入らない
- **0.5.0 は破壊的** — アプリ本体に実装が無いメソッドが撤去された
  （`sendMeeting` / `sendAIContent` ほか、**電源・リモコンのイベントリスナー4つ**）
- **0.6.0 でキャンバス画像が複数枚になった** — `sendCanvasImage` に `id` が増えて **8 枚まで**。
  `removeCanvasImage` で 1 枚ずつ消せる。**0.5.0 までとは互換が無い**
  - あわせて **SDK が分割送信を直列化**し、続けて送ってもチャンクが混ざらなくなった
- 上流 SDK は **0.6.0 まで取り込み済み**
- 詳細 → [AGENTS.md](AGENTS.md#グラスに何を出せるか)

## はじめかた

1. [CONTRIBUTING.md](CONTRIBUTING.md) を読む
2. GitHub PAT を設定する（**これが無いとビルドが通らない**）
3. Android 実機を用意する（BLE 必須。エミュレータでは動かない）

```bash
cd samples/kmp
./gradlew :app:installDebug
```

## リポジトリの歩き方

| パス | 中身 |
|---|---|
| [AGENTS.md](AGENTS.md) | 技術的な前提・SDK の制約・未決定事項。AI エージェント向けだが人間が読んでもよい |
| [CONTRIBUTING.md](CONTRIBUTING.md) | 環境構築、進め方、コミット規約、仕様書の置き場所、困ったとき |
| `samples/kmp/app/` | team-e の実装ベース（Kotlin + Compose） |
| `samples/kmp/snippets/` | ドキュメント用のコード例。アプリではない |
| `samples/flutter/` | Flutter からの利用サンプル。team-e では使わない |
| `docs/` | SDK のドキュメントサイト（公開はせず手元で読む） |

## SDK について

- このアプリは **Sabera App SDK**（`jp.jig.sabera.app.sdk:sabera-app-core`）の上に作る
- 上流リポジトリ → [jig-SABERA/sabera-sdk](https://github.com/jig-SABERA/sabera-sdk)

上流が公開しているドキュメントサイト → **<https://jig-sabera.github.io/sabera-sdk/>**
（このリポジトリの `docs/` をビルドしたもの）

手元のファイル（0.6.0 時点）：

- [Getting Started](docs/getting-started.md) — セットアップと接続の流れ
- [API リファレンス](docs/api/) — 公開 API の一覧
- [メソッドの追加履歴](docs/api-history.md) — どのメソッドがどのバージョンから使えるか
- [GitHub PAT の作り方](docs/github-pat.md) — SDK 取得に必要な認証情報

### SDK の取得設定

SDK は private な GitHub Packages（`jig-SABERA/sabera-sdk-packages`）で配布されている。

Android — `~/.gradle/gradle.properties` に `read:packages` スコープの PAT を書く：

```properties
GitHubPackagesUsername=<GitHubのユーザー名>
GitHubPackagesPassword=<read:packages を持つ PAT>
```

iOS — Swift Package Manager で取得する。XCFramework の実体は GitHub Packages にあり、
**SPM は Authorization ヘッダを付けられない**ため `~/.netrc` に認証情報が要る：

```
machine maven.pkg.github.com
  login <GitHubのユーザー名>
  password <read:packages を持つ PAT>
```

## ライセンス

- このリポジトリのサンプル・ラッパーコードは [Apache License 2.0](LICENSE)
- **SDK 本体（`jp.jig.sabera.app.sdk:*`）はこのライセンスの対象外**。GitHub Packages から配布されるバイナリで、利用には別途 SDK 利用規約が適用される

| 対象 | ライセンス |
|---|---|
| このリポジトリのサンプル・ラッパーコード | Apache License 2.0 |
| Sabera App SDK 本体（AAR / XCFramework） | SDK 利用規約 |
