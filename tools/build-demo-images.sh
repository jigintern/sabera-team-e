#!/usr/bin/env bash
# デモ画像（docs/images/）を作り直す。**実機は要らない。**
#
#   tools/build-demo-images.sh
#
# - スマホ側 = アプリと同じ Compose を Robolectric で描いて撮る（phone-*.png）
# - グラス側 = アプリと同じ描画コードで中身を作り、AWT で絵にする（glass-*.png）
#
# **実機の写真ではない。** パネルのにじみ・実機のフォント・BLE の転送時間は出ない。
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root/samples/kmp"

./gradlew --quiet :app:testDebugUnitTest \
  --tests '*DocumentImagesTest*' \
  --tests '*PhoneScreenshotTest*'

cd "$root"
java -Djava.awt.headless=true tools/compose-glass-images.java

mkdir -p docs/images
cp samples/kmp/app/build/doc-images/phone/*.png docs/images/

ls docs/images/*.png | sed "s|^|$PWD/|"
