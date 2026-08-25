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
ROMAN_BOUNDARY_URL = "https://cdsarc.cds.unistra.fr/ftp/cats/VI/42/data.dat"
# 固有運動は d3-celestial に無いので、HIP 番号で Hipparcos 本表から引き当てる。
# 1 万年遡ると星座の形が変わる（アークトゥルスで約 6°）ので、深い時代にはこれが要る。
PROPER_MOTION_URL = (
    "https://vizier.cds.unistra.fr/viz-bin/asu-tsv"
    "?-source=I/239/hip_main&-out=HIP,pmRA,pmDE&Vmag=%3C6.0&-out.max=20000"
)

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


def fetch_text(name, url=None):
    """元データを取ってくる。一度取ったものは tools/.cache に置いて使い回す。"""
    CACHE.mkdir(parents=True, exist_ok=True)
    path = CACHE / name
    if not path.exists():
        print(f"  取得中: {name}")
        urllib.request.urlretrieve(url or f"{BASE}/{name}", path)
    return path.read_text(encoding="utf-8")


def fetch(name):
    return json.loads(fetch_text(name))


def fetch_proper_motions():
    """HIP 番号 → (pmRA*cos(dec), pmDE) [mas/年]。取れなければ空で返す。"""
    text = fetch_text("hip-proper-motion.tsv", PROPER_MOTION_URL)
    out = {}
    for line in text.splitlines():
        if not line or line.startswith(("#", "-")):
            continue
        parts = line.split("\t")
        if len(parts) < 3:
            continue
        try:
            hip = int(parts[0])
            out[hip] = (float(parts[1]), float(parts[2]))
        except ValueError:
            # 見出しの 2 行（列名と単位）と、値が空の星はここで落ちる
            continue
    return out


def build_vertex_motion(stars_raw, proper_motions):
    """星座線の頂点 → その位置にある星の固有運動。

    **頂点は星の位置そのもの**（893 個中 892 個が 0.0 秒角で一致）。等級 6 まで見るのは、
    星座線が同梱星表（5 等まで）より暗い星も結んでいるため。
    """
    grid = {}
    for f in stars_raw["features"]:
        lon, lat = f["geometry"]["coordinates"]
        grid.setdefault((round(lon, 2), round(lat, 2)), []).append((lon, lat, f["id"]))

    def motion(lon, lat):
        key = (round(lon, 2), round(lat, 2))
        best = None
        best_d = None
        for dx in (-1, 0, 1):
            for dy in (-1, 0, 1):
                cell = (round(key[0] + dx * 0.01, 2), round(key[1] + dy * 0.01, 2))
                for slon, slat, hip in grid.get(cell, ()):  # noqa: B007
                    d = (slon - lon) ** 2 + (slat - lat) ** 2
                    if best_d is None or d < best_d:
                        best_d, best = d, hip
        # 0.05° より遠ければ、その頂点に星は無い（星のあいだを通す線の折れ点）
        if best is None or best_d > 0.05 ** 2:
            return (0.0, 0.0)
        pm_ra, pm_dec = proper_motions.get(best, (0.0, 0.0))
        return (round(pm_ra, 2), round(pm_dec, 2))

    return motion


def to_ra(lon):
    """元データの経度（-180〜180）を赤経（0〜360）に戻す。"""
    return lon + 360.0 if lon < 0 else lon


def build_stars(raw, proper_motions):
    stars = []
    missing = 0
    for f in raw["features"]:
        mag = f["properties"]["mag"]
        if mag > LIMIT_MAGNITUDE:
            continue
        lon, lat = f["geometry"]["coordinates"]
        pm_ra, pm_dec = proper_motions.get(f["id"], (0.0, 0.0))
        if f["id"] not in proper_motions:
            missing += 1
        stars.append([
            f["id"], round(to_ra(lon), 4), round(lat, 4), mag,
            round(pm_ra, 2), round(pm_dec, 2),
        ])
    stars.sort(key=lambda s: s[3])
    if missing:
        print(f"  固有運動を引けなかった星: {missing} 個（0 として扱う）")
    return {
        **ATTRIBUTION,
        "properMotionSource": "CDS I/239: The Hipparcos Main Catalogue (ESA 1997)",
        "limitMagnitude": LIMIT_MAGNITUDE,
        "count": len(stars),
        "fields": [
            "hip", "raDegrees", "decDegrees", "magnitude",
            "pmRaMasPerYear", "pmDecMasPerYear",
        ],
        "stars": stars,
    }


