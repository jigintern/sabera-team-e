#!/usr/bin/env python3
"""観測ログから、実機で確かめたい数字だけを抜き出す。

見たいのは 3 つ。
- どの精度で始めた観測か（方位合わせのばらつき。±20° に入っていれば星座は当たる）
- ドリフト補正が効き続けているか（生のヨーは 44°/分 流れる）
- 失敗した行（黙って落ちているものを見つける）

送信の行には視野に入っていた月・惑星も並ぶので、星図と AI 解説が
同じものを見ているかはそこで読む。
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

    calibration = [l for l in lines if "方位合わせ=" in l]
    drift = [l for l in lines if "ドリフト監視 経過" in l]
    sends = [l for l in lines if " 送信 " in l or l.split("  ")[-1].startswith("送信")]
    failures = [l for l in lines if "失敗" in l]
    rejected = [l for l in lines if "根拠のない解説文を除外" in l]

    def show(title: str, rows: list[str], limit: int = 3) -> None:
        print(f"\n## {title}（{len(rows)} 行）")
        if not rows:
            print("  なし")
            return
        for row in rows[-limit:]:
            print(f"  {row}")

    print("=" * 72)
    print(f"行数 {len(lines)}  期間 {lines[0][:14] if lines else '?'} 〜 {lines[-1][:14] if lines else '?'}")

    print("\n## 方位合わせ")
    if calibration:
        for line in calibration[-3:]:
            print(f"  {line}")
    else:
        print("  なし（方位を合わせずに観測画面へ入っている）")

    if drift:
        print("\n## ドリフト補正")
        for line in drift[-2:]:
            print(f"  {line}")
        # 補正後のヨー。**古いログは「方位=」という名前だった**ので両方読む
        rates = [m.group(1) for l in drift
                 for m in [re.search(r"(?:補正yaw|方位)=[-0-9.]+°\(([-+0-9.]+)°/分\)", l)] if m]
        if rates:
            worst = max(rates, key=lambda r: abs(float(r)))
            print(f"  → 補正後の最大ドリフト {worst}°/分（生のヨーは -44°/分 が正常）")

    show("送信", sends, limit=2)
    show("落とした解説文（根拠なし）", rejected, limit=3)
    show("失敗した行", failures, limit=5)
    print()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
