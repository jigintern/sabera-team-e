#!/usr/bin/env python3
"""大三角などの星の結びと、天の川の帯を data/asterisms.json に書き出す。

**初心者が空で最初に見つけるのは星座ではなく「大三角」。** 星座線は 88 星座ぶんあって
どれがどれか分からないが、**夏の大三角のような結びは 3 点で覚えられる**。
天の川も「そこに何かある」と分かるだけで空の見え方が変わる。

- 結びは **HIP 番号**で持つ。星表と同じ星を指すので、位置は歳差込みで一致する
- 天の川は**銀河座標の b = ±10° を赤道座標へ変換した曲線**（J2000）。
  歳差 0.36° は帯の幅 20° に対して無視できるので、そのまま使う
- どちらも `python3 tools/build-asterisms.py` で作り直す
"""
from __future__ import annotations

import json
import math
from pathlib import Path

# 名前 / HIP の並び / 閉じるか
ASTERISMS: list[tuple[str, list[int], bool]] = [
    ("夏の大三角", [91262, 102098, 97649], True),   # ベガ・デネブ・アルタイル
    ("冬の大三角", [27989, 32349, 37279], True),    # ベテルギウス・シリウス・プロキオン
    ("春の大三角", [69673, 65474, 57632], True),    # アークトゥルス・スピカ・デネボラ
    ("北斗七星", [54061, 53910, 58001, 59774, 62956, 65378, 67301], False),
]

# 銀河北極（J2000）と、天の北極の銀河経度
NGP_RA = 192.85948
NGP_DEC = 27.12825
NCP_L = 122.93192

# 帯の縁。±10° にすると視野 35° に対して「帯」として読める
EDGES = (-10.0, 10.0)
STEP_DEG = 4.0


def galactic_to_equatorial(l_deg: float, b_deg: float) -> tuple[float, float]:
    l = math.radians(l_deg)
    b = math.radians(b_deg)
    dec_ngp = math.radians(NGP_DEC)
    l_ncp = math.radians(NCP_L)
    sin_dec = math.sin(dec_ngp) * math.sin(b) + math.cos(dec_ngp) * math.cos(b) * math.cos(l_ncp - l)
    dec = math.asin(max(-1.0, min(1.0, sin_dec)))
    y = math.cos(b) * math.sin(l_ncp - l)
    x = math.cos(dec_ngp) * math.sin(b) - math.sin(dec_ngp) * math.cos(b) * math.cos(l_ncp - l)
    ra = math.degrees(math.atan2(y, x)) + NGP_RA
    return (ra % 360.0 + 360.0) % 360.0, math.degrees(dec)


def milky_way() -> list[list[list[float]]]:
    """帯の縁を折れ線で返す。赤経が 0/360 をまたぐところで切る（線が空を横断しないように）"""
    edges = []
    for b in EDGES:
        current: list[list[float]] = []
        previous_ra = None
        steps = int(360.0 / STEP_DEG)
        for i in range(steps + 1):
            ra, dec = galactic_to_equatorial(i * STEP_DEG, b)
            if previous_ra is not None and abs(ra - previous_ra) > 180.0:
                if len(current) > 1:
                    edges.append(current)
                current = []
            current.append([round(ra, 3), round(dec, 3)])
            previous_ra = ra
        if len(current) > 1:
            edges.append(current)
    return edges


def main() -> int:
    root = Path(__file__).resolve().parent.parent
    stars = {s[0] for s in json.loads((root / "data" / "stars.json").read_text())["stars"]}
    for name, hips, _ in ASTERISMS:
        missing = [h for h in hips if h not in stars]
        if missing:
            raise SystemExit(f"{name}: 星表に無い HIP {missing}")

    band = milky_way()
    payload = {
        "note": "大三角などの星の結び（HIP 番号）と、天の川の帯の縁（銀河座標 b=±10° の J2000 赤道座標）",
        "asterisms": [
            {"nameJa": name, "hips": hips, "closed": closed} for name, hips, closed in ASTERISMS
        ],
        "milkyWay": band,
    }
    out = root / "data" / "asterisms.json"
    out.write_text(json.dumps(payload, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    points = sum(len(e) for e in band)
    print(f"ok: {out.relative_to(root)} に 結び {len(ASTERISMS)} 個・天の川 {len(band)} 本 {points} 点")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
