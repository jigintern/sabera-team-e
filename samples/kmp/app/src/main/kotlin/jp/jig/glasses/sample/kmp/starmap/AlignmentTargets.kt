package jp.jig.glasses.sample.kmp.starmap

import kotlin.math.abs

/**
 * 天体アライメントで「どれに合わせてもらうか」を決める。仕様は docs/team-e/alignment-accuracy.md。
 *
 * **初心者に星を探させない。** 段階 1 のあとでも方位は ±15° に入っていて、仰角は加速度計から
 * ほぼ絶対値で取れている。つまり**アプリの側から「右へ」「左へ」と言える**ので、
 * ユーザーがやるのは「見えている明るい点に十字を重ねる」だけになる。名前も方位も要らない。
 *
 * 候補を明るい星だけに絞れば視野 35° の中に 0〜2 個しか入らないので、
 * どれに合わせたかはアプリの側で決められる（Celestron SkyAlign と同じ理屈）。
 */

/** 合わせ先。名前は合わせ終わってから見せるためのもので、手順の前提にはしない */
data class AlignmentTarget(
    val hip: Int,
    val nameJa: String,
    val magnitude: Double,
    val azDeg: Double,
    val altDeg: Double,
)

/** いまの視線から見た合わせ先。UI とグラスの文字はここから作る */
data class AlignmentGuidance(
    val target: AlignmentTarget,
    val turnDeg: Double,
    val riseDeg: Double,
    val distanceDeg: Double,
    val inView: Boolean,
    val ambiguous: Boolean,
)

class AlignmentTargets(
    private val catalog: StarCatalog,
    private val limitMagnitude: Double = LIMIT_MAGNITUDE,
    private val width: Int = STAR_MAP_WIDTH,
    private val height: Int = STAR_MAP_HEIGHT,
    private val fovDeg: Double = ObservationDefaults.STAR_MAP_FOV_DEG,
) {
    private val scale = projectionScale(width, fovDeg)

    /**
     * いま空にある合わせ先を、選びやすい順に返す。
     *
     * 高度を 20〜40° に寄せるのは、方位の誤差の伝播が `1 / cos h` で、
     * 高い天体で合わせると誤差が拡大するため（高度 80° で 5.8 倍）。
     */
    fun candidates(site: Site, epochMillis: Long, lookAzDeg: Double? = null): List<AlignmentTarget> {
        val d = daysFromJ2000(epochMillis)
        val lst = localSiderealDeg(d, site.lonDeg)
        return catalog.stars.mapNotNull { star ->
            if (star.magnitude > limitMagnitude) return@mapNotNull null
            val name = catalog.brightNames[star.hip] ?: return@mapNotNull null
            val precessed = precess(star.raDeg, star.decDeg, d)
            val aa = toApparentAltAz(precessed[0], precessed[1], lst, site.latDeg)
            if (aa[1] < MIN_ALTITUDE_DEG || aa[1] > MAX_ALTITUDE_DEG) return@mapNotNull null
            AlignmentTarget(star.hip, name, star.magnitude, aa[0], aa[1])
        }.sortedBy { score(it, lookAzDeg) }
    }

    /** 視野に入っている候補を、中心に近い順に返す。合わせた天体を同定するのに使う */
    fun inView(candidates: List<AlignmentTarget>, look: Look): List<AlignmentTarget> {
        val basis = Basis(look.azDeg, look.altDeg)
        return candidates.filter { onPanel(it, basis) }
            .sortedBy { angleBetweenDeg(enu(it.azDeg, it.altDeg), basis.forward) }
    }

    /**
     * 次に合わせてもらう天体を 1 つ決める。
     *
     * [exclude] は既に使った天体、[separateFrom] は 1 点目。**2 点目は 1 点目から離す**
     * （SkyAlign の「離れた 2 天体」、SPAAM の「合わせ点を広く散らせ」と同じ）。
     */
    fun guide(
        candidates: List<AlignmentTarget>,
        look: Look,
        exclude: Set<Int> = emptySet(),
        separateFrom: AlignmentTarget? = null,
        minSeparationDeg: Double = MIN_SEPARATION_DEG,
    ): AlignmentGuidance? {
        val usable = candidates.filter { candidate ->
            if (candidate.hip in exclude) return@filter false
            if (separateFrom == null) return@filter true
            angleBetweenDeg(
                enu(candidate.azDeg, candidate.altDeg),
                enu(separateFrom.azDeg, separateFrom.altDeg),
            ) >= minSeparationDeg
        }
        val target = usable.minByOrNull { score(it, look.azDeg) } ?: return null
        return guidanceFor(target, usable, look)
    }

    /** 指定した天体を合わせ先にしたときの案内。別の星に振り替えたときに使う */
    fun guidanceFor(
        target: AlignmentTarget,
        candidates: List<AlignmentTarget>,
        look: Look,
    ): AlignmentGuidance {
        val basis = Basis(look.azDeg, look.altDeg)
        val direction = enu(target.azDeg, target.altDeg)
        // 「間違えようがないか」は、合わせ先を視野の中心に置いたときに
        // 同じくらい明るい点が一緒に入るかで決める。
        // ただし**月と惑星は星表に無い**ので、それが紛れ込む余地は残る。
        // 取り違えは 2 点目の残差に出るので、そこで気づけるようにしてある
        val neighbours = candidates.filter {
            it.hip != target.hip && onPanel(it, Basis(target.azDeg, target.altDeg))
        }
        return AlignmentGuidance(
            target = target,
            turnDeg = normalizeDeg(target.azDeg - look.azDeg),
            riseDeg = target.altDeg - look.altDeg,
            distanceDeg = angleBetweenDeg(direction, basis.forward),
            inView = onPanel(target, basis),
            ambiguous = neighbours.any { it.magnitude < target.magnitude + MAGNITUDE_GAP },
        )
    }

    private fun onPanel(target: AlignmentTarget, basis: Basis): Boolean {
        val at = project(enu(target.azDeg, target.altDeg), basis, scale, width, height) ?: return false
        val margin = width * EDGE_MARGIN
        return at[0] > margin && at[0] < width - margin && at[1] > margin && at[1] < height - margin
    }

    /** 小さいほど選ばれる。明るさ・高度・首を振る量の 3 つで決める */
    private fun score(target: AlignmentTarget, lookAzDeg: Double?): Double {
        val turn = lookAzDeg?.let { abs(normalizeDeg(target.azDeg - it)) } ?: 0.0
        return target.magnitude * MAGNITUDE_WEIGHT +
            abs(target.altDeg - PREFERRED_ALTITUDE_DEG) * ALTITUDE_WEIGHT +
            turn * TURN_WEIGHT
    }

    companion object {
        /** 同梱の `bright-stars.json` は 1.6 等まで。街明かりでも見える明るさに限る */
        const val LIMIT_MAGNITUDE = 1.6
        const val MIN_ALTITUDE_DEG = 15.0
        const val MAX_ALTITUDE_DEG = 60.0
        const val PREFERRED_ALTITUDE_DEG = 30.0
        const val MIN_SEPARATION_DEG = 40.0
        const val MAGNITUDE_GAP = 1.0
        private const val MAGNITUDE_WEIGHT = 0.5
        private const val ALTITUDE_WEIGHT = 0.05
        private const val TURN_WEIGHT = 0.02
        private const val EDGE_MARGIN = 0.08
    }
}

