#!/usr/bin/env python3
"""星座のイメージ図（星座絵）を data/constellation-figures.json に書き出す。

**星座線だけでは「なにに見立てたのか」が伝わらない。** 点と線に加えて、
星図で見慣れた絵を薄く重ねると、初心者でも形の意味が分かる。

出処は **NSF NOIRLab「The 88 Constellations」の線画**（CC BY 4.0・`tools/lineart/`）。
手描きの 7 星座を置き換えた（2026-08-25）。**手で描く必要が無いうえに星の上へ正確に載る**からで、
理由は下の「星から解ける」。

## 星から解ける

1 枚の SVG に**輪郭の線と星（`<circle>`、半径が等級）が同じ座標系で入っていて**、
**その星の並びは実際の空と一致する**（オリオン座で 22 個中 22 個・平均残差 0.08°）。
だから絵をどこへ置くかを手で決める必要がない。SVG の星と `data/constellations.json` の
星座線の頂点を突き合わせれば、**SVG 座標 → 空**の相似変換（拡大・回転・平行移動）が解ける。

- **どの円がどの星かは分かっていない。** 明るい順（＝円の半径の大きい順）に少数だけ組にして
  総当たりし、いちばん多くの星が乗る置き方を採る（[fit]）
- **北が上とは限らない。** からす座の図は 33° 傾いている。回転に上限を置かない
- **空を 100° またぐ星座は 1 枚の平らな絵と相似にならない。** 星ごとに合わせるのは諦めて、
  広がりだけ合わせる（[coarse]。88 星座のうち 2 つ）
- 絵は**星の並びよりずっと大きい**（ペガススの馬は四辺形の 5 倍で、置くと 90° を超える）。
  星のそばだけ残す（[ART_SPAN_LIMIT]）

## 赤道座標で書き出す

書き出すのは **J2000 の赤道座標（度）の折れ線**。アプリは星や星座線とまったく同じ道筋で投影する。
以前は正規化した絵をアプリ側で「投影後の外接矩形」へ写していたが、
**矩形の縦横比は空の位置で変わる**ので絵が伸び縮みし、
**首を傾けても絵だけ画面軸のまま立っていた**（2026-08-25）。

## 何を絵として拾うか

- `<path>` `<polyline>` `<polygon>` `<ellipse>` = 輪郭。`<line>` = 星座線なので**捨てる**
  （アプリが自前で引くので、拾うと二重になる）
- `<circle>` は**星に一致したものが星、しなかったものが絵**（目や飾り）
- 頂点がぜんぶ星の上に乗っている折れ線も星座線なので捨てる

## 点数を削る

**輪郭の点数がそのまま転送バイトに効く**（544×340 では 4,300 バイトしか余裕がない）。
ベジエを折れ線に開いたあと、**空の上で測った許容差**で間引く（[SIMPLIFY_DEG]）。
実機の 1 画素は 35° を 528px で見て 0.066° なので、この許容差は 2 画素ぶん。

- 作り直しは `python3 tools/build-constellation-figures.py`
- **`tools/lineart/` も `data/` も手で編集しない**
"""
from __future__ import annotations

import json
import math
import re
from itertools import permutations
from pathlib import Path

