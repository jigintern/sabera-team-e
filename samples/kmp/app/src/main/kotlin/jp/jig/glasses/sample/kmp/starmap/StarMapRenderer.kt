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
        // 人工衛星モードでは星を出さない。星と衛星の点が同じ緑 8 階調なので、
        // 重ねると「どれが衛星か」が分からなくなる（星座モードは逆に衛星を渡さない）
        drawStars: Boolean = true,
        // 主役 1 機の輪郭を出すか。実機で読めるかを確かめられるよう切れるようにしてある
        drawFigures: Boolean = true,
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

        if (drawStars) {
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
        }

        // **衛星は「大体どの辺にいるか」の点だけ。軌跡の線は描かない**（決定。satellites.md）。
        // 線を引くと画面が線で埋まるだけで、どれが衛星かが読めなかった
        for (track in tracks) {
            val q = project(enu(track.nowAzDeg, track.nowAltDeg), basis, k, width, height) ?: continue
            if (q[0] >= 0 && q[1] >= 0 && q[0] <= width && q[1] <= height) {
                dot(gray, width, height, q[0], q[1], 255, (3.0 * width / 196.0).roundToInt(), round = true)
            }
        }
        // 点 → 引き出し線 → 枠つきアイコン。名前はキャンバスのテキストで枠の上に重なる
        val callouts = if (drawFigures) callouts(basis, k, width, height, tracks) else emptyList()
        for (callout in callouts) {
            leader(gray, width, height, callout)
            frame(gray, width, height, callout.box, callout.size)
            drawFigure(
                gray, width, height, SatelliteFigure.of(callout.track.name),
                doubleArrayOf(callout.box[0] + callout.size * ICON_PAD, callout.box[1] + callout.size * ICON_PAD),
                (callout.size * (1.0 - 2 * ICON_PAD)).roundToInt(),
            )
        }

        // 名前の置き場所は上で決めた吹き出しに合わせる（同じ引数なので同じ答えになる）
        val trackLabels = trackLabels(look, fovDeg, width, height, tracks, drawFigures)

        val starLabels = if (drawStars) {
            labels(precessed, lst, site, basis, k, width, height, maxLabels)
        } else {
            emptyList()
        }
        // 衛星の名前を先に置く。枠が足りないときに消えるのは星座名のほう
        val merged = (trackLabels + starLabels).take(maxLabels.coerceAtLeast(trackLabels.size))
        return StarMap(width, height, gray, merged)
    }

    /**
     * 視線に近い順に星座名を返す。AI に「いま何を見ているか」を伝えるために使う。
     *
     * **これは近似。** 仕様（coordinate-system.md ⑦）が求めるのは IAU 境界による判定で、
     * そちらは天球を隙間なく分割するので属する星座が一意に決まる。ここでは星座線までの
     * 角距離が最小の星座を返しているだけなので、線の無い暗い領域では答えがずれる。
     * 境界表（Roman 1987）を入れるときは**この関数の中身だけ差し替えれば済む**。
     */
    fun constellationsNear(site: Site, epochMillis: Long, look: Look, max: Int = 4): List<String> {
        val d = daysFromJ2000(epochMillis)
        val lst = localSiderealDeg(d, site.lonDeg)
        val precessed = precessed(d)
        val target = enu(look.azDeg, look.altDeg)

        fun sky(raDec: DoubleArray): Vec3 {
            val aa = toAltAz(raDec[0], raDec[1], lst, site.latDeg)
            return enu(aa[0], aa[1])
        }

        val scored = ArrayList<Pair<Double, String>>(catalog.constellations.size)
        for (i in catalog.constellations.indices) {
            var nearest = -2.0
            for (seg in precessed.lines[i]) {
                for (j in seg.indices) {
                    val v = sky(seg[j])
                    if ((v dot target) > nearest) nearest = v dot target
                    // 頂点だけ見ると、長い星座線が視線のすぐ脇を通っていても拾えない。
                    // 星座は数十度に広がるので、5° ごとに刻めば取りこぼさない
                    if (j + 1 < seg.size) {
                        val a = v
                        val b = sky(seg[j + 1])
                        val ang = acos((a dot b).coerceIn(-1.0, 1.0))
                        val steps = ceil(ang * DEG / 5.0).toInt()
                        val s = sin(ang)
                        if (steps > 1 && s >= 1e-9) {
                            for (k in 1 until steps) {
                                val f = k.toDouble() / steps
                                val w0 = sin((1 - f) * ang) / s
                                val w1 = sin(f * ang) / s
                                val m = Vec3(
                                    a.x * w0 + b.x * w1,
                                    a.y * w0 + b.y * w1,
                                    a.z * w0 + b.z * w1,
                                ).normalized()
                                if ((m dot target) > nearest) nearest = m dot target
                            }
                        }
                    }
                }
            }
            // 星座線を持たない星座は中心で代用する
            if (nearest <= -2.0) {
                val center = precessed.centers[i] ?: continue
                nearest = sky(center) dot target
            }
            scored += nearest to catalog.constellations[i].nameJa
        }
        return scored.sortedByDescending { it.first }.take(max).map { it.second }
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

    /**
     * 衛星のいまの位置を、画像の上のどこに置くかだけ出す。
     *
     * **画像を焼き直さずにテキストだけ送るため**に切り出してある。
     * 衛星は 1 秒に 1° 動くのに画像は 1 枚 0.5 秒かかるので、
     * 首が止まっている間はここだけを送り直す。
     *
     * `look` は**画像を描いたときの視線**を渡す。いまの視線ではない
     * （画像がその向きで焼かれているので、印もその座標系に乗せる必要がある）。
     */
    fun trackLabels(
        look: Look,
        fovDeg: Double,
        width: Int,
        height: Int,
        tracks: List<SkyTrack>,
        drawFigures: Boolean = true,
    ): List<Label> {
        val basis = Basis(look.azDeg, look.altDeg)
        val k = projectionScale(width, fovDeg)
        // 吹き出しを出す機体は、名前も枠の上に置く。**同じ計算を使わないと絵と名前がずれる**
        val callouts = if (drawFigures) callouts(basis, k, width, height, tracks) else emptyList()
        val labels = ArrayList<Label>()
        for (track in tracks) {
            if (!track.labelled) continue
            val callout = callouts.firstOrNull { it.track === track }
            // **吹き出しが無い機体には名前を出さない。** 枠の上に出る名前と、点のところに出る
            // 名前が近くに並ぶと、重なったほうが落ちて「枠だけ・名前だけ」になる。
            // 輪郭を切っているときは、名前は点のところに出す（それしか手が無い）
            if (drawFigures && callout == null) continue
            val at = callout?.label
                ?: project(enu(track.nowAzDeg, track.nowAltDeg), basis, k, width, height)
                ?: continue
            if (at[0] < 0 || at[1] < 0 || at[0] > width || at[1] > height) continue
            // 日が当たっているものは塗り、影のものは輪郭。肉眼で見えるかどうかの区別
            val mark = if (track.sunlit) "●" else "○"
            // **「あと何分で最接近」を名前の後ろに足す。** 点だけでは待てばいいのか分からない。
            // 近づいているときだけ出す（過ぎた機体に数字を出しても意味がない）。
            // 文字数が増えるとラベルが重なって落ちるので、10 分以内に絞る
            val soon = track.motion?.closestInMinutes
                ?.takeIf { it > 0.0 && it <= LABEL_SOON_MIN }
                ?.let { " ${max(1, ceil(it).toInt())}分" }
                .orEmpty()
            labels += Label(mark + track.name + soon, at[0].roundToInt(), at[1].roundToInt())
        }
        return labels
    }

    /**
     * 名前つき衛星 1 機ぶんの吹き出し。
     *
     * **点 →（斜め ＋ 横の）引き出し線 → 枠つきアイコン、その上に名前**の並び
     * （手描きのデザインどおり）。名前は画像に焼かずキャンバスのテキストで重ねるので、
     * ここでは**名前を置く点**だけ返す。
     */
    private class Callout(
        val track: SkyTrack,
        /** 衛星の位置。ここが「大体どの辺にいるか」 */
        val dot: DoubleArray,
        /** アイコンの枠。左上の座標 */
        val box: DoubleArray,
        val size: Int,
        /** 名前を置く点（枠の上、中央） */
        val label: DoubleArray,
    )

    /**
     * 吹き出しの置き場所を決める。**描画とラベルで同じ答えが要る**ので、
     * [render] と [trackLabels] の両方からこれを呼ぶ（別々に決めると絵と名前がずれる）。
     *
     * 斜め 4 方向を順に試して、名前ごと画像に収まる最初の場所を取る。
     * すでに置いた吹き出しや、ほかの機体の点に重なるなら諦める
     * （無理に出すより出さないほうがよい）。
     */
    private fun callouts(
        basis: Basis,
        k: Double,
        width: Int,
        height: Int,
        tracks: List<SkyTrack>,
    ): List<Callout> {
        val cx = width / 2.0
        val cy = height / 2.0
        // 視野中心に近い順。混み合ったときに残すのは真ん中の機体
        val candidates = tracks.asSequence()
            .filter { it.labelled }
            .mapNotNull { track ->
                val q = project(enu(track.nowAzDeg, track.nowAltDeg), basis, k, width, height)
                if (q == null || q[0] < 0 || q[1] < 0 || q[0] > width || q[1] > height) null else track to q
            }
            .sortedBy { (_, q) -> hypot(q[0] - cx, q[1] - cy) }
            .toList()

        val size = (width * 0.13).roundToInt().coerceIn(32, 76)
        val diagonal = size * ELBOW_DIAGONAL
        val stem = size * ELBOW_STEM
        val out = ArrayList<Callout>()
        for ((track, dot) in candidates) {
            if (out.size >= FIGURE_SLOTS) break
            // 1 機目と 2 機目で最初に試す向きを変える。同じ側に寄せると名前が重なる
            val sides = if (out.size % 2 == 0) ELBOW_SIDES else ELBOW_SIDES.drop(1) + ELBOW_SIDES.first()
            for ((sx, sy) in sides) {
                // 引き出し線の折れ点までの伸び。横は枠の手前まで
                val endX = dot[0] + sx * (LEADER_GAP + diagonal + stem)
                val endY = dot[1] + sy * (LEADER_GAP + diagonal)
                val box = doubleArrayOf(if (sx > 0) endX else endX - size, endY - size / 2.0)
                // 名前は枠の上に出るので、そのぶんの余白も要る
                if (box[0] < 2.0 || box[0] + size > width - 2) continue
                if (box[1] - LABEL_BOX_HEIGHT < 2.0 || box[1] + size > height - 2) continue
                if (out.any { overlaps(it.box, box, size) }) continue
                if (candidates.any { (_, other) -> other !== dot && covers(box, size, other) }) continue
                out += Callout(
                    track = track,
                    dot = dot,
                    box = box,
                    size = size,
                    label = doubleArrayOf(box[0] + size / 2.0, box[1] - LABEL_BOX_HEIGHT / 2.0 - 2.0),
                )
                break
            }
        }
        return out
    }

    private fun overlaps(a: DoubleArray, b: DoubleArray, size: Int): Boolean =
        a[0] < b[0] + size && b[0] < a[0] + size && a[1] < b[1] + size && b[1] < a[1] + size

    private fun covers(box: DoubleArray, size: Int, point: DoubleArray): Boolean =
        point[0] >= box[0] && point[0] <= box[0] + size && point[1] >= box[1] && point[1] <= box[1] + size

    /** 点から枠へ。**斜めに離してから横に振る**（手描きのデザインどおり） */
    private fun leader(gray: ByteArray, width: Int, height: Int, callout: Callout) {
        val dot = callout.dot
        val toRight = callout.box[0] > dot[0]
        val nearX = if (toRight) callout.box[0] else callout.box[0] + callout.size
        val endY = callout.box[1] + callout.size / 2.0
        val sx = if (toRight) 1.0 else -1.0
        val sy = if (endY < dot[1]) -1.0 else 1.0
        val diagonal = callout.size * ELBOW_DIAGONAL
        val from = doubleArrayOf(dot[0] + sx * LEADER_GAP, dot[1] + sy * LEADER_GAP)
        val corner = doubleArrayOf(dot[0] + sx * (LEADER_GAP + diagonal), dot[1] + sy * (LEADER_GAP + diagonal))
        line(gray, width, height, from, corner, LEADER_VALUE, 0)
        line(gray, width, height, corner, doubleArrayOf(nearX, corner[1]), LEADER_VALUE, 0)
    }

    /** アイコンを囲む角丸の枠。枠があると「これは実景ではない」と一目で分かる */
    private fun frame(gray: ByteArray, width: Int, height: Int, box: DoubleArray, size: Int) {
        val r = size * BOX_RADIUS
        val x0 = box[0]
        val y0 = box[1]
        val x1 = box[0] + size
        val y1 = box[1] + size
        line(gray, width, height, doubleArrayOf(x0 + r, y0), doubleArrayOf(x1 - r, y0), FRAME_VALUE, 0)
        line(gray, width, height, doubleArrayOf(x0 + r, y1), doubleArrayOf(x1 - r, y1), FRAME_VALUE, 0)
        line(gray, width, height, doubleArrayOf(x0, y0 + r), doubleArrayOf(x0, y1 - r), FRAME_VALUE, 0)
        line(gray, width, height, doubleArrayOf(x1, y0 + r), doubleArrayOf(x1, y1 - r), FRAME_VALUE, 0)
        // 角は 4 分割の折れ線で十分。半径 14px の円弧に精度は要らない
        val corners = listOf(
            Triple(x0 + r, y0 + r, 180.0),
            Triple(x1 - r, y0 + r, 270.0),
            Triple(x1 - r, y1 - r, 0.0),
            Triple(x0 + r, y1 - r, 90.0),
        )
        for ((ccx, ccy, from) in corners) {
            var prev: DoubleArray? = null
            for (i in 0..4) {
                val a = (from + i * 22.5) * RAD
                val p = doubleArrayOf(ccx + r * kotlin.math.cos(a), ccy + r * sin(a))
                prev?.let { line(gray, width, height, it, p, FRAME_VALUE, 0) }
                prev = p
            }
        }
    }

    private fun drawFigure(
        gray: ByteArray,
        width: Int,
        height: Int,
        figure: SatelliteFigure,
        box: DoubleArray,
        size: Int,
    ) {
        // 外形だけ太くする。桟まで太くすると 96 画素では潰れる
        val thick = if (size >= 80) 1 else 0
        for (stroke in figure.strokes) {
            val value = if (stroke.strong) FIGURE_VALUE else FIGURE_INNER_VALUE
            val radius = if (stroke.strong) thick else 0
            val pts = stroke.points.map { doubleArrayOf(box[0] + it[0] * size, box[1] + it[1] * size) }
            for (i in 0 until pts.size - 1) {
                line(gray, width, height, pts[i], pts[i + 1], value, radius)
            }
            if (stroke.closed && pts.size > 2) {
                line(gray, width, height, pts.last(), pts.first(), value, radius)
            }
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

        /** 輪郭の外形。いちばん明るくして「これは実景ではない」と分かるようにする */
        const val FIGURE_VALUE = 255

        /** パネルの桟。外形と同じ明るさだと、96 画素では 1 枚の板に見える */
        const val FIGURE_INNER_VALUE = 120

        /** 引き出し線を点から離す距離[画素]。点（半径 8px）に線がくっつくと位置が読めない */
        const val LEADER_GAP = 14.0

        /** 引き出し線の斜めと横の長さ（枠の一辺に対する比） */
        const val ELBOW_DIAGONAL = 0.45
        const val ELBOW_STEM = 0.35

        /** 引き出し線と枠。輪郭より暗くして、輪郭が主役に見えるようにする */
        const val LEADER_VALUE = 150
        const val FRAME_VALUE = 170

        /** 枠の角の丸み（一辺に対する比） */
        const val BOX_RADIUS = 0.2

        /** 枠の内側の余白（一辺に対する比）。輪郭が枠に触ると読めない */
        const val ICON_PAD = 0.16

        /**
         * 名前 1 行の高さ[画素]。**`StarMapScreen` の `LABEL_HEIGHT` と揃える。**
         * 名前はキャンバスのテキストとして枠の上に出るので、そのぶんの余白を空けて置く
         */
        const val LABEL_BOX_HEIGHT = 40.0

        /** 引き出し線を出す向き。上→下、右→左の順に試す */
        val ELBOW_SIDES = listOf(
            1.0 to -1.0,
            -1.0 to -1.0,
            1.0 to 1.0,
            -1.0 to 1.0,
        )

        /** ラベルに「あと何分」を出す上限。これより先の最接近は書かない */
        const val LABEL_SOON_MIN = 10.0

        /**
         * 吹き出しを出す数。
         *
         * **名前は枠の上に出るので、枠が近いと名前どうしが重なって片方が消える**
         * （キャンバスのテキストは重なったほうを落とす）。2 つに絞ると collision がほぼ起きない。
         * 3 機目以降は名前だけを点のところに出す。
         */
        const val FIGURE_SLOTS = 2

    }
}