/** グラスに出す 1 行。矢印だけで意味が通るようにする（見上げている人は文字を読み込まない） */
fun glassGuidanceText(guidance: AlignmentGuidance?, holdState: AlignmentHoldState?): String {
    if (guidance == null) return "合わせられる星が見つかりません"
    if (!guidance.inView) {
        val turn = guidance.turnDeg
        return if (turn >= 0) "▶▶ 右へ ${turn.toInt()}°" else "◀◀ 左へ ${(-turn).toInt()}°"
    }
    if (holdState == null || !holdState.steady) return "＋ に重ねて止める"
    val remaining = ((AlignmentHold.REQUIRED_HOLD_MS - holdState.heldMs) / 1000.0).coerceAtLeast(0.0)
    return if (remaining <= 0.0) "取得しました" else "そのまま ${remaining.toInt() + 1}"
}

/** スマホ側の案内。こちらは同伴者も読むので言葉で書く */
fun phoneGuidanceText(guidance: AlignmentGuidance?, holdState: AlignmentHoldState?): String {
    if (guidance == null) return "いまの空に合わせられる明るい星がありません"
    if (guidance.ambiguous) return "近くに同じくらい明るい星があります。別の星にしてください"
    if (!guidance.inView) {
        val turn = guidance.turnDeg
        val side = if (turn >= 0) "右" else "左"
        val vertical = when {
            guidance.riseDeg > 8.0 -> "、少し上へ"
            guidance.riseDeg < -8.0 -> "、少し下へ"
            else -> ""
        }
        return "ゆっくり$side へ ${abs(turn).toInt()}° 首を向けてください$vertical"
    }
    if (holdState == null || !holdState.steady) {
        return "いま見えている一番明るい点に十字を重ねて、動かさないでください"
    }
    val remaining = ((AlignmentHold.REQUIRED_HOLD_MS - holdState.heldMs) / 1000.0).coerceAtLeast(0.0)
    return if (remaining <= 0.0) "取得しました" else "そのまま ${remaining.toInt() + 1} 秒"
}