# 星座の略号 → tools/lineart/ のファイル名（ラテン名を小書き・空白なし）
SLUGS = {
    "And": "andromeda", "Ant": "antlia", "Aps": "apus", "Aqr": "aquarius", "Aql": "aquila",
    "Ara": "ara", "Ari": "aries", "Aur": "auriga", "Boo": "bootes", "Cae": "caelum",
    "Cam": "camelopardalis", "Cnc": "cancer", "CVn": "canesvenatici", "CMa": "canismajor",
    "CMi": "canisminor", "Cap": "capricornus", "Car": "carina", "Cas": "cassiopeia",
    "Cen": "centaurus", "Cep": "cepheus", "Cet": "cetus", "Cha": "chamaeleon", "Cir": "circinus",
    "Col": "columba", "Com": "comaberenices", "CrA": "coronaaustralis", "CrB": "coronaborealis",
    "Crv": "corvus", "Crt": "crater", "Cru": "crux", "Cyg": "cygnus", "Del": "delphinus",
    "Dor": "dorado", "Dra": "draco", "Equ": "equuleus", "Eri": "eridanus", "For": "fornax",
    "Gem": "gemini", "Gru": "grus", "Her": "hercules", "Hor": "horologium", "Hya": "hydra",
    "Hyi": "hydrus", "Ind": "indus", "Lac": "lacerta", "Leo": "leo", "LMi": "leominor",
    "Lep": "lepus", "Lib": "libra", "Lup": "lupus", "Lyn": "lynx", "Lyr": "lyra", "Men": "mensa",
    "Mic": "microscopium", "Mon": "monoceros", "Mus": "musca", "Nor": "norma", "Oct": "octans",
    "Oph": "ophiuchus", "Ori": "orion", "Pav": "pavo", "Peg": "pegasus", "Per": "perseus",
    "Phe": "phoenix", "Pic": "pictor", "Psc": "pisces", "PsA": "piscisaustrinus",
    "Pup": "puppis", "Pyx": "pyxis", "Ret": "reticulum", "Sge": "sagitta", "Sgr": "sagittarius",
    "Sco": "scorpius", "Scl": "sculptor", "Sct": "scutum", "Ser": "serpens", "Sex": "sextans",
    "Tau": "taurus", "Tel": "telescopium", "Tri": "triangulum", "TrA": "triangulumaustrale",
    "Tuc": "tucana", "UMa": "ursamajor", "UMi": "ursaminor", "Vel": "vela", "Vir": "virgo",
    "Vol": "volans", "Vul": "vulpecula",
}

SOURCE = "NSF NOIRLab «The 88 Constellations» (https://noirlab.edu/public/education/constellations/)"
SOURCE_LICENSE = "CC BY 4.0, NOIRLab/NSF/AURA"

# 間引きの許容差[度]。実機の 1 画素は 0.066°（35° を 528px）なので 2 画素ぶん
SIMPLIFY_DEG = 0.13
# 星と認める距離[度]。SVG の星は実測で 0.1° ほどずれている
STAR_MATCH_DEG = 0.7
# 相似変換に許す回転[度]。**北が上とは限らない。** からす座の図は 33° 傾いていて、
# 「数度しかずれないはず」と決めつけると正しい答えが弾かれる
MAX_ROTATION_DEG = 180.0
# 乗った星がこれだけ散らばっていないと、あてはめが潰れていると見なす（星座の広がりに対する比）
MIN_SPREAD = 0.35
# ベジエ 1 本を折れ線に開くときの分割数。**多めに開いて後で間引く**
BEZIER_STEPS = 8
# 絵を残す範囲。星座線の広がりの何倍まで／最小・最大で何度まで。
# **下限が要る。** からす座は 5 個の四辺形しかないのに絵はカラス 1 羽ぶんあるので、
# 倍率だけで切ると輪郭の切れ端しか残らない。上限は画角 35° に収まる大きさ
ART_SPAN_LIMIT = 1.3
ART_SPAN_MIN_DEG = 12.0
ART_SPAN_MAX_DEG = 30.0
# viewBox の外へどれだけはみ出すまで拾うか[px]
VIEWBOX_MARGIN = 20.0
# 円と楕円を折れ線にするときの頂点数
ELLIPSE_STEPS = 16


# --- 球面と接平面 ------------------------------------------------------------

def _unit(ra_deg: float, dec_deg: float) -> tuple[float, float, float]:
    ra = math.radians(ra_deg)
    dec = math.radians(dec_deg)
    return (math.cos(dec) * math.cos(ra), math.cos(dec) * math.sin(ra), math.sin(dec))


def _center(vertices: list[tuple[float, float]]) -> tuple[float, float]:
    """星座線の頂点の平均方向。**赤経を数値で平均すると 0h をまたぐ星座で裏側に飛ぶ**"""
    x = y = z = 0.0
    for ra, dec in vertices:
        vx, vy, vz = _unit(ra, dec)
        x += vx
        y += vy
        z += vz
    n = math.sqrt(x * x + y * y + z * z)
    return (math.degrees(math.atan2(y / n, x / n)) % 360.0, math.degrees(math.asin(z / n)))


