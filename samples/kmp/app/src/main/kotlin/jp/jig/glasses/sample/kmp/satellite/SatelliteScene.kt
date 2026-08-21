package jp.jig.glasses.sample.kmp.satellite

import android.content.Context
import jp.jig.glasses.sample.kmp.starmap.Look
import jp.jig.glasses.sample.kmp.starmap.PANEL_HEIGHT
import jp.jig.glasses.sample.kmp.starmap.PANEL_WIDTH
import jp.jig.glasses.sample.kmp.starmap.SkyMotion
import jp.jig.glasses.sample.kmp.starmap.SkyTrack
import jp.jig.glasses.sample.kmp.starmap.cardinalDirection16
import jp.jig.glasses.sample.kmp.starmap.enu
import kotlin.math.roundToInt
import kotlin.math.hypot

/**
 * いま空にいる人工衛星を集めて、星図に重ねられる形にする。
 *
 * **名前つきの 24 機とスターリンクの群れでは扱いが違う。**
 * 名前つきは 1 機ずつ名前と輪郭を出すが、スターリンクは数が多いので点だけ打つ
 * （点の数そのものが「こんなに飛んでいるのか」になる）。
 */
class SatelliteScene(
    private val named: List<Sgp4>,
    private val starlink: List<Sgp4>,
    /** 同梱した TLE をいつ取ったか。**古いと位置がずれる**ので画面に出す */
    val fetchedAt: String? = null,
) {

    /** TLE の古さ[日]。取得日が分からなければ null */
    fun ageDays(nowMillis: Long): Double? {
        val text = fetchedAt?.trim() ?: return null
        val parsed = runCatching {
            java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }.parse(text)
        }.getOrNull() ?: return null
        return (nowMillis - parsed.time) / 86_400_000.0
    }

    /**
     * 軌道要素そのものの古さ[日]。名前つき衛星の中央値を取る。
     *
     * **位置のずれを決めるのは取得日ではなく元期。** 取得日は「いつ落としたか」でしかなく、
     * キャッシュを使い回すと実際より新しく見える（元期は落とした時点で数時間〜数日前）。
     */
    fun elementAgeDays(nowMillis: Long): Double? {
        if (named.isEmpty()) return null
        val ages = named.map { (nowMillis - it.tle.epochUnixMillis) / 86_400_000.0 }.sorted()
        return ages[ages.size / 2]
    }

    /** 空にいる衛星 1 機ぶんの情報。スマホ側の一覧にも使う */
    class Sighting(
        val name: String,
        val azDeg: Double,
        val altDeg: Double,
        val rangeKm: Double,
        val sunlit: Boolean,
        val named: Boolean,
        val motion: SkyMotion? = null,
    ) {

        /**
         * 「上昇中・最接近まで 3 分」のような一言。
         *
         * **点だけ見せても「待てばいいのか」が分からない**ので、時間を出す。
         */
        val timing: String
            get() {
                val m = motion ?: return ""
                if (m.stationary) return "ほぼ静止（同じ場所に見え続ける）"
                val minutes = m.closestInMinutes
                val direction = if (m.rising) "上昇中" else "下降中"
                return when {
                    minutes == null -> direction
                    minutes > 0.5 -> "$direction・最接近まで ${kotlin.math.ceil(minutes).toInt()} 分"
                    minutes > -0.5 -> "$direction・いま最接近"
                    else -> "$direction・最接近は ${kotlin.math.ceil(-minutes).toInt()} 分前"
                }
            }

        /** 「南南西 高度 45° 720km」のような表示 */
        val where: String
            get() {
                return "${cardinalDirection16(azDeg)} 高度 ${altDeg.roundToInt()}° ${rangeKm.roundToInt()}km"
            }
    }

    /** 視野に入っている衛星を返す */
    fun tracksInView(
        observer: Observer,
        epochMillis: Long,
        look: Look,
        fovDeg: Double,
        maxNamed: Int = MAX_NAMED,
        maxStarlink: Int = MAX_STARLINK,
    ): List<SkyTrack> {
        val forward = enu(look.azDeg, look.altDeg)
        // **fovDeg は視野の「横幅」なので、視線からの角度は半分で見る。**
        // パネルの対角まで含め、端へ入ってくる機体を少し早めに拾う。
        val diagonalScale = hypot(1.0, PANEL_HEIGHT.toDouble() / PANEL_WIDTH)
        val radiusDeg = fovDeg * 0.5 * diagonalScale * VIEW_MARGIN_SCALE
        val cosLimit = kotlin.math.cos(radiusDeg * (Math.PI / 180.0))

        fun inView(azDeg: Double, altDeg: Double): Boolean {
            val v = enu(azDeg, altDeg)
            return (v dot forward) > cosLimit
        }

        val namedTracks = named.mapNotNull { track(it, observer, epochMillis, labelled = true) }
            .filter { inView(it.nowAzDeg, it.nowAltDeg) }
            .sortedByDescending { it.nowAltDeg }
            .take(maxNamed)

        // **近い順に選ぶ。** カタログの並び順で先着 8 機にすると、
        // 視野の隅にいる遠い機体が、真ん中を通る近い機体を押しのける。
        // maxStarlink が 0 なら 10,748 機を回さずに帰る（印だけ動かすときはこの道）
        if (maxStarlink <= 0) return namedTracks
        val starlinkCandidates = ArrayList<Pair<Double, Sgp4>>()
        for (sgp4 in starlink) {
            val state = sgp4.at(epochMillis) ?: continue
            val now = observer.look(state, epochMillis)
            if (now.altDeg <= 0.0 || !inView(now.azDeg, now.altDeg)) continue
            starlinkCandidates += now.rangeKm to sgp4
        }
        val starlinkTracks = starlinkCandidates
            .sortedBy { it.first }
            .take(maxStarlink)
            .mapNotNull { (_, sgp4) -> track(sgp4, observer, epochMillis, labelled = false) }

        return namedTracks + starlinkTracks
    }

    /** スマホ側の一覧に出すぶん。視野に関係なく、空に出ているものを高い順に */
    fun aboveHorizon(observer: Observer, epochMillis: Long, limit: Int = 12): List<Sighting> {
        val sightings = ArrayList<Sighting>()
        for (sgp4 in named) {
            val state = sgp4.at(epochMillis) ?: continue
            val look = observer.look(state, epochMillis)
            if (look.altDeg <= 0.0) continue
            sightings += Sighting(
                name = sgp4.tle.name,
                azDeg = look.azDeg,
                altDeg = look.altDeg,
                rangeKm = look.rangeKm,
                sunlit = isSunlit(state, epochMillis),
                named = true,
                motion = motion(sgp4, observer, epochMillis),
            )
        }
        return sightings.sortedByDescending { it.altDeg }.take(limit)
    }

    /** 軌道要素を読めたか。assets が入っていないと空になる */
    val loaded: Boolean get() = named.isNotEmpty()

    /** 頭上にいるスターリンクの数。「いま何機飛んでいるか」を出すために数えるだけ */
    fun starlinkAboveHorizon(observer: Observer, epochMillis: Long): Int =
        starlink.count { sgp4 ->
            val state = sgp4.at(epochMillis) ?: return@count false
            observer.look(state, epochMillis).altDeg > 0.0
        }

    /**
     * 1 機ぶんの見え方。
     *
     * **軌跡の線をやめたので、伝播するのは「いま」の 1 点だけ。**
     * 以前は前後 3 分ぶんを 10 秒刻みで 19 回伝播していた（線を引くため）。
     */
    private fun track(sgp4: Sgp4, observer: Observer, epochMillis: Long, labelled: Boolean): SkyTrack? {
        val state = sgp4.at(epochMillis) ?: return null
        val now = observer.look(state, epochMillis)
        if (now.altDeg <= 0.0) return null
        return SkyTrack(
            name = sgp4.tle.name,
            nowAzDeg = now.azDeg,
            nowAltDeg = now.altDeg,
            sunlit = isSunlit(state, epochMillis),
            labelled = labelled,
            // 名前を出さない機体では時間も出さないので、そのぶんの伝播を省く
            motion = if (labelled) motion(sgp4, observer, epochMillis) else null,
        )
    }

    /**
     * 最接近までの時間と、上がっているか下がっているか。
     *
     * **前後の時刻を当たって、観測地からの距離がいちばん小さい点を探す**だけ。
     * 低軌道の機体はそこがいちばん高く・いちばん明るい。
     *
     * 出せない場合を 2 つに分けている。
     * - **静止軌道** — 距離がほとんど変わらない。数値のゆらぎで「あと 7 分」と出すと嘘になる
     * - **窓の外** — 端が最小だった。本当の最小はもっと先（または前）にある
     */
    private fun motion(sgp4: Sgp4, observer: Observer, epochMillis: Long): SkyMotion? {
        val steps = ((APPROACH_AHEAD_MIN - APPROACH_BACK_MIN) / APPROACH_STEP_MIN).toInt()
        var bestStep = -1
        var bestRange = Double.MAX_VALUE
        var minRange = Double.MAX_VALUE
        var maxRange = 0.0
        var altNow = Double.NaN
        var altNext = Double.NaN
        var azNext = Double.NaN
        for (i in 0..steps) {
            val minutes = APPROACH_BACK_MIN + i * APPROACH_STEP_MIN
            val at = epochMillis + (minutes * 60_000.0).toLong()
            val state = sgp4.at(at) ?: return null
            val look = observer.look(state, at)
            if (look.rangeKm < bestRange) {
                bestRange = look.rangeKm
                bestStep = i
            }
            minRange = minOf(minRange, look.rangeKm)
            maxRange = maxOf(maxRange, look.rangeKm)
            // 上昇か下降かは「いま」と「その次」で見る。次の位置は矢印にも使う
            if (minutes == 0.0) altNow = look.altDeg
            if (minutes == APPROACH_STEP_MIN) {
                altNext = look.altDeg
                azNext = look.azDeg
            }
        }
        if (bestStep < 0) return null
        val rising = altNext > altNow
        val stationary = (maxRange - minRange) / minRange < APPROACH_MIN_SPREAD
        val outsideWindow = bestStep == 0 || bestStep == steps
        return SkyMotion(
            closestInMinutes = if (stationary || outsideWindow) {
                null
            } else {
                APPROACH_BACK_MIN + bestStep * APPROACH_STEP_MIN
            },
            rising = rising,
            stationary = stationary,
            nextAzDeg = azNext.takeIf { !it.isNaN() },
            nextAltDeg = altNext.takeIf { !it.isNaN() },
        )
    }

    companion object {
        /** 最接近を探す窓。低軌道のパスは 10 分ほどで終わるので、これで足りる */
        private const val APPROACH_BACK_MIN = -5.0
        private const val APPROACH_AHEAD_MIN = 20.0
        private const val APPROACH_STEP_MIN = 0.5

        /** 距離の振れ幅がこれ未満なら「動いていない」とみなす */
        private const val APPROACH_MIN_SPREAD = 0.005

        /** 名前を出す数。衛星モードでは星座名を出さないので、テキスト枠 8 個を丸ごと使える */
        const val MAX_NAMED = 8

        /** 点だけ打つスターリンクの数。多すぎると星図が点で埋まる */
        const val MAX_STARLINK = 8

        /** 画面端へ入る直前から候補へ含める余裕。 */
        internal const val VIEW_MARGIN_SCALE = 1.3

        /** 同梱した TLE を読む。**10,748 機ぶんあるので IO スレッドで呼ぶこと** */
        fun load(context: Context): SatelliteScene {
            fun read(name: String): List<Sgp4> = runCatching {
                context.assets.open(name).use { it.readBytes().toString(Charsets.UTF_8) }
            }.map { text -> Tle.parseAll(text).map { Sgp4(it) } }.getOrElse { emptyList() }

            val fetchedAt = runCatching {
                context.assets.open("satellites-fetched.txt").use { it.readBytes().toString(Charsets.UTF_8) }
            }.getOrNull()
            return SatelliteScene(
                named = read("satellites.tle"),
                starlink = read("starlink.tle"),
                fetchedAt = fetchedAt,
            )
        }
    }
}
