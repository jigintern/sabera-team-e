package jp.jig.glasses.sample.kmp.starmap

import kotlin.math.acos
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** キャンバスの座標系。画像はこの範囲に収める */
const val PANEL_WIDTH = 576
const val PANEL_HEIGHT = 360

/** 観測地と時刻。歳差と緯度の行列はここが変わるまで作り直さなくてよい */
data class Site(val latDeg: Double, val lonDeg: Double)

/** グラスの視線。方位角はキャリブレーション済みの絶対値 */
data class Look(val azDeg: Double, val altDeg: Double)

/** 星座名を置く位置。画像には焼かず sendCanvas のテキストとして重ねる */
data class Label(val text: String, val x: Int, val y: Int)

class StarMap(val width: Int, val height: Int, val gray: ByteArray, val labels: List<Label>)

/** いま空に出ている星座と、その方角。方位合わせをせずに試すために使う */
data class Aimed(val nameJa: String, val azDeg: Double, val altDeg: Double) {
    /** 「南南西 高度 45°」のような表示 */
    val where: String
        get() {
            val points = listOf("北", "北北東", "北東", "東北東", "東", "東南東", "南東", "南南東",
                "南", "南南西", "南西", "西南西", "西", "西北西", "北西", "北北西")
            val i = ((azDeg + 11.25) / 22.5).toInt() % 16
            return "${points[i]} 高度 ${altDeg.toInt()}°"
        }
}

/**
 * 星図を 1 画素 1 バイトのグレースケールに描く。
 * 3bit への量子化と RLE 圧縮は SDK 側がやるので、ここでは 0-255 のまま置く。
 *
 * 緑 8 階調しか出ないので、等級は明るさだけでなく点の大きさと組み合わせる。
 */
class StarMapRenderer(private val catalog: StarCatalog) {

    /** 歳差は 26 年で 0.36° なので毎フレーム引き直す必要はない。30 日ぶんまとめる */
    @Volatile
    private var precessedKey = Long.MIN_VALUE

    @Volatile
    private var cache: Precessed? = null

    fun render(
        site: Site,
        epochMillis: Long,
        look: Look,
        fovDeg: Double,
        limitMagnitude: Double,
        width: Int = PANEL_WIDTH,
        height: Int = PANEL_HEIGHT,
        drawLines: Boolean = true,
        maxLabels: Int = 8,
        tracks: List<SkyTrack> = emptyList(),
    ): StarMap {
        val d = daysFromJ2000(epochMillis)
        val precessed = precessed(d)
        val lst = localSiderealDeg(d, site.lonDeg)
        val basis = Basis(look.azDeg, look.altDeg)
        val k = projectionScale(width, fovDeg)
        val gray = ByteArray(width * height)

        if (drawLines) {
            for (lines in precessed.lines) {
                for (seg in lines) {
                    for (i in 0 until seg.size - 1) {
                        drawGreatCircle(gray, width, height, seg[i], seg[i + 1], lst, site, basis, k)
                    }
                }
            }
        }

        for (i in catalog.stars.indices) {
            val star = catalog.stars[i]
            if (star.magnitude > limitMagnitude) continue
            val p = precessed.stars[i]
            val aa = toAltAz(p[0], p[1], lst, site.latDeg)
            val q = project(enu(aa[0], aa[1]), basis, k, width, height) ?: continue
            if (q[0] < -4 || q[1] < -4 || q[0] > width + 4 || q[1] > height + 4) continue
            // 明るいほど大きく、明るく。8 階調では明るさだけだと潰れる
            val t = ((limitMagnitude - star.magnitude) / (limitMagnitude + 1.5)).coerceIn(0.0, 1.0)
            val value = (255.0 * (0.45 + 0.55 * t)).roundToInt()
            // 点の大きさは画素数に比例させる。576px で 1px にすると 0.06° になって実機で見えない
            val base = if (t < 0.35) 1.0 else if (t < 0.7) 2.0 else 3.0
            dot(gray, width, height, q[0], q[1], value, (base * width / 196.0).roundToInt(), round = true)
        }

        // 衛星の軌跡は星より手前に描く。星座線より明るくして見分けが付くようにする
        val trackLabels = ArrayList<Label>()
        for (track in tracks) {
            drawTrack(gray, width, height, track, basis, k)
            val q = project(enu(track.nowAzDeg, track.nowAltDeg), basis, k, width, height)
            if (q != null && q[0] >= 0 && q[1] >= 0 && q[0] <= width && q[1] <= height) {
                // いまの位置は画像にも点を打つ。テキストが出なくても何かは見える
                dot(gray, width, height, q[0], q[1], 255, (3.0 * width / 196.0).roundToInt(), round = true)
                if (track.labelled) {
                    // 日が当たっているものは塗り、影のものは輪郭。肉眼で見えるかどうかの区別
                    val mark = if (track.sunlit) "●" else "○"
                    trackLabels += Label(mark + track.name, q[0].roundToInt(), q[1].roundToInt())
                }
            }
        }

        val starLabels = labels(precessed, lst, site, basis, k, width, height, maxLabels)
        // 衛星の名前を先に置く。枠が足りないときに消えるのは星座名のほう
        val merged = (trackLabels + starLabels).take(maxLabels.coerceAtLeast(trackLabels.size))
        return StarMap(width, height, gray, merged)
    }

