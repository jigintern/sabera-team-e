#!/usr/bin/env python3
"""CelesTrak から TLE を取って data/ に置く。

    python3 tools/build-satellites.py           # 足りないものだけ取る
    python3 tools/build-satellites.py --force   # 期限内でも取り直す

CelesTrak は非営利で運営されていて、次の作法がある。守らないと弾かれる。

  - データの更新は 2 時間ごと。それより短い間隔で取り直しても意味がない
  - 1 IP あたり 1 日 100MB まで
  - starlink グループは「更新期間ごとに 1 回まで」。繰り返すと HTTP 403

なので取ったものは tools/.cache/ に置き、2 時間以内なら再取得しない。
"""

import argparse
import json
import sys
import time
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
LIST = ROOT / "tools" / "satellites-ja.json"
CACHE = ROOT / "tools" / ".cache"
DATA = ROOT / "data"

BASE = "https://celestrak.org/NORAD/elements/gp.php?GROUP={group}&FORMAT=tle"
BY_CATNR = "https://celestrak.org/NORAD/elements/gp.php?CATNR={norad}&FORMAT=tle"
USER_AGENT = "sabera-team-e/1.0 (https://github.com/jigintern/sabera-team-e)"

# CelesTrak の更新間隔。これより新しいキャッシュは使い回す
FRESH_SECONDS = 2 * 60 * 60


def fetch_group(group: str, force: bool) -> str:
    CACHE.mkdir(parents=True, exist_ok=True)
    cached = CACHE / f"{group}.tle"
    if cached.exists() and not force:
        age = time.time() - cached.stat().st_mtime
        if age < FRESH_SECONDS:
            print(f"  {group}: キャッシュを使う（{int(age / 60)} 分前）")
            return cached.read_text(encoding="utf-8")
    print(f"  {group}: 取得中…")
    request = urllib.request.Request(BASE.format(group=group), headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=60) as response:
        text = response.read().decode("utf-8")
    if "Invalid query" in text or len(text) < 100:
        sys.exit(f"{group} の取得に失敗した: {text[:200]}")
    cached.write_text(text, encoding="utf-8")
    return text


def fetch_one(norad: str, force: bool) -> str:
    """グループに居ない衛星を 1 機だけ取る。

    しきさい・しずく・いぶき2号のように、どの小分類にも入っていないものがある。
    グループの当てが外れても直せるよう、番号で直接引く逃げ道を用意しておく。
    """
    CACHE.mkdir(parents=True, exist_ok=True)
    cached = CACHE / f"catnr-{norad}.tle"
    if cached.exists() and not force and time.time() - cached.stat().st_mtime < FRESH_SECONDS:
        return cached.read_text(encoding="utf-8")
    request = urllib.request.Request(BY_CATNR.format(norad=norad), headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=60) as response:
        text = response.read().decode("utf-8")
    if "No GP data found" in text or len(text) < 100:
        return ""
    cached.write_text(text, encoding="utf-8")
    return text


def parse(text: str) -> dict:
    """NORAD 番号 → (名前, 1行目, 2行目)"""
    lines = [l.rstrip() for l in text.splitlines() if l.strip()]
    found = {}
    i = 0
    while i + 2 < len(lines) + 1:
        if i + 2 < len(lines) and lines[i + 1].startswith("1 ") and lines[i + 2].startswith("2 "):
            norad = lines[i + 1][2:7].strip()
            found[norad] = (lines[i].strip(), lines[i + 1], lines[i + 2])
            i += 3
        elif lines[i].startswith("1 ") and i + 1 < len(lines) and lines[i + 1].startswith("2 "):
            norad = lines[i][2:7].strip()
            found[norad] = ("", lines[i], lines[i + 1])
            i += 2
        else:
            i += 1
    return found


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--force", action="store_true", help="キャッシュが新しくても取り直す")
    parser.add_argument("--skip-starlink", action="store_true", help="1.8MB の starlink を取らない")
    args = parser.parse_args()

    spec = json.loads(LIST.read_text(encoding="utf-8"))
    names = spec["names"]

    print("名前つきの衛星を集める")
    collected = {}
    for group, wanted in spec["groups"].items():
        found = parse(fetch_group(group, args.force))
        for norad in wanted:
            if norad in found:
                collected[norad] = found[norad]
            else:
                print(f"    ! {norad}（{names[norad]['ja']}）が {group} に居ない")

    missing = [n for n in names if n not in collected]
    if missing:
        print("グループに居なかったものを番号で引く")
        for norad in missing:
            found = parse(fetch_one(norad, args.force))
            if norad in found:
                collected[norad] = found[norad]
                print(f"  {norad}（{names[norad]['ja']}）: 取れた")
            else:
                print(f"  ! {norad}（{names[norad]['ja']}）: 取れない。番号が違うか、もう軌道に居ない")

    DATA.mkdir(exist_ok=True)
    out = []
    for norad, (_, line1, line2) in collected.items():
        # 名前の行は日本語にする。アプリ側で対応表を持たずに済む
        out.append(names[norad]["ja"])
        out.append(line1)
        out.append(line2)
    (DATA / "satellites.tle").write_text("\n".join(out) + "\n", encoding="utf-8")
    print(f"data/satellites.tle: {len(collected)} 機")

    if not args.skip_starlink:
        print("スターリンクを集める")
        starlink = fetch_group("starlink", args.force)
        (DATA / "starlink.tle").write_text(starlink, encoding="utf-8")
        count = sum(1 for l in starlink.splitlines() if l.startswith("1 "))
        size = len(starlink.encode("utf-8"))
        print(f"data/starlink.tle: {count} 機 / {size:,} バイト")

    if args.skip_starlink:
        # starlink.tle は前回のまま。取得日を今に書き換えると、アプリが古さを見誤る
        print("starlink を取らなかったので data/satellites-fetched.txt はそのまま")
    else:
        print("取得日を data/satellites-fetched.txt に残す")
        (DATA / "satellites-fetched.txt").write_text(
            time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()) + "\n", encoding="utf-8"
        )


if __name__ == "__main__":
    main()
