package jp.jig.glasses.sample.kmp.satellite

import android.content.Context
import jp.jig.glasses.sample.kmp.glass.PANEL_HEIGHT
import jp.jig.glasses.sample.kmp.glass.PANEL_WIDTH
import jp.jig.glasses.sample.kmp.sky.Look
import jp.jig.glasses.sample.kmp.sky.cardinalDirection16
import jp.jig.glasses.sample.kmp.sky.enu
import kotlin.math.hypot
import kotlin.math.roundToInt

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

    /**
     * これから空へ上がってくる 1 機ぶん。
     *
     * **「いま空に出ている」だけでは、待つという選択ができない。** 空が真っ暗な方角を
     * 見上げていても、10 分後にそこを ISS が通るなら待つ価値がある。
     */
    class Pass(
        val name: String,
        /** 地平線から出る時刻と、いちばん高くなる時刻 */
        val risesAtMillis: Long,
        val peakAtMillis: Long,
        /** 出る方角・いちばん高いところの方角・沈む方角 */
        val riseAzDeg: Double,
        val peakAzDeg: Double,
        val setAzDeg: Double,
        /** いちばん高いところの高度[度] */
        val peakAltDeg: Double,
        /** いちばん高いところで日が当たっているか。当たっていなければ肉眼では見えない */
        val sunlitAtPeak: Boolean,
    ) {

        /** 「西 → 南東」。**どちらから来てどちらへ抜けるか**が分かれば待つ向きが決まる */
        val path: String
            get() = "${cardinalDirection16(riseAzDeg)} → ${cardinalDirection16(setAzDeg)}"

        /** 「最大 62°（南南西）」 */
        val peak: String
            get() = "最大 ${peakAltDeg.roundToInt()}°（${cardinalDirection16(peakAzDeg)}）"

        /** あと何分で上がってくるか。過ぎているぶんは 0 に丸めない（呼ぶ側で見分ける） */
        fun risesInMinutes(nowMillis: Long): Double = (risesAtMillis - nowMillis) / 60_000.0
    }

    /**
     * これから見えるパス。**近い順**に返す。
     *
     * 静止軌道と測位衛星は返らない。**同じ場所に居続けるものに「次のパス」は無い**し、
     * 高度 20,000km の機体は上がってきても肉眼では点にもならないので、
     * [PASS_MAX_RANGE_KM] より遠い機体は落とす。
     *
     * 1 機につき**最初に条件を満たすパス 1 本だけ**を返す。同じ機体の 2 本目を並べても、
     * 見に行く判断は変わらない。
     *
     * @param include 機体名でさらに絞る。**名前で「なにか」が分かるものだけ**に使う（#36）
     */
    fun nextPasses(
        observer: Observer,
        epochMillis: Long,
        withinMinutes: Double = PASS_WINDOW_MIN,
        minPeakAltDeg: Double = PASS_MIN_PEAK_DEG,
        limit: Int = PASS_LIMIT,
        include: (String) -> Boolean = { true },
    ): List<Pass> {
        val steps = (withinMinutes / PASS_STEP_MIN).toInt()
        val found = ArrayList<Pass>()
        for (sgp4 in named) {
            if (!include(sgp4.tle.name)) continue
            // 上がってくるところが見たいので、いま既に出ている機体はこのパスを見送る
            // （そちらは「いま空に出ている」の一覧に載っている）
            var wasUp = true
            var riseAt = 0L
            var riseAz = 0.0
            var peakAlt = -90.0
            var peakAz = 0.0
            var peakAt = 0L
            var peakRange = Double.MAX_VALUE
            var lastAz = 0.0
            for (i in 0..steps) {
                val at = epochMillis + (i * PASS_STEP_MIN * 60_000.0).toLong()
                val state = sgp4.at(at) ?: break
                val look = observer.look(state, at)
                val up = look.altDeg > 0.0
                if (up && !wasUp) {
                    riseAt = at
                    riseAz = look.azDeg
                    peakAlt = look.altDeg
                    peakAz = look.azDeg
                    peakAt = at
                    peakRange = look.rangeKm
                }
                if (up && riseAt != 0L) {
                    if (look.altDeg > peakAlt) {
                        peakAlt = look.altDeg
                        peakAz = look.azDeg
                        peakAt = at
                        peakRange = look.rangeKm
                    }
                    lastAz = look.azDeg
                }
                // 沈んだ時点で 1 本が閉じる。低すぎるパスは捨てて次を探す
                if (!up && wasUp && riseAt != 0L) {
                    if (peakAlt >= minPeakAltDeg && peakRange <= PASS_MAX_RANGE_KM) {
                        found += pass(sgp4, riseAt, riseAz, peakAt, peakAz, peakAlt, look.azDeg)
                        break
                    }
                    riseAt = 0L
                    peakAlt = -90.0
                }
                wasUp = up
            }
            // 窓の端でまだ空にいるぶんも出す。**沈むまで待って捨てると、
            // いちばん近いパスが「窓に収まらなかった」だけで消える**
            if (riseAt != 0L && found.none { it.name == sgp4.tle.name } &&
                peakAlt >= minPeakAltDeg && peakRange <= PASS_MAX_RANGE_KM
            ) {
                found += pass(sgp4, riseAt, riseAz, peakAt, peakAz, peakAlt, lastAz)
            }
        }
        return found.sortedBy { it.risesAtMillis }.take(limit)
    }

    private fun pass(
        sgp4: Sgp4,
        riseAt: Long,
        riseAz: Double,
        peakAt: Long,
        peakAz: Double,
        peakAlt: Double,
        setAz: Double,
    ): Pass = Pass(
        name = sgp4.tle.name,
        risesAtMillis = riseAt,
        peakAtMillis = peakAt,
        riseAzDeg = riseAz,
        peakAzDeg = peakAz,
        setAzDeg = setAz,
        peakAltDeg = peakAlt,
        // 日照はいちばん高いところで見る。そこがいちばん明るく、探すのもそこ
        sunlitAtPeak = sgp4.at(peakAt)?.let { isSunlit(it, peakAt) } ?: false,
    )

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

        /**
         * パス予報を探す窓と刻み。
         *
         * ISS の周期が 92 分なので、90 分あれば低軌道の機体はおおむね 1 回は回ってくる。
         * 刻みは 0.5 分。パスそのものが 10 分ほどなので、これで出る時刻は 30 秒の精度になる
         * （待ち合わせに使うには十分で、名前つき 24 機 × 180 点なら伝播も数 ms）。
         */
        private const val PASS_WINDOW_MIN = 90.0
        private const val PASS_STEP_MIN = 0.5

        /**
         * これ未満のパスは出さない[度]。
         *
         * **街中では 20° より下は建物と木で見えない。** 低いパスまで並べると、
         * 出かけても見えないものを待つことになる。
         */
        private const val PASS_MIN_PEAK_DEG = 20.0

        /**
         * これより遠い機体はパスとして出さない[km]。
         *
         * 測位衛星（20,000km）と静止衛星（36,000km）を落とすための線。
         * **遠い機体は上がってきても肉眼では見えず、「次はいつ」に意味が無い。**
         */
        private const val PASS_MAX_RANGE_KM = 2_500.0

        /** 並べるパスの数。**近い順に数本**あれば待つかどうかは決められる */
        private const val PASS_LIMIT = 4

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