    /**
     * いま空に出ている星座を、高度の高い順に返す。
     * キャリブレーションもグラスの姿勢も要らずに「その星座を見た絵」を出すために使う。
     */
    fun visibleConstellations(site: Site, epochMillis: Long, minAltDeg: Double = 10.0): List<Aimed> {
        val d = daysFromJ2000(epochMillis)
        val lst = localSiderealDeg(d, site.lonDeg)
        val precessed = precessed(d)
        val found = ArrayList<Aimed>()
        for (i in catalog.constellations.indices) {
            val center = precessed.centers[i] ?: continue
            val aa = toAltAz(center[0], center[1], lst, site.latDeg)
            if (aa[1] < minAltDeg) continue
            found += Aimed(catalog.constellations[i].nameJa, aa[0], aa[1])
        }
        return found.sortedByDescending { it.altDeg }
    }

    /**
     * 30 日ぶんまとめて歳差をかけた星表。星・星座線・星座の中心をひとまとめに作り直す。
     *
     * 星座線と中心も毎フレーム引き直していたので、星より重い処理が描画のたびに走っていた。
     * 追従中の描き直しが 1 秒に 1 枚しか出せない以上、ここは削れるだけ削る。
     */
    private class Precessed(
        val stars: Array<DoubleArray>,
        val lines: List<List<List<DoubleArray>>>,
        val centers: Array<DoubleArray?>,
    )

    /** 空に出ている星座の一覧は描画と別のコルーチンから来るので、作り直しは 1 本に絞る */
    @Synchronized
    private fun precessed(d: Double): Precessed {
        val key = (d / 30.0).toLong()
        cache?.let { if (key == precessedKey) return it }
        val stars = Array(catalog.stars.size) { i ->
            val s = catalog.stars[i]
            precess(s.raDeg, s.decDeg, d)
        }
        val lines = catalog.constellations.map { c ->
            c.lines.map { seg -> seg.map { precess(it[0], it[1], d) } }
        }
        val centers = Array(catalog.constellations.size) { i -> meanDirection(lines[i]) }
        precessedKey = key
        return Precessed(stars, lines, centers).also { cache = it }
    }

    /**
     * 星座の中心方向を赤道座標のまま出す。
     *
     * 地平座標への変換は回転なので、頂点を全部回してから平均しても、平均してから回しても同じ。
     * 先に平均しておけば、1 フレームあたり星座 1 個につき 1 回の変換で済む。
     */
    private fun meanDirection(lines: List<List<DoubleArray>>): DoubleArray? {
        var sx = 0.0
        var sy = 0.0
        var sz = 0.0
        var n = 0
        for (seg in lines) {
            for (p in seg) {
                val v = enu(p[0], p[1])
                sx += v.x
                sy += v.y
                sz += v.z
                n++
            }
        }
        if (n == 0) return null
        if (hypot(hypot(sx, sy), sz) < 1e-9) return null
        val u = Vec3(sx, sy, sz).normalized()
        val dec = Math.toDegrees(kotlin.math.asin(u.z.coerceIn(-1.0, 1.0)))
        val ra = (Math.toDegrees(kotlin.math.atan2(u.x, u.y)) + 360.0) % 360.0
        return doubleArrayOf(ra, dec)
    }

    /** 星座名は画像に焼かず、視野中心に近い順に maxLabels 個だけ返す（sendCanvas は 8 要素まで） */
    private fun labels(
        precessed: Precessed,
        lst: Double,
        site: Site,
        basis: Basis,
        k: Double,
        width: Int,
        height: Int,
        maxLabels: Int,
    ): List<Label> {
        if (maxLabels <= 0) return emptyList()
        val cx = width / 2.0
        val cy = height / 2.0

        fun screen(raDec: DoubleArray): DoubleArray? {
            val aa = toAltAz(raDec[0], raDec[1], lst, site.latDeg)
            val q = project(enu(aa[0], aa[1]), basis, k, width, height) ?: return null
            return if (q[0] < 0 || q[1] < 0 || q[0] > width || q[1] > height) null else q
        }

        val found = ArrayList<Pair<Double, Label>>()
        for (i in catalog.constellations.indices) {
            val center = precessed.centers[i] ?: continue
            // 大きい星座は半分だけ視野に入ることが多い。中心が外に出ているなら、
            // 見えている頂点のうち視野中心にいちばん近いところに名前を置く
            val q = screen(center) ?: precessed.lines[i]
                .flatten()
                .mapNotNull { screen(it) }
                .minByOrNull { hypot(it[0] - cx, it[1] - cy) }
                ?: continue
            val dist = hypot(q[0] - cx, q[1] - cy)
            found += dist to Label(catalog.constellations[i].nameJa, q[0].roundToInt(), q[1].roundToInt())
        }
        return found.sortedBy { it.first }.take(maxLabels).map { it.second }
    }

