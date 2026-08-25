package jp.jig.glasses.sample.kmp.glass

import jp.jig.glasses.sample.kmp.catalog.ConstellationFigure
import jp.jig.glasses.sample.kmp.catalog.StarCatalog
import jp.jig.glasses.sample.kmp.satellite.SkyTrack
import jp.jig.glasses.sample.kmp.sky.Basis
import jp.jig.glasses.sample.kmp.sky.DEG
import jp.jig.glasses.sample.kmp.sky.GuidanceTarget
import jp.jig.glasses.sample.kmp.sky.GuidanceTargetKind
import jp.jig.glasses.sample.kmp.sky.Look
import jp.jig.glasses.sample.kmp.sky.ObservedStarFact
import jp.jig.glasses.sample.kmp.sky.RAD
import jp.jig.glasses.sample.kmp.sky.Site
import jp.jig.glasses.sample.kmp.sky.SkyDensity
import jp.jig.glasses.sample.kmp.sky.Vec3
import jp.jig.glasses.sample.kmp.sky.cardinalDirection16
import jp.jig.glasses.sample.kmp.sky.daysFromJ2000
import jp.jig.glasses.sample.kmp.sky.enu
import jp.jig.glasses.sample.kmp.sky.geometricAltitudeDeg
import jp.jig.glasses.sample.kmp.sky.localSiderealDeg
import jp.jig.glasses.sample.kmp.sky.moonPhase
import jp.jig.glasses.sample.kmp.sky.normalizeDeg
import jp.jig.glasses.sample.kmp.sky.precess
import jp.jig.glasses.sample.kmp.sky.precessDateToB1875
import jp.jig.glasses.sample.kmp.sky.project
import jp.jig.glasses.sample.kmp.sky.projectionScale
import jp.jig.glasses.sample.kmp.sky.rollFromAccel
import jp.jig.glasses.sample.kmp.sky.sunPosition
import jp.jig.glasses.sample.kmp.sky.toAltAz
import jp.jig.glasses.sample.kmp.sky.toApparentAltAz
import jp.jig.glasses.sample.kmp.sky.toRaDec
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** いま空に出ている星座と、その方角。方位合わせをせずに試すために使う */
data class Aimed(val nameJa: String, val azDeg: Double, val altDeg: Double) {
    /** 「南南西 高度 45°」のような表示 */
    val where: String
        get() {
            return "${cardinalDirection16(azDeg)} 高度 ${altDeg.toInt()}°"
        }
}

/**
 * 星図を 1 画素 1 バイトのグレースケールに描く。
 * 3bit への量子化と RLE 圧縮は SDK 側がやるので、ここでは 0-255 のまま置く。
 *
 * 緑 8 階調しか出ないので、等級は明るさだけでなく点の大きさと組み合わせる。
 */
class StarMapRenderer(private val catalog: StarCatalog) {

    /**
     * 星座ごとの「いちばん明るい星」の等級。**空の濃さで星座を間引くために使う。**
     *
     * 星座線のデータは星 ID ではなく座標なので、**頂点の近くにある星から拾う**。
     * J2000 の座標だけで決まる（時刻に依存しない）ので 1 回で済む。
     */
    @Volatile
    private var constellationAnchorCache: IntArray? = null

    /** 星座線の頂点に最も近い星のうち、いちばん明るいものを案内の入口にも使う。 */
    private fun constellationAnchors(): IntArray = constellationAnchorCache ?: IntArray(
        catalog.constellations.size,
    ) { i ->
        var bestIndex = -1
        var bestMagnitude = UNKNOWN_MAGNITUDE
        for (seg in catalog.constellations[i].lines) {
            for (point in seg) {
                for ((index, star) in catalog.stars.withIndex()) {
                    if (star.magnitude >= bestMagnitude) continue
                    if (kotlin.math.abs(star.decDeg - point[1]) > VERTEX_MATCH_DEG) continue
                    val dRa = normalizeDeg(star.raDeg - point[0]) * kotlin.math.cos(point[1] * RAD)
                    if (kotlin.math.abs(dRa) > VERTEX_MATCH_DEG) continue
                    bestIndex = index
                    bestMagnitude = star.magnitude
                }
            }
        }
        bestIndex
    }.also { constellationAnchorCache = it }

    private fun brightestPerConstellation(): DoubleArray = constellationAnchors().map { index ->
        if (index < 0) UNKNOWN_MAGNITUDE else catalog.stars[index].magnitude
    }.toDoubleArray()

    /** HIP → 星表の添字。結びは HIP で星を指すので、引くたびに探さないよう 1 回だけ作る */
    @Volatile
    private var hipIndexCache: Map<Int, Int>? = null

