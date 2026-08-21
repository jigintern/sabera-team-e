#!/usr/bin/env python3
"""観測ログから、実機で確かめたい数字だけを抜き出す。

見たいのは 6 つ。
- 加速度軸が自動判定で確定したか（確定するまでロール追従は使えない）
- ロールの符号が正しいか（反転していると追従を入れたぶんずれが 2 倍になる）
- 画角に何を使っていたか（実測を入れたか、仮の値のままか）
- 天体アライメントの残差（±3° に入っていれば星図を空に重ねられる）
- ドリフト補正が効き続けているか（生のヨーは 44°/分 流れる）
- 失敗した行（黙って落ちているものを見つける）
"""
from __future__ import annotations

import re
import sys
from pathlib import Path


def main() -> int:
    if len(sys.argv) != 2:
        print("usage: summarize-session-log.py <log>", file=sys.stderr)
        return 2
    lines = Path(sys.argv[1]).read_text(encoding="utf-8", errors="replace").splitlines()

    axis = [l for l in lines if "加速度軸" in l]
    axis_fixed = [l for l in axis if "確定" in l]
    roll_sign = [l for l in lines if "ロールの符号" in l]
    selfcheck = [l for l in lines if "自己診断" in l]
    calibration = [l for l in lines if l.startswith(tuple()) or "方位合わせ=" in l]
    residuals = [(l, m.group(1)) for l in lines for m in [re.search(r"残差=([0-9.]+)°", l)] if m]
    drift = [l for l in lines if "ドリフト監視 経過" in l]
    sends = [l for l in lines if " 送信 " in l or l.split("  ")[-1].startswith("送信")]
    failures = [l for l in lines if "失敗" in l]
    rejected = [l for l in lines if "根拠のない解説文を除外" in l]
    bodies = [l for l in selfcheck if "天体=" in l and "天体=なし" not in l]

    def show(title: str, rows: list[str], limit: int = 3) -> None:
        print(f"\n## {title}（{len(rows)} 行）")
        if not rows:
            print("  なし")
            return
        for row in rows[-limit:]:
            print(f"  {row}")

    print("=" * 72)
    print(f"行数 {len(lines)}  期間 {lines[0][:14] if lines else '?'} 〜 {lines[-1][:14] if lines else '?'}")

    print("\n## 加速度軸（ロール追従を使えるか）")
    if axis_fixed:
        print(f"  確定: {axis_fixed[-1]}")
    elif axis:
        print(f"  未確定: {axis[-1]}")
        print("  → 空を 20° 以上見上げ下ろしすると決まる")
    else:
        print("  記録なし（観測画面に入っていない）")

    print("\n## ロールの符号")
    decided = [l for l in roll_sign if "判定中" not in l]
    if decided:
        print(f"  {decided[-1]}")
        if "反転" in decided[-1]:
            print("  → 追従を切る。AccelBasis の向きを直すまで入れない")
    elif roll_sign:
        print(f"  {roll_sign[-1]}")
        print("  → 首を左右に 15° ほど 3 往復ゆっくり傾けると決まる")
    else:
        print("  記録なし（加速度軸が確定していないか、首を傾けていない）")

    print("\n## 画角")
    fov = [m.group(0) for l in selfcheck for m in [re.search(r"画角=[0-9]+°\((実測|仮)\)", l)] if m]
    if fov:
        print(f"  最後の値: {fov[-1]}")
        if "(仮)" in fov[-1]:
            print("  → 壁で測った値をスライダーに入れると視野端が合う")
    else:
        print("  記録なし")

    print("\n## 天体アライメントの残差")
    if residuals:
        for line, value in residuals[-3:]:
            verdict = "星に重なる" if float(value) <= 1.0 else ("重ねられる" if float(value) <= 3.0 else "足りない")
            print(f"  {value}° （{verdict}）  {line}")
    else:
        print("  なし（段階 2 をまだ通していない）")

    if drift:
        print("\n## ドリフト補正")
        for line in drift[-2:]:
            print(f"  {line}")
        rates = [m.group(1) for l in drift for m in [re.search(r"方位=[-0-9.]+°\(([-+0-9.]+)°/分\)", l)] if m]
        if rates:
            worst = max(rates, key=lambda r: abs(float(r)))
            print(f"  → 補正後の最大ドリフト {worst}°/分（生のヨーは -44°/分 が正常）")

    show("視野に入った月・惑星", bodies, limit=3)
    show("送信", sends, limit=2)
    show("落とした解説文（根拠なし）", rejected, limit=3)
    show("失敗した行", failures, limit=5)
    print()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
