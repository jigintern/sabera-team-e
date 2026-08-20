# team-e のアプリ

**`app/` が team-e のアプリ本体。** Kotlin + Jetpack Compose から Sabera App SDK を直接呼ぶ。

| モジュール | 中身 |
|---|---|
| **`app/`** | **アプリ本体。ここを書き換えて育てる** |
| `snippets/` | ドキュメント用のコード例。**アプリではない**（壊すと CI が落ちる） |

> **Android 実機のみ。** iOS は上流でも SDK 0.0.10 のまま追従していないので、team-e では扱わない。

## 前提

- Android Studio / JDK 17
- **Android 実機**（BLE 必須。エミュレータ不可）
- SDK 取得用の GitHub PAT → [CONTRIBUTING.md](../../CONTRIBUTING.md)

## 動かす

```bash
cd samples/kmp
./gradlew :app:installDebug              # 実機にインストール
./gradlew :app:testDebugUnitTest         # JVM テスト
./gradlew :snippets:compileDebugKotlin :snippets:ktlintCheck   # コード例の検証（CI に無いので手元で）
```

仕様は [docs/team-e/](../../docs/team-e/)、技術的な前提は [AGENTS.md](../../AGENTS.md)。

## ライセンス

このサンプルコードは [Apache License 2.0](../../LICENSE)。
SDK 本体は対象外で、別途 SDK 利用規約が適用される。
