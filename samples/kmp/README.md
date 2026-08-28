# team-e のアプリ

- `app/` が team-e のアプリ本体で、唯一の Gradle モジュール
- Kotlin + Jetpack Compose から Sabera App SDK を直接呼ぶ
- SDK のコード例と API リファレンスは[公開ドキュメント](https://jig-sabera.github.io/sabera-sdk/)を見る
- **Android 実機のみ。** iOS は上流でも SDK 0.0.10 のままなので追わない

## 前提

- Android Studio / JDK 17
- **Android 実機**（BLE 必須。エミュレータ不可）
- SDK 取得用の GitHub PAT → [CONTRIBUTING.md](../../CONTRIBUTING.md)

## 動かす

```bash
cd samples/kmp
./gradlew :app:installDebug              # 実機にインストール
./gradlew :app:testDebugUnitTest         # JVM テスト
./gradlew :app:assembleDebug             # Debug APK を作る
```

## 参照先

- 仕様 — [docs/team-e/00_index.md](../../docs/team-e/00_index.md)
- 禁止事項と規約 — [AGENTS.md](../../AGENTS.md)

## ライセンス

- このサンプルコード — [Apache License 2.0](../../LICENSE)
- SDK 本体は対象外で、別途 SDK 利用規約が適用される
