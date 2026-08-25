"""120 秒の切り出し位置を機械的に選ぶ。

**耳で選べないので、揺れの少ない区間を数字で選ぶ。** ループの継ぎ目は
「先頭の 6 秒」と「120 秒目からの 6 秒」を重ねて作るので、その 2 か所の
音量が近いことがいちばん効く。曲頭と曲尻は外す（立ち上がりと終わりが混ざる）。
"""
import re, subprocess, sys, statistics

BODY, XFADE, EDGE = 120, 6, 45

def rms_per_second(path):
    out = subprocess.run(
        ["ffmpeg", "-hide_banner", "-nostats", "-v", "error", "-i", path, "-af",
         "aresample=8000,aformat=channel_layouts=mono,asetnsamples=8000:p=0,"
         "astats=metadata=1:reset=1,ametadata=print:"
         "key=lavfi.astats.Overall.RMS_level:file=-", "-f", "null", "-"],
        capture_output=True, text=True).stdout
    xs = []
    for m in re.finditer(r"RMS_level=(-?[\d.]+|-inf)", out):
        xs.append(-90.0 if m.group(1) == "-inf" else float(m.group(1)))
    return xs

def pick(path):
    xs = rms_per_second(path)
    median = statistics.median(xs)
    best = None
    for start in range(EDGE, len(xs) - BODY - XFADE - EDGE):
        head = statistics.fmean(xs[start:start + XFADE])
        tail = statistics.fmean(xs[start + BODY:start + BODY + XFADE])
        body = xs[start:start + BODY + XFADE]
        # 継ぎ目の段差をいちばん重く、次に区間の揺れ、最後に曲全体からの外れ
        score = abs(head - tail) * 3 + statistics.pstdev(body) + abs(statistics.fmean(body) - median)
        if best is None or score < best[0]:
            best = (score, start, abs(head - tail), statistics.pstdev(body),
                    statistics.fmean(body))
    _, start, seam, spread, mean = best
    name = path.rsplit("/", 1)[-1].removesuffix(".mp3")
    print("%-14s offset=%4d s  seam=%.2f dB  spread=%.2f dB  mean=%.1f (median %.1f)"
          % (name, start, seam, spread, mean, median))
    return start

for p in sys.argv[1:]:
    pick(p)
