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

/**
 * 星図を 1 画素 1 バイトのグレースケールに描く。
 * 3bit への量子化と RLE 圧縮は SDK 側がやるので、ここでは 0-255 のまま置く。
 *
 * 緑 8 階調しか出ないので、等級は明るさだけでなく点の大きさと組み合わせる。
 */
class StarMapRenderer(private val catalog: StarCatalog) {

    /** 歳差は 26 年で 0.36° なので毎フレーム引き直す必要はない。30 日ぶんまとめる */
    private var precessedKey = Long.MIN_VALUE
    private var precessed: Array<DoubleArray> = emptyArray()

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
    ): StarMap {
        val d = daysFromJ2000(epochMillis)
        ensurePrecessed(d)
        val lst = localSiderealDeg(d, site.lonDeg)
        val basis = Basis(look.azDeg, look.altDeg)
        val k = projectionScale(width, fovDeg)
        val gray = ByteArray(width * height)

        if (drawLines) {
            for (c in catalog.constellations) {
                for (seg in c.lines) {
                    for (i in 0 until seg.size - 1) {
                        drawGreatCircle(gray, width, height, seg[i], seg[i + 1], d, lst, site, basis, k)
                    }
                }
            }
        }

        for (i in catalog.stars.indices) {
            val star = catalog.stars[i]
            if (star.magnitude > limitMagnitude) continue
            val p = precessed[i]
            val aa = toAltAz(p[0], p[1], lst, site.latDeg)
            val q = project(enu(aa[0], aa[1]), basis, k, width, height) ?: continue
            if (q[0] < -4 || q[1] < -4 || q[0] > width + 4 || q[1] > height + 4) continue
            // 明るいほど大きく、明るく。8 階調では明るさだけだと潰れる
            val t = ((limitMagnitude - star.magnitude) / (limitMagnitude + 1.5)).coerceIn(0.0, 1.0)
            val value = (255.0 * (0.35 + 0.65 * t)).roundToInt()
            val radius = if (t < 0.35) 0 else if (t < 0.7) 1 else 2
            dot(gray, width, height, q[0], q[1], value, radius)
        }

        return StarMap(width, height, gray, labels(d, lst, site, basis, k, width, height, maxLabels))
    }

    private fun ensurePrecessed(d: Double) {
        val key = (d / 30.0).toLong()
        if (key == precessedKey) return
        precessedKey = key
        precessed = Array(catalog.stars.size) { i ->
            val s = catalog.stars[i]
            precess(s.raDeg, s.decDeg, d)
        }
    }

    /** 星座名は画像に焼かず、視野中心に近い順に maxLabels 個だけ返す（sendCanvas は 8 要素まで） */
    private fun labels(
        d: Double,
        lst: Double,
        site: Site,
        basis: Basis,
        k: Double,
        width: Int,
        height: Int,
        maxLabels: Int,
    ): List<Label> {
        if (maxLabels <= 0) return emptyList()
        val found = ArrayList<Pair<Double, Label>>()
        for (c in catalog.constellations) {
            var sx = 0.0
            var sy = 0.0
            var sz = 0.0
            var n = 0
            for (seg in c.lines) {
                for (pt in seg) {
                    val p = precess(pt[0], pt[1], d)
                    val aa = toAltAz(p[0], p[1], lst, site.latDeg)
                    val v = enu(aa[0], aa[1])
                    sx += v.x
                    sy += v.y
                    sz += v.z
                    n++
                }
            }
            if (n == 0) continue
            val center = Vec3(sx, sy, sz)
            if (hypot(hypot(center.x, center.y), center.z) < 1e-9) continue
            val q = project(center.normalized(), basis, k, width, height) ?: continue
            // 端に寄った名前は切れて読めないので落とす
            if (q[0] < 40 || q[1] < 12 || q[0] > width - 40 || q[1] > height - 12) continue
            val dist = hypot(q[0] - width / 2.0, q[1] - height / 2.0)
            found += dist to Label(c.nameJa, q[0].roundToInt(), q[1].roundToInt())
        }
        return found.sortedBy { it.first }.take(maxLabels).map { it.second }
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
        d: Double,
        lst: Double,
        site: Site,
        basis: Basis,
        k: Double,
    ) {
        val a = precess(from[0], from[1], d).let { toAltAz(it[0], it[1], lst, site.latDeg) }
        val b = precess(to[0], to[1], d).let { toAltAz(it[0], it[1], lst, site.latDeg) }
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
            prev?.let { line(gray, width, height, it, q, LINE_VALUE) }
            prev = q
        }
    }

    private fun line(gray: ByteArray, w: Int, h: Int, a: DoubleArray, b: DoubleArray, value: Int) {
        val dx = b[0] - a[0]
        val dy = b[1] - a[1]
        val steps = max(1, ceil(max(kotlin.math.abs(dx), kotlin.math.abs(dy))).toInt())
        if (steps > 4 * (w + h)) return // 視野の裏側へ回り込んだ線分は捨てる
        for (i in 0..steps) {
            val f = i.toDouble() / steps
            dot(gray, w, h, a[0] + dx * f, a[1] + dy * f, value, 0)
        }
    }

    private fun dot(gray: ByteArray, w: Int, h: Int, x: Double, y: Double, value: Int, radius: Int) {
        val cx = x.roundToInt()
        val cy = y.roundToInt()
        for (oy in -radius..radius) {
            for (ox in -radius..radius) {
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
        const val LINE_VALUE = 96
    }
}
