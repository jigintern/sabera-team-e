#!/usr/bin/env bash
# 実機の観測ログを取り出して要点だけ並べる。
#
# 夜の屋外では画面を見ていられないので、アプリは検証に必要な数字を
# 端末内のファイル（filesDir/starmap-log.txt）へ溜め続ける。
# 帰ってきて USB を差したら、このスクリプトを 1 回叩けば全部手元に来る。
#
#   tools/pull-session-log.sh            # 取り出して要約を出す
#   tools/pull-session-log.sh --logcat   # 直近の logcat も一緒に取る
#   tools/pull-session-log.sh --clear    # 取り出したあと端末側を空にする（計測を仕切り直す）
#
# debuggable なビルドなら run-as でアプリ専用領域を読めるので、root は要らない。
set -euo pipefail

PACKAGE="jp.jig.sabera.app.sample.kmp"
REMOTE="files/starmap-log.txt"
OUT_DIR="${OUT_DIR:-logs}"

ADB="${ADB:-}"
if [ -z "$ADB" ]; then
    if command -v adb >/dev/null 2>&1; then
        ADB="$(command -v adb)"
    elif [ -x "$HOME/Library/Android/sdk/platform-tools/adb" ]; then
        ADB="$HOME/Library/Android/sdk/platform-tools/adb"
    else
        echo "adb が見つからない。ADB=/path/to/adb を指定する" >&2
        exit 1
    fi
fi

WITH_LOGCAT=0
CLEAR_AFTER=0
for arg in "$@"; do
    case "$arg" in
        --logcat) WITH_LOGCAT=1 ;;
        --clear) CLEAR_AFTER=1 ;;
        *) echo "知らない引数: $arg" >&2; exit 1 ;;
    esac
done

if [ -z "$("$ADB" devices | sed -n '2,$p' | grep -w device || true)" ]; then
    echo "実機が繋がっていない（adb devices が空）。USB を差して開発者向けオプションを許可する" >&2
    exit 1
fi

mkdir -p "$OUT_DIR"
STAMP="$(date +%Y%m%d-%H%M%S)"
OUT="$OUT_DIR/session-$STAMP.txt"

if ! "$ADB" exec-out run-as "$PACKAGE" cat "$REMOTE" > "$OUT" 2>/dev/null; then
    rm -f "$OUT"
    echo "ログが読めない。アプリを 1 回起動して観測画面まで進んでいるか確認する" >&2
    exit 1
fi

if [ ! -s "$OUT" ]; then
    rm -f "$OUT"
    echo "ログが空。観測画面に入ると溜まりはじめる" >&2
    exit 1
fi

if [ "$WITH_LOGCAT" = 1 ]; then
    LOGCAT="$OUT_DIR/logcat-$STAMP.txt"
    "$ADB" logcat -d -v time -s StarMap:V Narrator:V CloudVoice:V > "$LOGCAT" || true
    echo "logcat: $LOGCAT ($(wc -l < "$LOGCAT" | tr -d ' ') 行)"
fi

echo "ログ: $OUT ($(wc -l < "$OUT" | tr -d ' ') 行)"
python3 tools/summarize-session-log.py "$OUT"

if [ "$CLEAR_AFTER" = 1 ]; then
    # アプリ側の「記録を消す」と同じ効果。次の計測が前回と混ざらないようにする
    "$ADB" shell run-as "$PACKAGE" rm -f "$REMOTE"
    echo "端末側のログを消した"
fi