def build_constellations(raw, names_ja, vertex_motion):
    out = []
    for f in raw["features"]:
        abbr = f["id"]
        lines = [[[round(to_ra(lon), 4), round(lat, 4), *vertex_motion(lon, lat)]
                  for lon, lat in seg]
                 for seg in f["geometry"]["coordinates"]]
        # へび座は頭部と尾部で 2 つに分かれて入っているので、同じ略号にまとめる
        existing = next((c for c in out if c["abbr"] == abbr), None)
        if existing:
            existing["lines"].extend(lines)
            continue
        out.append({"abbr": abbr, "nameJa": names_ja[abbr], "lines": lines})
    out.sort(key=lambda c: c["abbr"])
    return {
        **ATTRIBUTION,
        # **頂点は星の位置そのもの**（893 個中 892 個が 0.0 秒角で一致）。
        # 固有運動を頂点へ持たせておけば、深い時代でも線が星から外れない
        "fields": ["raDegrees", "decDegrees", "pmRaMasPerYear", "pmDecMasPerYear"],
        "count": len(out),
        "constellations": out,
    }


def build_bright_stars(raw, starnames, names_ja, proper_motions):
    out = []
    for f in raw["features"]:
        mag = f["properties"]["mag"]
        if mag > BRIGHT_MAGNITUDE:
            continue
        pm_ra, pm_dec = proper_motions.get(f["id"], (0.0, 0.0))
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
            "pmRaMasPerYear": pm_ra,
            "pmDecMasPerYear": pm_dec,
        })
    out.sort(key=lambda s: s["magnitude"])
    return {**ATTRIBUTION, "limitMagnitude": BRIGHT_MAGNITUDE, "count": len(out), "stars": out}


def build_constellation_boundaries(raw, names_ja):
    """Roman (1987) の B1875 境界表を、端末で順に走査できる形へする。"""
    rows = []
    for line in raw.splitlines():
        if not line.strip():
            continue
        ra_low, ra_up, dec_low, abbr = line.split()
        rows.append([float(ra_low), float(ra_up), float(dec_low), abbr])
    if len(rows) != 357:
        sys.exit(f"Roman 1987 の境界行数が不正: {len(rows)}（期待値 357）")
    missing = {row[3] for row in rows} - set(names_ja)
    if missing:
        sys.exit(f"境界表の日本語名が足りない: {sorted(missing)}")
    return {
        "source": "CDS VI/42: Roman, Identification of a Constellation from a Position (1987)",
        "sourceUrl": ROMAN_BOUNDARY_URL,
        "epoch": "B1875.0",
        "generatedBy": "tools/build-star-catalog.py",
        "fields": ["raLowHours", "raUpHours", "decLowDegrees", "abbr"],
        "namesJa": names_ja,
        "count": len(rows),
        "boundaries": rows,
    }


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
    boundaries_raw = fetch_text("constellation-boundaries-roman87.dat", ROMAN_BOUNDARY_URL)
    verify_names(lines_raw, stars_raw, starnames, names_ja)
    proper_motions = fetch_proper_motions()
    vertex_motion = build_vertex_motion(stars_raw, proper_motions)

    outputs = {
        DATA / "stars.json": build_stars(stars_raw, proper_motions),
        DATA / "constellations.json": build_constellations(
            lines_raw,
            names_ja["constellations"],
            vertex_motion,
        ),
        DATA / "bright-stars.json": build_bright_stars(
            stars_raw,
            starnames,
            names_ja["stars"],
            proper_motions,
        ),
        DATA / "constellation-boundaries.json": build_constellation_boundaries(
            boundaries_raw,
            names_ja["constellations"],
        ),
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