def _to_plane(ra_deg: float, dec_deg: float, ra0: float, dec0: float) -> complex | None:
    """接平面（心射図法）へ落とす。**実部が右＝赤経が減る向き**、虚部が下（SVG と同じ向き）"""
    ra = math.radians(ra_deg - ra0)
    dec = math.radians(dec_deg)
    d0 = math.radians(dec0)
    cos_c = math.sin(d0) * math.sin(dec) + math.cos(d0) * math.cos(dec) * math.cos(ra)
    if cos_c <= 0.05:  # 接点の裏側は発散する
        return None
    xi = math.cos(dec) * math.sin(ra) / cos_c
    eta = (math.cos(d0) * math.sin(dec) - math.sin(d0) * math.cos(dec) * math.cos(ra)) / cos_c
    return complex(-xi, -eta)


def _from_plane(p: complex, ra0: float, dec0: float) -> tuple[float, float]:
    """[_to_plane] の逆。接平面の点を赤道座標へ戻す"""
    xi = -p.real
    eta = -p.imag
    d0 = math.radians(dec0)
    rho = math.hypot(xi, eta)
    if rho < 1e-12:
        return (ra0 % 360.0, dec0)
    c = math.atan(rho)
    dec = math.asin(math.cos(c) * math.sin(d0) + eta * math.sin(c) * math.cos(d0) / rho)
    ra = math.radians(ra0) + math.atan2(
        xi * math.sin(c),
        rho * math.cos(d0) * math.cos(c) - eta * math.sin(d0) * math.sin(c),
    )
    return (math.degrees(ra) % 360.0, math.degrees(dec))


# --- SVG ---------------------------------------------------------------------

TOKENS = re.compile(r"[MmLlHhVvCcSsQqTtAaZz]|[-+]?\d*\.?\d+(?:[eE][-+]?\d+)?")
ATTR = re.compile(r'(\w[\w-]*)\s*=\s*"([^"]*)"')


def _bezier(p0, p1, p2, p3):
    for i in range(1, BEZIER_STEPS + 1):
        t = i / BEZIER_STEPS
        u = 1 - t
        yield complex(
            u ** 3 * p0.real + 3 * u * u * t * p1.real + 3 * u * t * t * p2.real + t ** 3 * p3.real,
            u ** 3 * p0.imag + 3 * u * u * t * p1.imag + 3 * u * t * t * p2.imag + t ** 3 * p3.imag,
        )


def _parse_path(d: str) -> list[list[complex]]:
    """`d` 属性を折れ線の集まりに開く。曲線は [BEZIER_STEPS] 等分する"""
    tokens = TOKENS.findall(d)
    i = 0
    cur = start = complex(0, 0)
    control = None
    command = "M"
    strokes: list[list[complex]] = []
    points: list[complex] = []

    def number() -> float:
        nonlocal i
        value = float(tokens[i])
        i += 1
        return value

    while i < len(tokens):
        if tokens[i].isalpha():
            command = tokens[i]
            i += 1
            if command in "Zz":
                if points:
                    points.append(start)
                    strokes.append(points)
                points = []
                cur = start
                control = None
                continue
        relative = command.islower()
        head = command.upper()
        base = cur if relative else complex(0, 0)
        if head == "M":
            cur = complex(number(), number()) + base
            if points:
                strokes.append(points)
            points = [cur]
            start = cur
            control = None
            # M のあとに続く座標は L 扱いという SVG の決まり
            command = "l" if relative else "L"
        elif head == "L":
            cur = complex(number(), number()) + base
            points.append(cur)
            control = None
        elif head == "H":
            cur = complex(number() + base.real, cur.imag)
            points.append(cur)
            control = None
        elif head == "V":
            cur = complex(cur.real, number() + base.imag)
            points.append(cur)
            control = None
        elif head in ("C", "S"):
            if head == "C":
                c1 = complex(number(), number()) + base
                c2 = complex(number(), number()) + base
            else:
                c1 = 2 * cur - control if control is not None else cur
                c2 = complex(number(), number()) + base
            end = complex(number(), number()) + base
            points.extend(_bezier(cur, c1, c2, end))
            cur = end
            control = c2
        elif head in ("Q", "T"):
            if head == "Q":
                q = complex(number(), number()) + base
            else:
                q = 2 * cur - control if control is not None else cur
            end = complex(number(), number()) + base
            # 2 次を 3 次に持ち上げる
            points.extend(_bezier(cur, cur + 2 / 3 * (q - cur), end + 2 / 3 * (q - end), end))
            cur = end
            control = q
        elif head == "A":
            for _ in range(5):
                number()
            end = complex(number(), number()) + base
            points.append(end)  # 円弧は端点だけ。飾りの小さな弧にしか出てこない
            cur = end
            control = None
        else:
            break
    if points:
        strokes.append(points)
    return [s for s in strokes if len(s) > 1]


