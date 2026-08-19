#!/usr/bin/env python3
"""data/ の星表を tools/simulator/template.html に埋め込んで、単体で開ける HTML を作る。

    python3 tools/build-simulator.py            # tools/simulator/index.html を作り直す
    python3 tools/build-simulator.py --check    # 生成物が最新か確かめる

出力は自己完結（外部参照は Google Fonts のみ）なので、ファイルをダブルクリックすれば
ブラウザで開く。ローカルサーバーは要らない。
"""

import argparse
import json
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
TEMPLATE = ROOT / "tools" / "simulator" / "template.html"
OUTPUT = ROOT / "tools" / "simulator" / "index.html"
SLOTS = {"__STARS__": "stars.json", "__CONS__": "constellations.json", "__BRIGHT__": "bright-stars.json"}


def build():
    html = TEMPLATE.read_text(encoding="utf-8")
    for slot, name in SLOTS.items():
        if slot not in html:
            sys.exit(f"テンプレートに {slot} が無い")
        data = json.loads((ROOT / "data" / name).read_text(encoding="utf-8"))
        html = html.replace(slot, json.dumps(data, ensure_ascii=False, separators=(",", ":")))
    return html


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true", help="生成物が最新かどうかだけ見る")
    args = ap.parse_args()

    html = build()
    if args.check:
        if not OUTPUT.exists() or OUTPUT.read_text(encoding="utf-8") != html:
            sys.exit("tools/simulator/index.html が古い。python3 tools/build-simulator.py を実行する")
        print("ok: tools/simulator/index.html は最新")
        return

    OUTPUT.write_text(html, encoding="utf-8")
    print(f"  {OUTPUT.relative_to(ROOT)}: {len(html.encode()):,} バイト")


if __name__ == "__main__":
    main()
