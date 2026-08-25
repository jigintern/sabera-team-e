#!/usr/bin/env bash
#
# res/raw の BGM を作り直す。
#
# 元曲は incompetech（Kevin MacLeod・CC BY 4.0）から落とす。**ネットワークが要る。**
# 落としたものは cache に残すので、2 回目からは切り出しだけになる。
#
#   tools/build-bgm.sh              # 6 曲すべて
#   tools/build-bgm.sh bgm_ambiment # 1 曲だけ
#
# 切り出し位置（OFFSET）は tools/pick-bgm-window.py が選んだもの。
# **耳で選んでいない。** ループの継ぎ目になる 2 か所の音量差がいちばん小さく、
# 区間の揺れが少ないところを数字で選んでいる。差し替えるときは同じ手順で出し直す。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT_DIR="$ROOT/samples/kmp/app/src/main/res/raw"
CACHE="${BGM_CACHE:-${TMPDIR:-/tmp}/sabera-bgm-cache}"
BASE="https://incompetech.com/music/royalty-free/mp3-royaltyfree"

# 出来上がりの長さと、継ぎ目に使う長さ
BODY=120
XFADE=6

# **音量を揃える目標。** 同じ場面の曲どうしがクロスフェードで入れ替わるので、
# 揃っていないと曲が変わるたびに音量が動いたように聞こえる
TARGET_LUFS=-23.0

# 出力名 | 曲名 | 切り出しの開始位置（秒）
TRACKS=(
  "bgm_silver_blue_light|Silver Blue Light|60"
  "bgm_light_awash|Light Awash|645"
  "bgm_fluidscape|Fluidscape|300"
  "bgm_ambiment|Ambiment|414"
  "bgm_drone_in_d|Drone in D|163"
  "bgm_concentration|Concentration|1559"
)

urlencode() {
  python3 -c 'import urllib.parse,sys; print(urllib.parse.quote(sys.argv[1]))' "$1"
}

# 元曲を落とす。**すでにあれば触らない**（1 曲 40〜70MB ある）
fetch() {
  local title="$1" dest="$CACHE/$title.mp3"
  if [ ! -s "$dest" ]; then
    echo "  落とす: $title"
    curl -sSf -L --max-time 900 -o "$dest" "$BASE/$(urlencode "$title.mp3")"
  fi
  echo "$dest"
}

# 積分ラウドネスを測る
measure() {
  ffmpeg -hide_banner -nostats -i "$1" -af ebur128 -f null - 2>&1 |
    awk '/Integrated loudness/{f=1} f&&/I:/{print $2; exit}'
}

build() {
  local name="$1" title="$2" offset="$3"
  local src cut work
  src="$(fetch "$title")"
  work="$(mktemp -d)"
  trap 'rm -rf "$work"' RETURN

  # 切り出しは BODY + XFADE。**余分の XFADE が継ぎ目の材料になる**
  ffmpeg -hide_banner -v error -y -ss "$offset" -t "$((BODY + XFADE))" -i "$src" \
    -ac 2 -ar 48000 -c:a pcm_s16le "$work/seg.wav"

  # 末尾の XFADE 秒を先頭の XFADE 秒に重ねて、BODY 秒のループを作る。
  # こうすると BODY 秒目の続きが 0 秒目に溶け込むので、繰り返しても継ぎ目が出ない。
  # 等パワーで重ねる（qsin）。線形だと重なりの真ん中で音量が凹む
  ffmpeg -hide_banner -v error -y -i "$work/seg.wav" -filter_complex "
    [0:a]asplit=3[a][b][c];
    [a]atrim=0:$XFADE,asetpts=PTS-STARTPTS,afade=t=in:st=0:d=$XFADE:curve=qsin[head];
    [b]atrim=$BODY:$((BODY + XFADE)),asetpts=PTS-STARTPTS,
       afade=t=out:st=0:d=$XFADE:curve=qsin[tail];
    [head][tail]amix=inputs=2:normalize=0[seam];
    [c]atrim=$XFADE:$BODY,asetpts=PTS-STARTPTS[rest];
    [seam][rest]concat=n=2:v=0:a=1[body]" \
    -map "[body]" -c:a pcm_s16le "$work/loop.wav"

  # **音量は掛け算だけで揃える。** loudnorm の動的な均しはアンビエントの
  # ゆっくりした起伏を潰してしまうので、測って持ち上げるだけにする
  local measured gain
  measured="$(measure "$work/loop.wav")"
  gain="$(python3 -c "print(f'{$TARGET_LUFS - ($measured):.2f}')")"
  ffmpeg -hide_banner -v error -y -i "$work/loop.wav" -af "volume=${gain}dB" \
    -c:a libopus -b:a 122k -vbr constrained -ar 48000 -ac 2 "$OUT_DIR/$name.ogg"

  printf "  %-24s %s (%+.1f 秒目から・%s dB)\n" "$name.ogg" "$title" "$offset" "$gain"
}

mkdir -p "$CACHE"
for entry in "${TRACKS[@]}"; do
  IFS='|' read -r name title offset <<<"$entry"
  if [ "$#" -gt 0 ] && [[ ! " $* " == *" $name "* ]]; then continue; fi
  echo "$title"
  build "$name" "$title" "$offset"
done