def _transform(spec: str):
    """`translate(...) rotate(...)` と `matrix(...)` だけ解く（楕円の傾きにしか出てこない）"""
    a, b, c, d, e, f = 1.0, 0.0, 0.0, 1.0, 0.0, 0.0
    for name, args in re.findall(r"(\w+)\s*\(([^)]*)\)", spec):
        v = [float(x) for x in re.findall(r"[-+]?\d*\.?\d+(?:[eE][-+]?\d+)?", args)]
        if name == "matrix":
            m = v
        elif name == "translate":
            m = [1, 0, 0, 1, v[0], v[1] if len(v) > 1 else 0]
        elif name == "rotate":
            t = math.radians(v[0])
            m = [math.cos(t), math.sin(t), -math.sin(t), math.cos(t), 0, 0]
        elif name == "scale":
            m = [v[0], 0, 0, v[1] if len(v) > 1 else v[0], 0, 0]
        else:
            continue
        a, b, c, d, e, f = (
            a * m[0] + c * m[1], b * m[0] + d * m[1],
            a * m[2] + c * m[3], b * m[2] + d * m[3],
            a * m[4] + c * m[5] + e, b * m[4] + d * m[5] + f,
        )
    return lambda p: complex(a * p.real + c * p.imag + e, b * p.real + d * p.imag + f)


def _ellipse(cx: float, cy: float, rx: float, ry: float) -> list[complex]:
    pts = [
        complex(cx + rx * math.cos(2 * math.pi * i / ELLIPSE_STEPS),
                cy + ry * math.sin(2 * math.pi * i / ELLIPSE_STEPS))
        for i in range(ELLIPSE_STEPS)
    ]
    return pts + [pts[0]]


def _inside(p: complex) -> bool:
    """viewBox（0..360）の外は描かれていない。**消し忘れの部品が入っている図がある**"""
    return -VIEWBOX_MARGIN <= p.real <= 360 + VIEWBOX_MARGIN and -VIEWBOX_MARGIN <= p.imag <= 360 + VIEWBOX_MARGIN


def _clip_radius(points: list[complex], limit: float) -> list[list[complex]]:
    """接平面の原点（星座の中心）から [limit] より遠い部分を落とし、折れ線を切り分ける"""
    out: list[list[complex]] = []
    run: list[complex] = []
    for p in points:
        if abs(p) <= limit:
            run.append(p)
        elif run:
            out.append(run)
            run = []
    if run:
        out.append(run)
    return [r for r in out if len(r) > 1]


def _clip(points: list[complex]) -> list[list[complex]]:
    out: list[list[complex]] = []
    run: list[complex] = []
    for p in points:
        if _inside(p):
            run.append(p)
        elif run:
            out.append(run)
            run = []
    if run:
        out.append(run)
    return [r for r in out if len(r) > 1]


def read_svg(path: Path) -> tuple[list[tuple[complex, float]], list[tuple[str, list[complex]]]]:
    """SVG から**円（星かもしれないもの）**と**折れ線**を取り出す"""
    text = path.read_text(encoding="utf-8")
    circles: list[tuple[complex, float]] = []
    strokes: list[tuple[str, list[complex]]] = []
    for tag, body in re.findall(r"<(path|polyline|polygon|line|circle|ellipse)\b([^>]*)>", text):
        attrs = dict(ATTR.findall(body))
        move = _transform(attrs["transform"]) if "transform" in attrs else (lambda p: p)
        if tag == "circle":
            c = move(complex(float(attrs["cx"]), float(attrs["cy"])))
            if _inside(c):
                circles.append((c, float(attrs["r"])))
        elif tag == "ellipse":
            pts = _ellipse(float(attrs["cx"]), float(attrs["cy"]),
                           float(attrs["rx"]), float(attrs["ry"]))
            strokes += [(tag, r) for r in _clip([move(p) for p in pts])]
        elif tag == "line":
            # 星座線。アプリが自前で引くので拾わない
            continue
        elif tag == "path":
            for s in _parse_path(attrs.get("d", "")):
                strokes += [(tag, r) for r in _clip([move(p) for p in s])]
        else:
            v = [float(x) for x in re.findall(r"[-+]?\d*\.?\d+(?:[eE][-+]?\d+)?", attrs.get("points", ""))]
            pts = [complex(v[i], v[i + 1]) for i in range(0, len(v) - 1, 2)]
            if tag == "polygon" and pts:
                pts.append(pts[0])
            strokes += [(tag, r) for r in _clip([move(p) for p in pts])]
    return circles, strokes


