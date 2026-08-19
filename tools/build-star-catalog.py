#!/usr/bin/env python3
"""d3-celestial の GeoJSON から、アプリに同梱する星表を作る。

出処は https://github.com/ofrohn/d3-celestial （BSD-3-Clause）。
元データは赤経を -180〜180 の経度に折り返しているので、ここで 0〜360 の赤経に戻す。
元期は J2000.0。

    python3 tools/build-star-catalog.py            # 取得して data/ を作り直す
    python3 tools/build-star-catalog.py --check    # 生成物が最新か確かめる（CI 用）
"""

import argparse
import json
import pathlib
import sys
import urllib.request

BASE = "https://raw.githubusercontent.com/ofrohn/d3-celestial/master/data"
SOURCES = ["stars.6.json", "constellations.lines.json", "starnames.json"]

# 196x196 に描く密度から決めた。5 等で FOV 40° の視野内に約 49 個
LIMIT_MAGNITUDE = 5.0
# キャリブレーションの基準にできる明るさ。この等級までは固有名を持たせる
BRIGHT_MAGNITUDE = 1.6

ROOT = pathlib.Path(__file__).resolve().parent.parent
CACHE = ROOT / "tools" / ".cache"
DATA = ROOT / "data"

ATTRIBUTION = {
    "source": "d3-celestial (https://github.com/ofrohn/d3-celestial)",
    "sourceLicense": "BSD-3-Clause, Copyright (c) 2015 Olaf Frohn",
    "originalCatalog": "XHIP: An Extended Hipparcos Compilation (Anderson & Francis 2012)",
    "epoch": "J2000.0",
    "generatedBy": "tools/build-star-catalog.py",
}


def fetch(name):
    """元データを取ってくる。一度取ったものは tools/.cache に置いて使い回す。"""
    CACHE.mkdir(parents=True, exist_ok=True)
    path = CACHE / name
    if not path.exists():
        print(f"  取得中: {name}")
        urllib.request.urlretrieve(f"{BASE}/{name}", path)
    return json.loads(path.read_text(encoding="utf-8"))


def to_ra(lon):
    """元データの経度（-180〜180）を赤経（0〜360）に戻す。"""
    return lon + 360.0 if lon < 0 else lon


def build_stars(raw):
    stars = []
    for f in raw["features"]:
        mag = f["properties"]["mag"]
        if mag > LIMIT_MAGNITUDE:
            continue
        lon, lat = f["geometry"]["coordinates"]
        stars.append([f["id"], round(to_ra(lon), 4), round(lat, 4), mag])
    stars.sort(key=lambda s: s[3])
    return {
        **ATTRIBUTION,
        "limitMagnitude": LIMIT_MAGNITUDE,
        "count": len(stars),
        "fields": ["hip", "raDegrees", "decDegrees", "magnitude"],
        "stars": stars,
    }


def build_constellations(raw, names_ja):
    out = []
    for f in raw["features"]:
        abbr = f["id"]
        lines = [[[round(to_ra(lon), 4), round(lat, 4)] for lon, lat in seg]
                 for seg in f["geometry"]["coordinates"]]
        # へび座は頭部と尾部で 2 つに分かれて入っているので、同じ略号にまとめる
        existing = next((c for c in out if c["abbr"] == abbr), None)
        if existing:
            existing["lines"].extend(lines)
            continue
        out.append({"abbr": abbr, "nameJa": names_ja[abbr], "lines": lines})
    out.sort(key=lambda c: c["abbr"])
    return {**ATTRIBUTION, "count": len(out), "constellations": out}


def build_bright_stars(raw, starnames, names_ja):
    out = []
    for f in raw["features"]:
        mag = f["properties"]["mag"]
        if mag > BRIGHT_MAGNITUDE:
            continue
        entry = starnames.get(str(f["id"]), {})
        name_en = entry.get("name")
        if not name_en:
            continue
        lon, lat = f["geometry"]["coordinates"]
        out.append({
            "hip": f["id"],
            "nameEn": name_en,
            "nameJa": names_ja[name_en],
            "raDegrees": round(to_ra(lon), 4),
            "decDegrees": round(lat, 4),
            "magnitude": mag,
        })
    out.sort(key=lambda s: s["magnitude"])
    return {**ATTRIBUTION, "limitMagnitude": BRIGHT_MAGNITUDE, "count": len(out), "stars": out}


def verify_names(lines_raw, stars_raw, starnames, names_ja):
    """日本語名の取りこぼしを落とす。データが増えたときに気づけるようにする。"""
    missing_c = {f["id"] for f in lines_raw["features"]} - set(names_ja["constellations"])
    if missing_c:
        sys.exit(f"星座の日本語名が足りない: {sorted(missing_c)}")

    need = set()
    for f in stars_raw["features"]:
        if f["properties"]["mag"] <= BRIGHT_MAGNITUDE:
            name = starnames.get(str(f["id"]), {}).get("name")
            if name:
                need.add(name)
    missing_s = need - set(names_ja["stars"])
    if missing_s:
        sys.exit(f"恒星の日本語名が足りない: {sorted(missing_s)}")


def dump(path, obj):
    return json.dumps(obj, ensure_ascii=False, separators=(",", ":")) + "\n"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true", help="生成物が最新かどうかだけ見る")
    args = ap.parse_args()

    stars_raw, lines_raw, starnames = (fetch(n) for n in SOURCES)
    names_ja = json.loads((ROOT / "tools" / "names-ja.json").read_text(encoding="utf-8"))
    verify_names(lines_raw, stars_raw, starnames, names_ja)

    outputs = {
        DATA / "stars.json": build_stars(stars_raw),
        DATA / "constellations.json": build_constellations(lines_raw, names_ja["constellations"]),
        DATA / "bright-stars.json": build_bright_stars(stars_raw, starnames, names_ja["stars"]),
    }

    stale = []
    for path, obj in outputs.items():
        text = dump(path, obj)
        if args.check:
            if not path.exists() or path.read_text(encoding="utf-8") != text:
                stale.append(path.name)
        else:
            DATA.mkdir(parents=True, exist_ok=True)
            path.write_text(text, encoding="utf-8")
            print(f"  {path.relative_to(ROOT)}: {obj['count']} 件 / {len(text):,} バイト")

    if args.check:
        if stale:
            sys.exit(f"生成物が古い: {stale}。python3 tools/build-star-catalog.py を実行する")
        print("ok: data/ は最新")


if __name__ == "__main__":
    main()