    private fun hipIndex(): Map<Int, Int> = hipIndexCache ?: buildMap {
        catalog.stars.forEachIndexed { index, star -> put(star.hip, index) }
    }.also { hipIndexCache = it }

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
        // 月・惑星。星と同じ空のものなので、衛星モードでは渡さない
        bodies: List<SkyBodyMark> = emptyList(),
        // 星座絵（星座線だけでは「なにに見立てたのか」が伝わらない）
        drawFigureArt: Boolean = true,
        // 大三角などの結び。**初心者が空で最初に見つけるもの**
        drawAsterisms: Boolean = true,
        // 天の川の帯
        drawMilkyWay: Boolean = true,
        /**
         * 首の傾き[度]。**パネルは頭に固定されている**ので、傾けたぶん枠ごと回さないと
         * 地平線だけが水平のまま残る。6DoF にロールが無いので加速度から起こす（[rollFromAccel]）
         */
        rollDeg: Double = 0.0,
        // 星座の線と名前を出す下限。その星座でいちばん明るい星がこれより暗ければ出さない
        constellationMagnitude: Double = 99.0,
        // 地平線・方位の文字・視野中心の印。**星図らしく読ませるための下敷き**
        drawGuides: Boolean = true,
        // 流星群の放射点。**その日に活動している群があるときだけ渡ってくる**
        radiants: List<MeteorRadiantMark> = emptyList(),
        // 案内中は周囲を薄くし、到着時は対象の線または点へリングも重ねる
        guidanceHighlight: GuidanceHighlight? = null,
    ): StarMap {
        val brightest = brightestPerConstellation()
        val d = daysFromJ2000(epochMillis)
        val precessed = precessed(d)
        val lst = localSiderealDeg(d, site.lonDeg)
        val basis = Basis(look.azDeg, look.altDeg, rollDeg)
        val k = projectionScale(width, fovDeg)
        val gray = ByteArray(width * height)

        // **天の川 → 星座絵 → 星座線 → 結び → 星の順に重ねる。**
        // 下に敷くものほど暗い段にする（dot は明るいほうを残すので、順番に関係なく星が勝つ）
        if (drawGuides) {
            drawHorizon(gray, width, height, look, basis, k)
            drawCardinals(gray, width, height, look, basis, k)
        }

        if (drawMilkyWay) {
            for (edge in catalog.milkyWay) {
                var previous: DoubleArray? = null
                for (point in edge) {
                    val aa = toApparentAltAz(point[0], point[1], lst, site.latDeg)
                    val q = project(enu(aa[0], aa[1]), basis, k, width, height)
                    if (q != null) previous?.let { line(gray, width, height, it, q, MILKY_WAY_VALUE, 0) }
                    previous = q
                }
            }
        }

        if (drawFigureArt) {
            for (i in catalog.constellations.indices) {
                if (brightest[i] > constellationMagnitude) continue
                val figure = catalog.figures[catalog.constellations[i].abbr] ?: continue
                val focused = guidanceHighlight?.kind == GuidanceTargetKind.CONSTELLATION &&
                    guidanceHighlight.nameJa == catalog.constellations[i].nameJa
                drawConstellationArt(
                    gray, width, height, figure, precessed.lines[i], lst, site, basis, k,
                    value = if (guidanceHighlight != null && !focused) GUIDANCE_DIM_ART_VALUE else ART_VALUE,
                )
            }
        }

        if (drawLines) {
            for ((index, lines) in precessed.lines.withIndex()) {
                // **見えない星をつないだ線は描かない。** 街では線だけが浮いて見える
                if (brightest[index] > constellationMagnitude) continue
                val highlighted = guidanceHighlight?.kind == GuidanceTargetKind.CONSTELLATION &&
                    guidanceHighlight.nameJa == catalog.constellations[index].nameJa
                val value = when {
                    highlighted && guidanceHighlight.arrived -> GUIDANCE_HIGHLIGHT_VALUE
                    highlighted -> GUIDANCE_FOCUS_VALUE
                    guidanceHighlight != null -> GUIDANCE_DIM_LINE_VALUE
                    else -> LINE_VALUE
                }
                for (seg in lines) {
                    for (i in 0 until seg.size - 1) {
                        drawGreatCircle(
                            gray, width, height, seg[i], seg[i + 1], lst, site, basis, k,
                            value = value,
                            radius = lineRadius(width) + if (highlighted) 1 else 0,
                        )
                    }
                }
            }
        }

        // **結びは星座線より明るく、破線で描く。** 3 点だけなので、実線だと星座線に紛れる
        val asterismLabels = ArrayList<Label>(catalog.asterisms.size)
        if (drawAsterisms) {
            for (asterism in catalog.asterisms) {
                val points = asterism.hips.mapNotNull { hip ->
                    val index = hipIndex()[hip] ?: return@mapNotNull null
                    val position = precessed.stars[index]
                    val aa = toApparentAltAz(position[0], position[1], lst, site.latDeg)
                    project(enu(aa[0], aa[1]), basis, k, width, height)
                }
                if (points.size < asterism.hips.size) continue
                val closed = if (asterism.closed) points + points.first() else points
                val highlighted = guidanceHighlight?.kind == GuidanceTargetKind.ASTERISM &&
                    guidanceHighlight.nameJa == asterism.nameJa
                val value = when {
                    highlighted -> GUIDANCE_HIGHLIGHT_VALUE
                    guidanceHighlight != null -> GUIDANCE_DIM_ASTERISM_VALUE
                    else -> ASTERISM_VALUE
                }
                var inside = false
                for (i in 0 until closed.size - 1) {
                    line(
                        gray, width, height, closed[i], closed[i + 1],
                        value,
                        if (highlighted) 1 else 0,
                        dash = if (highlighted) 0 else ASTERISM_DASH,
                    )
                    if (closed[i][0] in 0.0..width.toDouble() && closed[i][1] in 0.0..height.toDouble()) {
                        inside = true
                    }
                }
                // 名前は結びの重心へ。**全部の星が視野に入っていなくても、見えている側に出す**
                if (inside) {
                    val cx = points.sumOf { it[0] } / points.size
                    val cy = points.sumOf { it[1] } / points.size
                    if (cx in 0.0..width.toDouble() && cy in 0.0..height.toDouble()) {
                        asterismLabels += Label(
                            asterism.nameJa,
                            cx.roundToInt(),
                            cy.roundToInt(),
                            LabelKind.ASTERISM,
                        )
                    }
                }
            }
        }

        // 一等星の固有名。等級の順に並べたいので、いったん等級と一緒に持つ
        val namedStars = ArrayList<Pair<Double, Label>>(4)

        if (drawStars) {
            for (i in catalog.stars.indices) {
                val star = catalog.stars[i]
                if (star.magnitude > limitMagnitude) continue
                val p = precessed.stars[i]
                val aa = toApparentAltAz(p[0], p[1], lst, site.latDeg)
                val q = project(enu(aa[0], aa[1]), basis, k, width, height) ?: continue
                if (q[0] < -4 || q[1] < -4 || q[0] > width + 4 || q[1] > height + 4) continue
                if (star.magnitude <= STAR_NAME_MAGNITUDE &&
                    q[0] >= 0 && q[1] >= 0 && q[0] <= width && q[1] <= height
                ) {
                    catalog.brightNames[star.hip]?.let { name ->
                        namedStars += star.magnitude to Label(
                            name,
                            q[0].roundToInt(),
                            (q[1] - BODY_LABEL_OFFSET_PX).roundToInt(),
                            LabelKind.STAR_NAME,
                        )
                    }
                }
                // 明るいほど大きく、明るく。8 階調では明るさだけだと潰れる
                val t = ((limitMagnitude - star.magnitude) / (limitMagnitude + 1.5)).coerceIn(0.0, 1.0)
                val value = (255.0 * (0.45 + 0.55 * t)).roundToInt()
                // 点の大きさは画素数に比例させる。576px で 1px にすると 0.06° になって実機で見えない
                val base = if (t < 0.35) 1.0 else if (t < 0.7) 2.0 else 3.0
                dot(gray, width, height, q[0], q[1], value, (base * width / 196.0).roundToInt(), round = true)
            }

            // **月は満ち欠けを描く。** どの星見アプリも出している情報で、丸のままだと
            // 「明るい点」以上のことが伝わらない。欠け方は太陽のある側で決まる
            val hasMoon = bodies.any { it.moon }
            val moonLit = if (hasMoon) moonPhase(epochMillis).illuminated else 0.0
            val sunAltAz = if (hasMoon) {
                sunPosition(epochMillis).let { toAltAz(it.raDeg, it.decDeg, lst, site.latDeg) }
            } else {
                null
            }

            for (body in bodies) {
                val q = project(enu(body.azDeg, body.altDeg), basis, k, width, height) ?: continue
                if (q[0] < -4 || q[1] < -4 || q[0] > width + 4 || q[1] > height + 4) continue
                if (body.moon) {
                    // 実物（半径 0.26°）の 2.5 倍の輪で描く。**位置は中心なのでずれない**
                    val radius = maxOf(MOON_MIN_RADIUS_PX, 2.5 * MOON_RADIUS_DEG * k * RAD)
                    // 欠けている側も薄い輪で残す。**丸ごと消すと、月の大きさが分からなくなる**
                    ring(gray, width, height, q, radius, MOON_LIMB_VALUE)
                    val sunward = sunAltAz?.let {
                        sunwardOnScreen(body, it, basis, k, width, height, q)
                    }
                    if (sunward == null) {
                        dot(gray, width, height, q[0], q[1], 255, radius.roundToInt(), round = true)
                    } else {
                        moonDisc(gray, width, height, q, radius, moonLit, sunward)
                    }
                } else {
                    drawPlanet(gray, width, height, q, body.nameJa, width / 528.0)
                }
            }
        }

        // **放射点は星の上に重ねる。** 星より暗い段で、形（放射する線）だけで読ませる。
        // 点で描くと星と区別が付かず、輪で描くと衛星の印と紛れる
        for (radiant in radiants) {
            val q = project(enu(radiant.azDeg, radiant.altDeg), basis, k, width, height) ?: continue
            if (q[0] < 0 || q[1] < 0 || q[0] > width || q[1] > height) continue
            drawRadiant(gray, width, height, q, width / 528.0)
        }

        // **衛星は「大体どの辺にいるか」の点だけ。軌跡の線は描かない**（決定。09_satellite-drawing.md）。
        // 線を引くと画面が線で埋まるだけで、どれが衛星かが読めなかった。
        // ただし点を同じ大きさで並べると「点々」にしか見えないので、3 つ描き分ける。
        // **名前つき＝大きい点＋輪・スターリンク＝小さい点・動いているもの＝進行方向の矢印**
        // **星座に重ねるので、星より目立たせない**（#36）。輪で「星ではない」と分かる
        val namedRadius = (2.0 * width / 196.0).roundToInt()
        val crowdRadius = (1.5 * width / 196.0).roundToInt().coerceAtLeast(1)
        for (track in tracks) {
            val q = project(enu(track.nowAzDeg, track.nowAltDeg), basis, k, width, height) ?: continue
            if (q[0] < 0 || q[1] < 0 || q[0] > width || q[1] > height) continue
            if (!track.labelled) {
                // スターリンクは群れ。小さく暗くしておくと、名前つきが埋もれない
                dot(gray, width, height, q[0], q[1], CROWD_VALUE, crowdRadius, round = true)
                continue
            }
            dot(gray, width, height, q[0], q[1], 255, namedRadius, round = true)
            // 輪を回すと「主役」に見える。点だけだと星と同じ扱いに見えてしまう
            ring(gray, width, height, q, namedRadius + width * RING_GAP, RING_VALUE)
            // 30 秒後の位置へ向けた矢印。**静止軌道は動かないので矢印が出ない**（それも情報）
            val motion = track.motion
            val next = if (motion?.nextAzDeg != null && motion.nextAltDeg != null) {
                project(enu(motion.nextAzDeg, motion.nextAltDeg), basis, k, width, height)
            } else {
                null
            }
            if (next != null) arrow(gray, width, height, q, next, namedRadius + width * RING_GAP)
        }

        guidanceHighlight?.takeIf { it.arrived }?.let { highlight ->
            val q = project(enu(highlight.azDeg, highlight.altDeg), basis, k, width, height)
            if (q != null && q[0] in 0.0..width.toDouble() && q[1] in 0.0..height.toDouble()) {
                val radius = width * if (
                    highlight.kind == GuidanceTargetKind.CONSTELLATION ||
                    highlight.kind == GuidanceTargetKind.ASTERISM
                ) {
                    GUIDANCE_AREA_RADIUS
                } else {
                    GUIDANCE_POINT_RADIUS
                }
                ring(gray, width, height, q, radius, GUIDANCE_HIGHLIGHT_VALUE)
                ring(gray, width, height, q, radius + 3.0, GUIDANCE_HIGHLIGHT_VALUE)
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
            labels(precessed, lst, site, basis, k, width, height, maxLabels, brightest, constellationMagnitude)
        } else {
            emptyList()
        }
        // 月・惑星の名前は星座名より先に置く。**点だけでは恒星と区別が付かない**ので、
        // 名前が落ちると「明るい星がある」以上の情報が消える
        val bodyLabels = if (drawStars) {
            bodies.mapNotNull { body ->
                val q = project(enu(body.azDeg, body.altDeg), basis, k, width, height) ?: return@mapNotNull null
                if (q[0] < 0 || q[1] < 0 || q[0] > width || q[1] > height) return@mapNotNull null
                Label(body.nameJa, q[0].roundToInt(), (q[1] - BODY_LABEL_OFFSET_PX).roundToInt(), LabelKind.BODY)
            }
        } else {
            emptyList()
        }
        // **星座名が主役で、衛星は枠を 2 つまで借りるだけ**（#36）。
        // 月・惑星は数が少なく、点だけでは恒星と区別が付かないので先に置く
        val tracksShown = trackLabels.take(MAX_TRACK_LABELS)
        // 結びは 1 つだけ。**「夏の大三角」は星座名より先に知りたい名前**
        val asterismsShown = asterismLabels.take(MAX_ASTERISM_LABELS)
        // 一等星の名前は明るい順に 2 つまで。**視野に 1 つあるかないか**なので枠は食い合わない
        val starNames = namedStars.sortedBy { it.first }.take(MAX_STAR_NAME_LABELS).map { it.second }
        val room = (maxLabels - tracksShown.size - bodyLabels.size - asterismsShown.size - starNames.size)
            .coerceAtLeast(0)
        val merged = bodyLabels + asterismsShown + starNames + starLabels.take(room) + tracksShown
        return StarMap(width, height, gray, merged)
    }

    /**
     * 視線が属する IAU 星座を先頭にし、周辺の星座線に近いものを続ける。
     *
     * **AI 解説の主役には使わない**（グラスに出したラベルを使う。[labels]）。
     * 中心 1 点の境界判定は、残っている地磁気の誤差 ±5〜15° にいちばん弱い推定で、
     * 境界際では隣の星座に化ける。ここはラベルが 1 つも無い方向のための保険。
     */
    fun constellationsNear(site: Site, epochMillis: Long, look: Look, max: Int = 4): List<String> {
        if (max <= 0) return emptyList()
        val d = daysFromJ2000(epochMillis)
        val lst = localSiderealDeg(d, site.lonDeg)
        val precessed = precessed(d)
        val target = enu(look.azDeg, look.altDeg)
        val primary = constellationAt(site, epochMillis, look)

        fun sky(raDec: DoubleArray): Vec3 {
            val aa = toApparentAltAz(raDec[0], raDec[1], lst, site.latDeg)
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
        return buildList {
            if (primary != null) add(primary)
            scored.sortedByDescending { it.first }
                .asSequence()
                .map { it.second }
                .filterNot { it == primary }
                .take((max - size).coerceAtLeast(0))
                .forEach(::add)
        }
    }

    /** Roman (1987) の B1875.0 境界表で、視線中心が属する星座を判定する。 */
    fun constellationAt(site: Site, epochMillis: Long, look: Look): String? {
        val boundaries = catalog.boundaries ?: return null
        val d = daysFromJ2000(epochMillis)
        val date = toRaDec(
            look.azDeg,
            geometricAltitudeDeg(look.altDeg),
            localSiderealDeg(d, site.lonDeg),
            site.latDeg,
        )
        val b1875 = precessDateToB1875(date[0], date[1], d)
        return boundaries.nameAtB1875(b1875[0], b1875[1])
    }

    /** 視野に実際に入る固有名つきの明るい星を、中心に近い順で返す。 */
    fun visibleNamedStars(
        site: Site,
        epochMillis: Long,
        look: Look,
        fovDeg: Double,
        max: Int = 5,
    ): List<ObservedStarFact> {
        if (max <= 0) return emptyList()
        val d = daysFromJ2000(epochMillis)
        val lst = localSiderealDeg(d, site.lonDeg)
        val precessed = precessed(d)
        val target = enu(look.azDeg, look.altDeg)
        return catalog.stars.indices.asSequence().mapNotNull { index ->
            val name = catalog.brightNames[catalog.stars[index].hip] ?: return@mapNotNull null
            val position = precessed.stars[index]
            val aa = toApparentAltAz(position[0], position[1], lst, site.latDeg)
            val distance = acos((enu(aa[0], aa[1]) dot target).coerceIn(-1.0, 1.0)) * DEG
            if (distance > fovDeg / 2.0) return@mapNotNull null
            ObservedStarFact(name, catalog.stars[index].magnitude, aa[0], aa[1], distance)
        }.sortedBy { it.distanceFromCenterDeg }.take(max).toList()
    }

    fun knownBrightStarNames(): Set<String> = catalog.brightNames.values.toSet()

    /**
     * その星座でいちばん明るい星の等級。**ガイドの「明るくて探しやすい」で使う。**
     *
     * 星座を間引く判定（[SkyDensity]）と同じ値なので、**星図に線と名前が出るかどうかと
     * ガイドの選び方がずれない**。星が引けなければ null。
     */
    fun brightestMagnitude(nameJa: String): Double? {
        val index = catalog.constellations.indexOfFirst { it.nameJa == nameJa }
        if (index < 0) return null
        return brightestPerConstellation()[index].takeIf { it < UNKNOWN_MAGNITUDE }
    }

    /**
     * 「ふつう」の星図が描く対象から、声で名前を指定できるものだけを返す。
     *
     * 星座は幾何中心ではなく最輝星へ案内する。中心が暗い大きな星座でも、最初に探す点が
     * 肉眼で見えるため。北極星は1.97等で固有名表示の1.5等から外れるが、案内で最もよく
     * 呼ばれるため、同じ星表の点へ名前だけ補う。
     */
    fun guidanceTargets(
        site: Site,
        epochMillis: Long,
        density: SkyDensity = SkyDensity.STANDARD,
    ): List<GuidanceTarget> {
        val d = daysFromJ2000(epochMillis)
        val lst = localSiderealDeg(d, site.lonDeg)
        val precessed = precessed(d)
        val targets = ArrayList<GuidanceTarget>()
        val anchors = constellationAnchors()

        for (i in catalog.constellations.indices) {
            val anchor = anchors[i]
            if (anchor < 0 || catalog.stars[anchor].magnitude > density.constellationMagnitude) continue
            val aa = precessed.stars[anchor].toGuidanceAltAz(lst, site)
            val constellation = catalog.constellations[i]
            targets += GuidanceTarget(
                id = "constellation:${constellation.abbr}",
                nameJa = constellation.nameJa,
                kind = GuidanceTargetKind.CONSTELLATION,
                aim = Look(aa[0], aa[1]),
            )
        }

        val namedStars = catalog.brightNames + GUIDANCE_EXTRA_STAR_NAMES
        for ((hip, name) in namedStars) {
            val index = hipIndex()[hip] ?: continue
            if (catalog.stars[index].magnitude > density.limitMagnitude) continue
            val aa = precessed.stars[index].toGuidanceAltAz(lst, site)
            targets += GuidanceTarget(
                id = "star:$hip",
                nameJa = name,
                kind = GuidanceTargetKind.STAR,
                aim = Look(aa[0], aa[1]),
            )
        }

        for (asterism in catalog.asterisms) {
            val vectors = asterism.hips.mapNotNull { hip ->
                val index = hipIndex()[hip] ?: return@mapNotNull null
                val aa = precessed.stars[index].toGuidanceAltAz(lst, site)
                enu(aa[0], aa[1])
            }
            if (vectors.size != asterism.hips.size) continue
            val center = vectors.reduce { sum, vector -> sum + vector }.normalized()
            val aim = Look(
                azDeg = ((atan2(center.x, center.y) * DEG) % 360.0 + 360.0) % 360.0,
                altDeg = asin(center.z.coerceIn(-1.0, 1.0)) * DEG,
            )
            targets += GuidanceTarget(
                id = "asterism:${asterism.nameJa}",
                nameJa = asterism.nameJa,
                kind = GuidanceTargetKind.ASTERISM,
                aim = aim,
                aliases = setOf(asterism.nameJa + "形"),
            )
        }
        return targets
    }

    private fun DoubleArray.toGuidanceAltAz(lst: Double, site: Site): DoubleArray =
        toApparentAltAz(this[0], this[1], lst, site.latDeg)

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
            val aa = toApparentAltAz(center[0], center[1], lst, site.latDeg)
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
        brightest: DoubleArray,
        constellationMagnitude: Double,
    ): List<Label> {
        if (maxLabels <= 0) return emptyList()
        val cx = width / 2.0
        val cy = height / 2.0

        fun screen(raDec: DoubleArray): DoubleArray? {
            val aa = toApparentAltAz(raDec[0], raDec[1], lst, site.latDeg)
            val q = project(enu(aa[0], aa[1]), basis, k, width, height) ?: return null
            return if (q[0] < 0 || q[1] < 0 || q[0] > width || q[1] > height) null else q
        }

        val found = ArrayList<Pair<Double, Label>>()
        for (i in catalog.constellations.indices) {
            // **名前だけ出しても、その星座の星が見えていなければ意味が無い**
            if (brightest[i] > constellationMagnitude) continue
            val center = precessed.centers[i] ?: continue
            val visibleCenter = screen(center)
            // 見えている頂点のうち視野中心にいちばん近いもの。**順位はこれで決める。**
            // 星座の中心で測ると、視野を横切っている大きな星座が、中心がたまたま近い
            // 小さな星座に負ける。見ている人にとって近いのは「見えている部分」のほう
            val nearestVertex = precessed.lines[i]
                .flatten()
                .mapNotNull { screen(it) }
                .minByOrNull { hypot(it[0] - cx, it[1] - cy) }
            // 名前を置くのは中心。大きい星座で中心が視野の外なら、見えている近い頂点へ寄せる
            val at = visibleCenter ?: nearestVertex ?: continue
            val nearest = listOfNotNull(visibleCenter, nearestVertex)
                .minOf { hypot(it[0] - cx, it[1] - cy) }
            found += nearest to Label(
                catalog.constellations[i].nameJa,
                at[0].roundToInt(),
                at[1].roundToInt(),
            )
        }
        return found.sortedBy { it.first }.take(maxLabels).map { it.second }
    }

    /**
     * 地平線。**空と地面の境目が見えると、星図が「空の絵」になる。**
     *
     * 高度 0° の大円を方位 1° 刻みで投影して結ぶ。視野の外なら [line] の中で捨てられる。
     * 破線にしてあるのは、**星座線と見間違えないため**。
     */
    private fun drawHorizon(gray: ByteArray, w: Int, h: Int, look: Look, basis: Basis, k: Double) {
        var previous: DoubleArray? = null
        var az = look.azDeg - HORIZON_SPAN_DEG
        while (az <= look.azDeg + HORIZON_SPAN_DEG) {
            val q = project(enu(az, 0.0), basis, k, w, h)
            if (q != null) previous?.let { line(gray, w, h, it, q, HORIZON_VALUE, 1, dash = HORIZON_DASH) }
            previous = q
            az += 1.0
        }
    }

    /**
     * 方位の文字（北東南西）と、その間の目印を地平線の上に置く。
     *
     * **文字は線で描く。** キャンバスのテキスト枠は 8 つしかなく星座名で埋まるので、
     * 画像に焼くほうが安い（N・E・S・W なら数本の直線で読める）。
     */
    private fun drawCardinals(gray: ByteArray, w: Int, h: Int, look: Look, basis: Basis, k: Double) {
        val size = w * CARDINAL_SIZE
        for (index in 0 until 8) {
            val az = index * 45.0
            if (kotlin.math.abs(normalizeDeg(az - look.azDeg)) > HORIZON_SPAN_DEG) continue
            val at = project(enu(az, 0.0), basis, k, w, h) ?: continue
            // 目印の縦棒。地平線から上へ伸ばす
            val tick = if (index % 2 == 0) size * 1.2 else size * 0.6
            line(
                gray, w, h,
                doubleArrayOf(at[0], at[1]), doubleArrayOf(at[0], at[1] - tick),
                CARDINAL_VALUE, 1,
            )
            val glyph = CARDINAL_GLYPHS[index] ?: continue
            val left = at[0] - size / 2.0
            val top = at[1] - tick - size * 1.2
            for (stroke in glyph) {
                var previous: DoubleArray? = null
                for (point in stroke) {
                    val q = doubleArrayOf(left + point[0] * size, top + point[1] * size)
                    // 字は太らせる。1px では実機で読めない
                    previous?.let { line(gray, w, h, it, q, CARDINAL_VALUE, 1) }
                    previous = q
                }
            }
        }
    }

    /**
     * 星座絵を 1 つ敷く。
     *
     * **その星座の星座線を投影した外接矩形へ、正規化した絵を写すだけ。**
     * 星の位置へ厳密に貼るのではないので、絵は星より暗く細くして「下敷き」に見せる。
     *
     * 視野に入っていない星座は矩形が画面の外へ出るので、[line] の中で捨てられる。
     * **矩形が画面よりずっと大きいときは描かない**（星座の端がかすっただけで
     * 画面いっぱいに絵が広がると、見えている星と対応が取れなくなる）。
     */
    private fun drawConstellationArt(
        gray: ByteArray,
        width: Int,
        height: Int,
        figure: ConstellationFigure,
        lines: List<List<DoubleArray>>,
        lst: Double,
        site: Site,
        basis: Basis,
        k: Double,
        value: Int = ART_VALUE,
    ) {
        var minX = Double.MAX_VALUE
        var minY = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE
        var maxY = -Double.MAX_VALUE
        var count = 0
        for (seg in lines) {
            for (p in seg) {
                val aa = toApparentAltAz(p[0], p[1], lst, site.latDeg)
                val q = project(enu(aa[0], aa[1]), basis, k, width, height) ?: continue
                minX = min(minX, q[0])
                minY = min(minY, q[1])
                maxX = max(maxX, q[0])
                maxY = max(maxY, q[1])
                count++
            }
        }
        if (count < 2) return
        val boxWidth = maxX - minX
        val boxHeight = maxY - minY
        // 小さすぎる（遠い星座の端）・大きすぎる（真上に広がっている）ものは敷かない
        if (boxWidth < width * ART_MIN_SPAN || boxHeight < height * ART_MIN_SPAN) return
        if (boxWidth > width * ART_MAX_SPAN || boxHeight > height * ART_MAX_SPAN) return
        if (maxX < 0 || maxY < 0 || minX > width || minY > height) return

        // **1 画素の細線で描く。** 輪郭の点数を増やしたぶん、太いと絵が潰れるうえ
        // 圧縮後のバイト数も膨らむ（544×340 では 4,300 バイトしか余裕がない）
        val radius = 0
        for (stroke in figure) {
            var previous: DoubleArray? = null
            for (point in stroke) {
                val q = doubleArrayOf(minX + point[0] * boxWidth, minY + point[1] * boxHeight)
                previous?.let { line(gray, width, height, it, q, value, radius) }
                previous = q
            }
        }
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
        /** 焼いた絵と同じ傾きで置かないと、印だけが回って見える */
        rollDeg: Double = 0.0,
    ): List<Label> {
        val basis = Basis(look.azDeg, look.altDeg, rollDeg)
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
            labels += Label(mark + track.name + soon, at[0].roundToInt(), at[1].roundToInt(), LabelKind.SATELLITE)
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
                if (box[1] - CANVAS_LABEL_HEIGHT < 2.0 || box[1] + size > height - 2) continue
                if (out.any { overlaps(it.box, box, size) }) continue
                if (candidates.any { (_, other) -> other !== dot && covers(box, size, other) }) continue
                out += Callout(
                    track = track,
                    dot = dot,
                    box = box,
                    size = size,
                    label = doubleArrayOf(box[0] + size / 2.0, box[1] - CANVAS_LABEL_HEIGHT / 2.0 - 2.0),
                )
                break
            }
        }
        return out
    }

    /** 点を囲む輪。折れ線で十分（半径 10px の円に精度は要らない） */
    /**
     * 画面上で太陽がどちら側にあるか。単位ベクトルで返す。
     *
     * **太陽は視野の外にいるのが普通**（夜だから月が見えている）。そのまま投影すると
     * 視野の反対側で発散するので、**月から太陽の側へ 1° だけ寄った点**を投影して向きを取る。
     * 新月と満月ちょうどのように向きが決まらないときは null。
     */
    private fun sunwardOnScreen(
        moon: SkyBodyMark,
        sunAltAz: DoubleArray,
        basis: Basis,
        k: Double,
        width: Int,
        height: Int,
        at: DoubleArray,
    ): DoubleArray? {
        val m = enu(moon.azDeg, moon.altDeg)
        val sun = enu(sunAltAz[0], sunAltAz[1])
        val along = m dot sun
        // 月から見た太陽の向きのうち、視線に垂直な成分
        val perp = Vec3(sun.x - along * m.x, sun.y - along * m.y, sun.z - along * m.z)
        val length = hypot(hypot(perp.x, perp.y), perp.z)
        if (length < 1e-6) return null
        val step = 1.0 * RAD
        val nudged = Vec3(
            cos(step) * m.x + sin(step) * perp.x / length,
            cos(step) * m.y + sin(step) * perp.y / length,
            cos(step) * m.z + sin(step) * perp.z / length,
        )
        val projected = project(nudged, basis, k, width, height) ?: return null
        val dx = projected[0] - at[0]
        val dy = projected[1] - at[1]
        val screen = sqrt(dx * dx + dy * dy)
        if (screen < 1e-6) return null
        return doubleArrayOf(dx / screen, dy / screen)
    }

    /**
     * 月の満ち欠け。[illuminated] は 0 が新月で 1 が満月、[sunward] は画面上で太陽のある向き。
     *
     * 明暗の境目は、円を太陽の向きに `1 - 2 * illuminated` 倍だけ潰した楕円になる。
     * 満月なら左の縁、新月なら右の縁に重なるので、同じ式のまま端まで成り立つ。
     */
    private fun moonDisc(
        gray: ByteArray,
        width: Int,
        height: Int,
        at: DoubleArray,
        radius: Double,
        illuminated: Double,
        sunward: DoubleArray,
    ) {
        val squash = 1.0 - 2.0 * illuminated.coerceIn(0.0, 1.0)
        val left = max(0, floor(at[0] - radius).toInt())
        val right = min(width - 1, ceil(at[0] + radius).toInt())
        val top = max(0, floor(at[1] - radius).toInt())
        val bottom = min(height - 1, ceil(at[1] + radius).toInt())
        for (py in top..bottom) {
            for (px in left..right) {
                val dx = px - at[0]
                val dy = py - at[1]
                val distance = dx * dx + dy * dy
                if (distance > radius * radius) continue
                // 太陽の向きを x 軸に取り直す
                val x = dx * sunward[0] + dy * sunward[1]
                val y = -dx * sunward[1] + dy * sunward[0]
                if (x < squash * sqrt(max(0.0, radius * radius - y * y))) continue
                gray[py * width + px] = 255.toByte()
            }
        }
    }

    /**
     * 放射点の印。**中心を空けて、外向きの短い線を放射状に置く。**
     *
     * 中心を塗らないのは、そこに星があっても隠さないため。
     * 線の内側を空けてあるので、**「1 点から広がる」形がそのまま意味になる**。
     */
    private fun drawRadiant(gray: ByteArray, width: Int, height: Int, at: DoubleArray, scale: Double) {
        val inner = RADIANT_INNER_PX * scale
        val outer = RADIANT_OUTER_PX * scale
        for (i in 0 until RADIANT_RAYS) {
            val angle = i * 2.0 * PI / RADIANT_RAYS
            val dx = cos(angle)
            val dy = sin(angle)
            line(
                gray, width, height,
                doubleArrayOf(at[0] + dx * inner, at[1] + dy * inner),
                doubleArrayOf(at[0] + dx * outer, at[1] + dy * outer),
                RADIANT_VALUE, 0,
            )
        }
    }

    private fun ring(gray: ByteArray, width: Int, height: Int, at: DoubleArray, r: Double, value: Int) {
        var prev: DoubleArray? = null
        for (i in 0..RING_STEPS) {
            val a = i * (360.0 / RING_STEPS) * RAD
            val p = doubleArrayOf(at[0] + r * kotlin.math.cos(a), at[1] + r * sin(a))
            prev?.let { line(gray, width, height, it, p, value, 0) }
            prev = p
        }
    }

    /**
     * 進行方向の矢印。**輪の外から描き始める**（点に重ねると位置が読めない）。
     *
     * 長さは画面の中で一定にする。30 秒ぶんの実際の移動量をそのまま描くと、
     * 高いところを通る機体だけ極端に長くなって、向きが読み取りにくい。
     */
    private fun arrow(
        gray: ByteArray,
        width: Int,
        height: Int,
        at: DoubleArray,
        toward: DoubleArray,
        skip: Double,
    ) {
        val dx = toward[0] - at[0]
        val dy = toward[1] - at[1]
        val len = hypot(dx, dy)
        // 30 秒でほとんど動かないなら向きが定まらない。静止軌道はここで帰る
        if (len < ARROW_MIN_MOVE) return
        val ux = dx / len
        val uy = dy / len
        val from = doubleArrayOf(at[0] + ux * skip, at[1] + uy * skip)
        val tip = doubleArrayOf(at[0] + ux * (skip + width * ARROW_LENGTH), at[1] + uy * (skip + width * ARROW_LENGTH))
        line(gray, width, height, from, tip, ARROW_VALUE, 0)
        // かえし。左右に 30° 開く
        val head = width * ARROW_HEAD
        for (sign in intArrayOf(1, -1)) {
            val a = kotlin.math.atan2(uy, ux) + sign * 150.0 * RAD
            line(
                gray, width, height, tip,
                doubleArrayOf(tip[0] + head * kotlin.math.cos(a), tip[1] + head * sin(a)),
                ARROW_VALUE, 0,
            )
        }
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
        value: Int = LINE_VALUE,
        radius: Int = lineRadius(width),
    ) {
        val a = toApparentAltAz(from[0], from[1], lst, site.latDeg)
        val b = toApparentAltAz(to[0], to[1], lst, site.latDeg)
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
            prev?.let { line(gray, width, height, it, q, value, radius) }
            prev = q
        }
    }

    /**
     * 星座線の太さ。**点と同じく画素数に比例させる。**
     *
     * 528px で半径 1（3px・0.18°）だと、緑 8 階調の実機では**線が見えなかった**。
     * 星の半径は一番暗い星でも 3（7px）あるので、線だけが細すぎた。
     */
    private fun lineRadius(width: Int): Int = (width / 264.0).roundToInt().coerceAtLeast(1)

    /**
     * 線分を打つ。[dash] を渡すと破線になる（0 なら実線）。
     *
     * **結びは破線にする。** 実線だと星座線に紛れて、どれが「大三角」なのか読めない。
     */
    private fun line(
        gray: ByteArray,
        w: Int,
        h: Int,
        a: DoubleArray,
        b: DoubleArray,
        value: Int,
        radius: Int,
        dash: Int = 0,
    ) {
        val dx = b[0] - a[0]
        val dy = b[1] - a[1]
        val steps = max(1, ceil(max(kotlin.math.abs(dx), kotlin.math.abs(dy))).toInt())
        if (steps > 4 * (w + h)) return // 視野の裏側へ回り込んだ線分は捨てる
        for (i in 0..steps) {
            if (dash > 0 && (i / dash) % 2 == 1) continue
            val f = i.toDouble() / steps
            dot(gray, w, h, a[0] + dx * f, a[1] + dy * f, value, radius)
        }
    }

    /**
     * 惑星を**外形のイラスト**で描く。
     *
     * 点で描くと恒星と区別が付かず、名前のラベルが落ちた瞬間に「明るい星」に戻ってしまう。
     * **輪と数本の線だけで土星と木星は見分けが付く**（緑 8 階調でもリングは読める）。
     * 実物の見かけの大きさ（木星でも 0.01°）とは関係のない**アイコン**なので、
     * 星より大きく描いてよい。
     */
    private fun drawPlanet(gray: ByteArray, w: Int, h: Int, q: DoubleArray, nameJa: String, scale: Double) {
        val radius = (PLANET_RADIUS_PX[nameJa] ?: PLANET_DEFAULT_RADIUS_PX) * scale
        ring(gray, w, h, q, radius, 255)
        // 中心に小さな点を置くと、輪だけのときより「そこに何かある」と読める
        dot(gray, w, h, q[0], q[1], 150, 1, round = true)
        when (nameJa) {
            "土星" -> {
                // 環。**少し傾けた 1 本の線**で足りる（楕円を描くと 8 階調では潰れる）
                val arm = radius * 2.1
                val tilt = radius * 0.45
                line(
                    gray, w, h,
                    doubleArrayOf(q[0] - arm, q[1] + tilt),
                    doubleArrayOf(q[0] + arm, q[1] - tilt),
                    255, 0,
                )
            }
            "木星" -> {
                // 縞。輪の内側に 2 本
                for (offset in listOf(-0.35, 0.35)) {
                    val y = q[1] + radius * offset
                    val half = radius * 0.85
                    line(
                        gray, w, h,
                        doubleArrayOf(q[0] - half, y),
                        doubleArrayOf(q[0] + half, y),
                        200, 0,
                    )
                }
            }
            "火星" -> {
                // 極冠のつもりの短い線。**赤い星と言えないので形で示す**
                line(
                    gray, w, h,
                    doubleArrayOf(q[0] - radius * 0.5, q[1] - radius * 0.6),
                    doubleArrayOf(q[0] + radius * 0.5, q[1] - radius * 0.6),
                    200, 0,
                )
            }
            "金星" -> {
                // 満ち欠けする星なので、内側に弧を 1 本入れて三日月に見せる
                ring(gray, w, h, doubleArrayOf(q[0] + radius * 0.5, q[1]), radius * 0.85, 180)
            }
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
        /** 「ふつう」で点は出るが固有名ラベルの1.5等から外れる、案内に欠かせない星。 */
        val GUIDANCE_EXTRA_STAR_NAMES = mapOf(11767 to "北極星")

        const val GUIDANCE_HIGHLIGHT_VALUE = 255
        const val GUIDANCE_FOCUS_VALUE = 210
        const val GUIDANCE_DIM_LINE_VALUE = 72
        const val GUIDANCE_DIM_ASTERISM_VALUE = 72
        const val GUIDANCE_DIM_ART_VALUE = 36
        const val GUIDANCE_AREA_RADIUS = 0.10
        const val GUIDANCE_POINT_RADIUS = 0.045

        /**
         * 星座線の明るさ。**3bit に落ちるので「少し暗く」は効かない。**
         *
         * 110 は量子化すると 8 階調の 3 で、**一番暗い星と同じ段**だった。
         * 実機で線が見えなかったのはこれと細さの合わせ技。144 なら段が 4 に上がり、
         * 中くらい以上の星（5〜7）より暗いまま、線として読める。
         * **明るさは転送量に効かない**（同じ値が続くので RLE の走長は変わらない）。
         */
        const val LINE_VALUE = 144

        /** スターリンクの点。名前つきより暗くして、群れとして見せる */
        const val CROWD_VALUE = 170

        /**
         * 星座絵の明るさ。**3bit の 2 段目**（線が 4・暗い星が 3）。
         * 星より暗くないと、絵が主役になって星の位置が読めない。
         */
        /**
         * 星座絵の明るさ。**3bit でいちばん暗い段**（1/7）。
         *
         * 骨組みの線だった頃は段 2 でも「線が増えた」としか見えなかったが、
         * **輪郭にして 1 画素まで細くしたので、段 2 でも絵として読める**。
         * 段 1 まで落とすと実機で薄すぎた。**細さで主張を抑え、明るさは残す。**
         */
        const val ART_VALUE = 72

        /** 星座絵を敷く矩形の下限・上限（画面に対する比） */
        const val ART_MIN_SPAN = 0.15
        const val ART_MAX_SPAN = 1.8

        /** 地平線を引く方位の幅（視野の片側ぶん＋余裕） */
        const val HORIZON_SPAN_DEG = 30.0

        /** 地平線は破線。星座線と見間違えないため */
        const val HORIZON_VALUE = 110
        const val HORIZON_DASH = 5

        /** 方位の文字と目印。**読ませたいので星と同じくらい明るく** */
        const val CARDINAL_VALUE = 210
        // 528px で 26px。**これより小さいと緑 8 階調では字に見えない**
        const val CARDINAL_SIZE = 0.05

        /** 視野中心の印。星より暗く、中心は空けておく */

        /**
         * 方位の文字。**線で描く**（テキスト枠を使わない）。
         * 正規化 [0,1] の枠に入れた折れ線で、45° 刻みの 8 方位のうち 4 つに文字を置く。
         */
        val CARDINAL_GLYPHS: Map<Int, List<List<DoubleArray>>> = mapOf(
            // N
            0 to listOf(
                listOf(doubleArrayOf(0.0, 1.0), doubleArrayOf(0.0, 0.0), doubleArrayOf(1.0, 1.0), doubleArrayOf(1.0, 0.0)),
            ),
            // E
            2 to listOf(
                listOf(doubleArrayOf(1.0, 0.0), doubleArrayOf(0.0, 0.0), doubleArrayOf(0.0, 1.0), doubleArrayOf(1.0, 1.0)),
                listOf(doubleArrayOf(0.0, 0.5), doubleArrayOf(0.8, 0.5)),
            ),
            // S
            4 to listOf(
                listOf(
                    doubleArrayOf(1.0, 0.0), doubleArrayOf(0.0, 0.0), doubleArrayOf(0.0, 0.5),
                    doubleArrayOf(1.0, 0.5), doubleArrayOf(1.0, 1.0), doubleArrayOf(0.0, 1.0),
                ),
            ),
            // W
            6 to listOf(
                listOf(
                    doubleArrayOf(0.0, 0.0), doubleArrayOf(0.25, 1.0), doubleArrayOf(0.5, 0.35),
                    doubleArrayOf(0.75, 1.0), doubleArrayOf(1.0, 0.0),
                ),
            ),
        )

        /** 星座線の頂点と星を同じものと見なす角距離。星座線は星の位置に引かれている */
        const val VERTEX_MATCH_DEG = 1.0

        /**
         * 天の川。**星座絵と同じ段**（3bit で 2）。
         *
         * 50 だと段が 1 で、実機の緑 8 階調では帯として読めない見込み。
         * 星（3 以上）と星座線（4）より下なので、下敷きの位置は変わらない。
         */
        const val MILKY_WAY_VALUE = 70

        /** 結びは星座線より明るく（3bit で 5）、破線の刻みは 6px */
        const val ASTERISM_VALUE = 180
        const val ASTERISM_DASH = 6

        /** 結びの名前に貸すテキスト枠。1 つで足りる */
        const val MAX_ASTERISM_LABELS = 1

        /** 惑星のアイコンの半径[px]（528px のとき）。実物の見かけとは無関係のアイコン */
        val PLANET_RADIUS_PX = mapOf(
            "水星" to 7.0,
            "金星" to 10.0,
            "火星" to 8.0,
            "木星" to 12.0,
            "土星" to 10.0,
            "天王星" to 7.0,
            "海王星" to 7.0,
            "冥王星" to 5.0,
        )
        const val PLANET_DEFAULT_RADIUS_PX = 7.0

        /** 衛星の名前に貸すテキスト枠の数。星座名を押し出さないための上限 */
        const val MAX_TRACK_LABELS = 2

        /** 名前つきの点を囲む輪 */
        const val RING_VALUE = 200
        const val RING_STEPS = 16

        /** 点の縁から輪までの距離（画像の幅に対する比） */
        const val RING_GAP = 0.012

        /** 進行方向の矢印 */
        const val ARROW_VALUE = 210
        const val ARROW_LENGTH = 0.055
        const val ARROW_HEAD = 0.016

        /** 30 秒ぶんの移動がこれ未満なら矢印を出さない[画素]。静止軌道はここで落ちる */
        const val ARROW_MIN_MOVE = 3.0

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

        /** 月の見かけの半径。実物は 0.26° で、35° の視野では 4px しかない */
        const val MOON_RADIUS_DEG = 0.26

        /** 実物どおりだと星と見分けが付かないので、輪はこの太さを下限にする */
        const val MOON_MIN_RADIUS_PX = 8.0

        /** 名前を点の上へずらす量。点の上に重ねると点が読めない */
        const val BODY_LABEL_OFFSET_PX = 26.0

        /**
         * 固有名を出す等級。
         *
         * **一等星まで。** 全天で 21 個しかないので、画角 35° の視野には 1 つあるかないかで、
         * 星座名の枠をほとんど食わない。2 等まで広げると視野に 5 個入ることがあり、
         * 星座名が押し出される。
         */
        const val STAR_NAME_MAGNITUDE = 1.5

        /** 固有名に貸す枠。**主役は星座名**なので 2 つまで */
        const val MAX_STAR_NAME_LABELS = 2

        /** 欠けている側の輪。消すと月の大きさが分からなくなるので、いちばん暗い段で残す */
        const val MOON_LIMB_VALUE = 60

        /**
         * 放射点の印。**星（最大 255）より暗く、星座線（144）と同じくらいの段**にする。
         * 流星群は「そこに何かある」と分かればよく、星を押しのける情報ではない。
         */
        const val RADIANT_VALUE = 150

        /** 中心を空ける半径[px]。528 幅のときの値で、他の幅では比例させる */
        const val RADIANT_INNER_PX = 5.0
        const val RADIANT_OUTER_PX = 13.0

        /** 放射する線の本数。少ないと十字に、多いと円に見える */
        const val RADIANT_RAYS = 8

        /** 星が 1 つも引けなかった星座の等級。どんな下限より暗いので必ず落ちる */
        private const val UNKNOWN_MAGNITUDE = 99.0
    }
}