# --- あてはめ ----------------------------------------------------------------

def _similarity(pairs: list[tuple[complex, complex]]) -> tuple[complex, complex]:
    """`svg → 接平面` の相似変換を最小二乗で解く。返すのは (倍率つき回転, 平行移動)"""
    mp = sum(p for p, _ in pairs) / len(pairs)
    ms = sum(s for _, s in pairs) / len(pairs)
    num = sum((s - ms) * (p - mp).conjugate() for p, s in pairs)
    den = sum(abs(p - mp) ** 2 for p, _ in pairs)
    m = num / den
    return m, ms - m * mp


def fit(circles, sky: list[complex], bright: list[complex]):
    """SVG の星と空の星を突き合わせて相似変換を解く。

    **どの円がどの星かは分かっていない。** 明るい順に少数だけ組にして総当たりし、
    いちばん多くの星が乗る組を採る（星以外の円が混ざっていても外れ値として落ちる）。
    """
    if len(sky) < 2 or len(circles) < 2:
        return None
    tolerance = math.radians(STAR_MATCH_DEG)
    # SVG の円は半径が等級なので、大きい順が明るい順
    big = [c for c, _ in sorted(circles, key=lambda t: -t[1])[:6]]
    span_sky = max(abs(a - b) for a in sky for b in sky)
    best = None
    for a, b in permutations(big, 2):
        if abs(b - a) < 1e-6:
            continue
        for u, v in permutations(bright, 2):
            if abs(v - u) < 1e-12:
                continue
            m = (v - u) / (b - a)
            if abs(math.degrees(math.atan2(m.imag, m.real))) > MAX_ROTATION_DEG:
                continue
            matched: dict[int, tuple[float, complex]] = {}
            for c, _ in circles:
                z = (c - a) * m + u
                d, j = min((abs(z - s), j) for j, s in enumerate(sky))
                if d < tolerance and (j not in matched or d < matched[j][0]):
                    matched[j] = (d, c)
            if len(matched) < 2:
                continue
            # **絵を 1 点に潰す変換は、どの星にも乗るので点数だけなら勝ってしまう。**
            # 乗った星が散らばっていることを条件にして弾く（同じ星への重複は数えていない）
            spread = max(abs(sky[i] - sky[j]) for i in matched for j in matched)
            if spread < span_sky * MIN_SPREAD:
                continue
            score = (len(matched), -sum(d for d, _ in matched.values()) / max(len(matched), 1))
            if best is None or score > best[0]:
                best = (score, [(c, sky[j]) for j, (_, c) in matched.items()])
    if best is None or len(best[1]) < 2:
        return None
    m, t = _similarity(best[1])
    if abs(math.degrees(math.atan2(m.imag, m.real))) > MAX_ROTATION_DEG:
        return None
    residual = [abs(m * p + t - s) for p, s in best[1]]
    return m, t, len(best[1]), math.degrees(sum(residual) / len(residual))


def coarse(circles, sky: list[complex]) -> tuple[complex, complex]:
    """星の対応が取れないときの逃げ道。**円の広がりを星の広がりに合わせるだけ。**

    うみへび座のように**空を 100° またぐ星座**は、平らな 1 枚の絵と空とが相似にならない
    （NOIRLab の図が長さを詰めて描いてある）。星ごとに合わせるのは諦めて、
    「だいたいその星座のところに絵がある」ところまでで止める。回転はかけない。
    """
    points = [c for c, _ in circles]
    mid_svg = sum(points) / len(points)
    mid_sky = sum(sky) / len(sky)
    span_svg = math.sqrt(sum(abs(p - mid_svg) ** 2 for p in points) / len(points))
    span_sky = math.sqrt(sum(abs(p - mid_sky) ** 2 for p in sky) / len(sky))
    m = complex(span_sky / span_svg, 0.0)
    return m, mid_sky - m * mid_svg


