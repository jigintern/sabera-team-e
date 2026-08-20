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
        // 輪郭は点のすぐ近くに置く（引き出し線も要らない）
        if (drawFigures) drawFigures(gray, width, height, basis, k, tracks)

        val trackLabels = trackLabels(look, fovDeg, width, height, tracks)

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
    ): List<Label> {
        val basis = Basis(look.azDeg, look.altDeg)
        val k = projectionScale(width, fovDeg)
        val labels = ArrayList<Label>()
        for (track in tracks) {
            if (!track.labelled) continue
            val q = project(enu(track.nowAzDeg, track.nowAltDeg), basis, k, width, height) ?: continue
            if (q[0] < 0 || q[1] < 0 || q[0] > width || q[1] > height) continue
            // 日が当たっているものは塗り、影のものは輪郭。肉眼で見えるかどうかの区別
            val mark = if (track.sunlit) "●" else "○"
            // **「あと何分で最接近」を名前の後ろに足す。** 点だけでは待てばいいのか分からない。
            // 近づいているときだけ出す（過ぎた機体に数字を出しても意味がない）。
            // 文字数が増えるとラベルが重なって落ちるので、10 分以内に絞る
            val soon = track.motion?.closestInMinutes
                ?.takeIf { it > 0.0 && it <= LABEL_SOON_MIN }
                ?.let { " ${max(1, ceil(it).toInt())}分" }
                .orEmpty()
            labels += Label(mark + track.name + soon, q[0].roundToInt(), q[1].roundToInt())
        }
        return labels
    }

    /**
     * 名前つき衛星の輪郭を、**その点のすぐ上（入らなければ下）**に置く。
     *
     * 実物大なら 0.24 画素しかないので、輪郭は大きさの嘘を承知で出すアイコン
     * （理由は [SatelliteFigure]）。**点が「大体どの辺にいるか」で、輪郭が「何が飛んでいるか」。**
     *
     * 横に逃がさないのは、**名前のテキストが点の左右に伸びる**から
     * （キャンバスのテキストは点を中心に、文字数ぶんの幅で置かれる）。
     * 縦に [LABEL_CLEARANCE] だけ空けると、名前とも重ならない。
     */
    private fun drawFigures(
        gray: ByteArray,
        width: Int,
        height: Int,
        basis: Basis,
        k: Double,
        tracks: List<SkyTrack>,
    ) {
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
        val placed = ArrayList<DoubleArray>()
        for ((track, at) in candidates) {
            if (placed.size >= FIGURE_SLOTS) break
            val box = placeFigure(width, height, at, size, placed, candidates.map { it.second }) ?: continue
            drawFigure(gray, width, height, SatelliteFigure.of(track.name), box, size)
            placed += box
        }
    }

    /**
     * 輪郭の置き場所。点の上 → 下の順に試す。
     * すでに置いた輪郭や、ほかの衛星の点に重なるなら諦める（無理に出すより出さないほうがよい）。
     */
    private fun placeFigure(
        width: Int,
        height: Int,
        at: DoubleArray,
        size: Int,
        placed: List<DoubleArray>,
        dots: List<DoubleArray>,
    ): DoubleArray? {
        // 端の近くでも点の真上に置けるよう、横だけは画像の中へ寄せる
        val x = (at[0] - size / 2.0).coerceIn(2.0, (width - size - 2).toDouble())
        for (y in listOf(at[1] - LABEL_CLEARANCE - size, at[1] + LABEL_CLEARANCE)) {
            if (y < 2.0 || y + size > height - 2) continue
            val box = doubleArrayOf(x, y)
            if (placed.any { overlaps(it, box, size) }) continue
            // 自分の点は下（または上）にあるので入らない。ほかの機体の点を隠すのは避ける
            if (dots.any { it !== at && covers(box, size, it) }) continue
            return box
        }
        return null
    }

    private fun overlaps(a: DoubleArray, b: DoubleArray, size: Int): Boolean =
        a[0] < b[0] + size && b[0] < a[0] + size && a[1] < b[1] + size && b[1] < a[1] + size

    private fun covers(box: DoubleArray, size: Int, point: DoubleArray): Boolean =
        point[0] >= box[0] && point[0] <= box[0] + size && point[1] >= box[1] && point[1] <= box[1] + size

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

        /** ラベルに「あと何分」を出す上限。これより先の最接近は書かない */
        const val LABEL_SOON_MIN = 10.0

        /** 輪郭を出す数。点のそばに置くので、多いと点と輪郭の対応が読めなくなる */
        const val FIGURE_SLOTS = 3

        /**
         * 点から輪郭までの縦の間隔[画素]。
         *
         * 名前は**キャンバスのテキスト（高さ 40、点を中心に置く）**として画像の手前に出るので、
         * その半分より外へ逃がさないと名前の上に輪郭が乗る。
         */
        const val LABEL_CLEARANCE = 26.0
    }
}
