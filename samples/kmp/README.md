# team-e のアプリ

**`app/` が team-e のアプリ本体。** Kotlin + Jetpack Compose から Sabera App SDK を直接呼ぶ。

`app/` が唯一のGradleモジュール。上流SDKのコード例とAPIリファレンスは、
[公開ドキュメント](https://jig-sabera.github.io/sabera-sdk/)を参照する。

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
./gradlew :app:assembleDebug              # デバッグAPKを作る
```

仕様は [docs/team-e/index.md](../../docs/team-e/index.md)、技術的な前提は [AGENTS.md](../../AGENTS.md)。

## ライセンス

このサンプルコードは [Apache License 2.0](../../LICENSE)。
SDK 本体は対象外で、別途 SDK 利用規約が適用される。
