#!/usr/bin/env python3
"""主な流星群を data/meteor-showers.json に書き出す。

**「今夜がピーク」は、見に行く理由そのもの。** 星座は一年中どれかが見えているが、
流星群は日付で決まっていて、逃すと次は一年後になる。それなのにアプリのどこにも出ていなかった。

- **日付だけで決まる**ので通信は要らない（同梱の星座解説と同じ理由）。
  星を見に行く場所ほど電波が届かない
- 放射点は **J2000 の赤経・赤緯**。星表と同じ座標系なので、そのまま星図に印を焼ける
- **放射点は活動期間の中で数度動く**が、放射点そのものが数度の広がりを持つうえ、
  視野は 35° あるので極大日の値で固定する
- 出現数（ZHR）は**理想条件の値**。実際には空の暗さと放射点の高度で減る。
  アプリ側では「空が暗ければ」と断って使う
- 値の出どころは IMO（国際流星機構）の年間カレンダー。極大は年によって半日ほど動く

`python3 tools/build-meteor-showers.py` で作り直す。
"""
from __future__ import annotations

import json
from pathlib import Path

# 名前 / 極大(月,日) / 活動期間(開始 月,日)(終了 月,日) / ZHR / 放射点(赤経,赤緯)
SHOWERS: list[tuple[str, tuple[int, int], tuple[int, int], tuple[int, int], int, float, float]] = [
    ("しぶんぎ座流星群", (1, 4), (12, 28), (1, 12), 110, 230.0, 49.0),
    ("こと座流星群", (4, 22), (4, 16), (4, 25), 18, 271.0, 34.0),
    ("みずがめ座エータ流星群", (5, 6), (4, 19), (5, 28), 50, 338.0, -1.0),
    ("みずがめ座デルタ南流星群", (7, 30), (7, 12), (8, 23), 25, 340.0, -16.0),
    ("ペルセウス座流星群", (8, 12), (7, 17), (8, 24), 100, 48.0, 58.0),
    ("りゅう座流星群", (10, 8), (10, 6), (10, 10), 10, 262.0, 54.0),
    ("オリオン座流星群", (10, 21), (10, 2), (11, 7), 20, 95.0, 16.0),
    ("おうし座南流星群", (11, 5), (9, 10), (11, 20), 5, 52.0, 15.0),
    ("しし座流星群", (11, 17), (11, 6), (11, 30), 15, 152.0, 22.0),
    ("ふたご座流星群", (12, 14), (12, 4), (12, 17), 150, 112.0, 33.0),
    ("こぐま座流星群", (12, 22), (12, 17), (12, 26), 10, 217.0, 76.0),
]

# 極大の前後何日を「極大のころ」として扱うか。アプリ側の言い方が変わる
PEAK_WINDOW_DAYS = 2


def day_of_year(month: int, day: int) -> int:
    """うるう年を無視した通日。**流星群の日付は年で半日しか動かない**ので 1 日の差は問題にならない"""
    lengths = [31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31]
    return sum(lengths[: month - 1]) + day


def main() -> int:
    root = Path(__file__).resolve().parent.parent

    names = [s[0] for s in SHOWERS]
    if len(set(names)) != len(names):
        raise SystemExit(f"名前が重複している: {names}")
    for name, peak, start, end, zhr, ra, dec in SHOWERS:
        if not 0.0 <= ra < 360.0 or not -90.0 <= dec <= 90.0:
            raise SystemExit(f"{name}: 放射点が座標の外 ra={ra} dec={dec}")
        if zhr <= 0:
            raise SystemExit(f"{name}: ZHR が 0 以下")
        # 年をまたぐ群（しぶんぎ座）があるので、通日の大小では期間を検査できない。
        # **極大が期間の中にあるか**だけを見る
        peak_day = day_of_year(*peak)
        first, last = day_of_year(*start), day_of_year(*end)
        inside = first <= peak_day <= last if first <= last else (peak_day >= first or peak_day <= last)
        if not inside:
            raise SystemExit(f"{name}: 極大が活動期間の外にある")

    payload = {
        "note": (
            "主な流星群。放射点は J2000 の赤経・赤緯で、極大日の値に固定してある"
            "（活動期間中に数度動くが、放射点の広がりと視野 35° に対して無視できる）。"
            "zhr は理想条件の 1 時間あたりの出現数"
        ),
        "peakWindowDays": PEAK_WINDOW_DAYS,
        "showers": [
            {
                "nameJa": name,
                "peakMonth": peak[0],
                "peakDay": peak[1],
                "startMonth": start[0],
                "startDay": start[1],
                "endMonth": end[0],
                "endDay": end[1],
                "zhr": zhr,
                "raDeg": ra,
                "decDeg": dec,
            }
            for name, peak, start, end, zhr, ra, dec in SHOWERS
        ],
    }
    out = root / "data" / "meteor-showers.json"
    out.write_text(json.dumps(payload, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    print(f"ok: {out.relative_to(root)} に {len(SHOWERS)} 群を書き出した")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