    /** 軌跡を折れ線で描く。点はすでに方位・高度なので、投影して結ぶだけ */
    private fun drawTrack(
        gray: ByteArray,
        width: Int,
        height: Int,
        track: SkyTrack,
        basis: Basis,
        k: Double,
    ) {
        var prev: DoubleArray? = null
        for (p in track.points) {
            val q = project(enu(p[0], p[1]), basis, k, width, height)
            if (q == null) {
                prev = null
                continue
            }
            prev?.let { line(gray, width, height, it, q, TRACK_VALUE, lineRadius(width)) }
            prev = q
        }
    }

    /**
     * 星座線は投影後の直線ではなく大円。ステレオ投影では円弧になるので
     * 3° ごとの折れ線に割って描く（仕様どおり、実用上の差は無い）。
     */
    private fun drawGreatCircle(
        gray: ByteArray,
        width: Int,
        height: Int,
        from: DoubleArray,
        to: DoubleArray,
        lst: Double,
        site: Site,
        basis: Basis,
        k: Double,
    ) {
        val a = toAltAz(from[0], from[1], lst, site.latDeg)
        val b = toAltAz(to[0], to[1], lst, site.latDeg)
        val va = enu(a[0], a[1])
        val vb = enu(b[0], b[1])
        val ang = acos((va dot vb).coerceIn(-1.0, 1.0))
        val steps = max(2, ceil(ang * DEG / 3.0).toInt())
        var prev: DoubleArray? = null
        for (i in 0..steps) {
            val f = i.toDouble() / steps
            val s = sin(ang)
            val v = if (s < 1e-9) {
                va
            } else {
                val w0 = sin((1 - f) * ang) / s
                val w1 = sin(f * ang) / s
                Vec3(va.x * w0 + vb.x * w1, va.y * w0 + vb.y * w1, va.z * w0 + vb.z * w1)
            }
            val q = project(v, basis, k, width, height)
            if (q == null) {
                prev = null
                continue
            }
            prev?.let { line(gray, width, height, it, q, LINE_VALUE, lineRadius(width)) }
            prev = q
        }
    }

    private fun lineRadius(width: Int): Int = if (width >= 400) 1 else 0

    private fun line(gray: ByteArray, w: Int, h: Int, a: DoubleArray, b: DoubleArray, value: Int, radius: Int) {
        val dx = b[0] - a[0]
        val dy = b[1] - a[1]
        val steps = max(1, ceil(max(kotlin.math.abs(dx), kotlin.math.abs(dy))).toInt())
        if (steps > 4 * (w + h)) return // 視野の裏側へ回り込んだ線分は捨てる
        for (i in 0..steps) {
            val f = i.toDouble() / steps
            dot(gray, w, h, a[0] + dx * f, a[1] + dy * f, value, radius)
        }
    }

    /**
     * 点を打つ。星は丸く（`round`）、線は四角のまま。
     *
     * 528px だと一番明るい星の半径が 8px になり、四角のままでは 17×17 の塊に見える。
     * 丸にすると画素が 3 割減るので、見た目だけでなく転送量も下がる。
     */
    private fun dot(
        gray: ByteArray,
        w: Int,
        h: Int,
        x: Double,
        y: Double,
        value: Int,
        radius: Int,
        round: Boolean = false,
    ) {
        val cx = x.roundToInt()
        val cy = y.roundToInt()
        val r2 = radius * radius + radius
        for (oy in -radius..radius) {
            for (ox in -radius..radius) {
                if (round && ox * ox + oy * oy > r2) continue
                val px = cx + ox
                val py = cy + oy
                if (px < 0 || py < 0 || px >= w || py >= h) continue
                val idx = py * w + px
                val current = gray[idx].toInt() and 0xFF
                gray[idx] = min(255, max(current, value)).toByte()
            }
        }
    }

    private companion object {
        /** 星座線は星より暗く。転送量の半分以上を占めるので、間に合わないときはここを間引く */
        const val LINE_VALUE = 110

        /** 衛星の軌跡は星座線より明るく。同じ濃さだと空の模様と見分けが付かない */
        const val TRACK_VALUE = 200
    }
}