# --- 間引き ------------------------------------------------------------------

def simplify(points: list[complex], tolerance: float) -> list[complex]:
    """Douglas-Peucker。**輪郭の点数がそのまま転送バイトに効く**ので、描く前に削る"""
    if len(points) < 3:
        return points
    stack = [(0, len(points) - 1)]
    keep = {0, len(points) - 1}
    while stack:
        lo, hi = stack.pop()
        a, b = points[lo], points[hi]
        span = b - a
        length = abs(span)
        worst, index = 0.0, lo
        for i in range(lo + 1, hi):
            p = points[i]
            d = abs((span.conjugate() * (p - a)).imag) / length if length > 1e-12 else abs(p - a)
            if d > worst:
                worst, index = d, i
        if worst > tolerance:
            keep.add(index)
            stack += [(lo, index), (index, hi)]
    return [points[i] for i in sorted(keep)]


# --- 組み立て ----------------------------------------------------------------

def build(constellation, circles, strokes, stars) -> tuple[list, dict] | None:
    vertices = sorted({(round(p[0], 4), round(p[1], 4)) for seg in constellation["lines"] for p in seg})
    ra0, dec0 = _center(vertices)
    planed = [(v, _to_plane(v[0], v[1], ra0, dec0)) for v in vertices]
    planed = [(v, p) for v, p in planed if p is not None]
    if len(planed) < 2:
        return None
    sky = [p for _, p in planed]
    # 明るい順に並べておく。SVG の大きい円と組にする相手
    order = sorted(planed, key=lambda t: stars.get(t[0], 99.0))
    bright = [p for _, p in order[:6]]

    fitted = fit(circles, sky, bright)
    # **星ごとに合っていない答えは採らない。** 3 個以下しか乗らないのに円が 5 個以上あるのは、
    # たまたま合う置き方を拾っただけ（残差 0 に見えるのは合わせた点しか見ていないから）
    if fitted is not None and (fitted[2] >= 4 or fitted[2] == len(circles)) and fitted[3] < 0.4:
        m, t, matched, residual = fitted
    elif len(circles) >= 2:
        m, t = coarse(circles, sky)
        matched, residual = 0, float("nan")
    else:
        return None

    star_positions = [c for c, _ in circles if any(abs(m * c + t - s) < math.radians(STAR_MATCH_DEG) for s in sky)]

    def on_star(p: complex) -> bool:
        return any(abs(p - s) < 5.0 for s in star_positions)

    # **絵は星の並びよりずっと大きい。** ペガススの馬は四辺形の 5 倍あって、
    # そのまま置くと 90° を超えて広がり、**画角 35° では「どこかの線」にしか見えない**。
    # 星のそばだけ残す（星に乗っている胴体が残り、はみ出した脚や翼が落ちる）
    span = math.degrees(math.atan(max(abs(p) for p in sky))) * ART_SPAN_LIMIT
    limit = math.tan(math.radians(min(max(span, ART_SPAN_MIN_DEG), ART_SPAN_MAX_DEG)))

    tolerance = math.radians(SIMPLIFY_DEG)
    figure = []
    for tag, points in strokes:
        # 頂点がぜんぶ星の上に乗っている折れ線は星座線。アプリが引くので捨てる
        if tag in ("polyline", "polygon") and all(on_star(p) for p in points):
            continue
        for run in _clip_radius([m * p + t for p in points], limit):
            thinned = simplify(run, tolerance)
            if len(thinned) >= 2:
                figure.append([_from_plane(p, ra0, dec0) for p in thinned])
    # 星に一致しなかった円は目や飾り。絵として残す
    for c, r in circles:
        if any(abs(c - s) < 1e-9 for s in star_positions):
            continue
        for run in _clip_radius([m * p + t for p in _ellipse(c.real, c.imag, r, r)], limit):
            thinned = simplify(run, tolerance)
            if len(thinned) >= 3:
                figure.append([_from_plane(p, ra0, dec0) for p in thinned])
    if not figure:
        return None
    return figure, {"matched": matched, "circles": len(circles), "residual": residual,
                    "rotation": math.degrees(math.atan2(m.imag, m.real))}


