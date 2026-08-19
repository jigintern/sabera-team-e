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
  - ただし **196x196 / 3bit グレースケール（8階調）/ RLE 圧縮**という制約付き
  - エンコードは呼び出し側で実装する必要がある
- **グラスの姿勢・方位を取る API は無い**ので、方位はスマホ側センサーに依存する
- 上流 SDK は **0.0.11 まで取り込み済み**
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
| `docs/` | SDK のドキュメントサイト（GitHub Pages に自動デプロイ） |

## SDK について

- このアプリは **Sabera App SDK**（`jp.jig.sabera.app.sdk:sabera-app-core`）の上に作る
- 上流リポジトリ → [jig-SABERA/sabera-sdk](https://github.com/jig-SABERA/sabera-sdk)

ドキュメント（0.0.11 時点）：

- [Getting Started](docs/getting-started.md) — セットアップと接続の流れ
- [API リファレンス](docs/api/) — 公開 API の一覧
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