def _dump(payload: dict) -> str:
    """**1 ストロークを 1 行に畳む。** 1 点ずつ改行すると 9,000 点で 390KB になり、
    同梱データとしても差分としても読めない（1 行 1 ストロークなら 4 割で済む）"""
    head = {k: v for k, v in payload.items() if k != "figures"}
    lines = [json.dumps(head, ensure_ascii=False, indent=1)[:-2] + ",", ' "figures": {']
    items = list(payload["figures"].items())
    for i, (abbr, strokes) in enumerate(items):
        body = ",\n".join("   " + json.dumps(s, separators=(",", ",")) for s in strokes)
        lines.append(f'  "{abbr}": [\n{body}\n  ]' + ("," if i < len(items) - 1 else ""))
    lines += [" }", "}"]
    return "\n".join(lines) + "\n"


def main() -> int:
    root = Path(__file__).resolve().parent.parent
    constellations = json.loads((root / "data" / "constellations.json").read_text(encoding="utf-8"))
    catalog = json.loads((root / "data" / "stars.json").read_text(encoding="utf-8"))
    fields = catalog["fields"]
    stars_raw = [dict(zip(fields, s)) for s in catalog["stars"]]

    figures: dict[str, list] = {}
    skipped: list[str] = []
    rough: list[str] = []
    worst = []
    for constellation in constellations["constellations"]:
        abbr = constellation["abbr"]
        path = root / "tools" / "lineart" / f"{SLUGS[abbr]}.svg"
        circles, strokes = read_svg(path)
        # 星座線の頂点の等級。**頂点は星の位置そのもの**なので 1° 以内から拾える
        magnitudes = {}
        for seg in constellation["lines"]:
            for p in seg:
                key = (round(p[0], 4), round(p[1], 4))
                for s in stars_raw:
                    if abs(s["decDegrees"] - p[1]) > 1:
                        continue
                    d = math.hypot((s["raDegrees"] - p[0]) * math.cos(math.radians(p[1])),
                                   s["decDegrees"] - p[1])
                    if d <= 1 and s["magnitude"] < magnitudes.get(key, 99.0):
                        magnitudes[key] = s["magnitude"]
        built = build(constellation, circles, strokes, magnitudes)
        if built is None:
            skipped.append(abbr)
            continue
        figure, report = built
        worst.append((report["residual"], abbr, report))
        if report["matched"] == 0:
            rough.append(abbr)
        # 小数 3 桁 = 0.004°。間引きの許容差（0.13°）よりずっと細かいので、これ以上は要らない
        figures[abbr] = [[[round(ra, 3), round(dec, 3)] for ra, dec in stroke] for stroke in figure]

    out = root / "data" / "constellation-figures.json"
    payload = {
        "note": "星座絵。**J2000 の赤道座標（度）の折れ線**で、星や星座線と同じ道筋で投影する。"
                "SVG に入っている星の位置から相似変換を解いて空へ載せてある",
        "source": SOURCE,
        "sourceLicense": SOURCE_LICENSE,
        "generatedBy": "tools/build-constellation-figures.py",
        "epoch": "J2000.0",
        "count": len(figures),
        "figures": {abbr: figures[abbr] for abbr in sorted(figures)},
    }
    out.write_text(_dump(payload), encoding="utf-8")

    strokes_total = sum(len(s) for s in figures.values())
    points = sum(len(p) for s in figures.values() for p in s)
    print(f"ok: {out.relative_to(root)} に {len(figures)} 星座・{strokes_total} ストローク・{points} 点")
    fitted = [w for w in worst if w[2]["matched"] > 0]
    fitted.sort(reverse=True)
    print(f"  星に合わせられた星座 {len(fitted)}・広がりだけ合わせた星座 {len(rough)}")
    for residual, abbr, report in fitted[:3]:
        print(f"  あてはめが粗い順: {abbr} 星 {report['matched']}/{report['circles']} 個一致・"
              f"平均 {residual:.2f}°・回転 {report['rotation']:+.1f}°")
    if rough:
        print(f"  広がりだけ合わせた星座（星ごとには合っていない）: {' '.join(sorted(rough))}")
    if skipped:
        print(f"  絵を付けられなかった星座: {' '.join(skipped)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
