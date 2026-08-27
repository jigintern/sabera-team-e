package jp.jig.glasses.sample.kmp.satellite

import android.content.Context
import jp.jig.glasses.sample.kmp.sky.Look
import jp.jig.glasses.sample.kmp.sky.GuidanceTarget
import jp.jig.glasses.sample.kmp.sky.GuidanceTargetKind
import jp.jig.glasses.sample.kmp.sky.apparentAltitudeDeg
import jp.jig.glasses.sample.kmp.sky.enu
import jp.jig.glasses.sample.kmp.support.DAY_MILLIS
import jp.jig.glasses.sample.kmp.support.MINUTE_MILLIS
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

    /**
     * 大気差を入れた見かけの位置。
     *
     * **星と同じ物差しに揃える。** 星・星座線・月惑星は [apparentAltitudeDeg] を通っているのに
     * 衛星だけ幾何学的高度のまま重ねていたので、同じ 1 枚の中で地平線際 0.48°・高度 5° で 0.17° の
     * 系統差が出ていた。距離は変わらないので、最接近の並べ替えには影響しない。
     */
    private fun Topocentric.apparent(): Topocentric =
        Topocentric(azDeg, apparentAltitudeDeg(altDeg), rangeKm)

    /** TLE の古さ[日]。取得日が分からなければ null */
    fun ageDays(nowMillis: Long): Double? {
        val text = fetchedAt?.trim() ?: return null
        val parsed = runCatching {
            java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }.parse(text)
        }.getOrNull() ?: return null
        return (nowMillis - parsed.time) / DAY_MILLIS.toDouble()
    }

    /**
     * 軌道要素そのものの古さ[日]。名前つき衛星の中央値を取る。
     *
     * **位置のずれを決めるのは取得日ではなく元期。** 取得日は「いつ落としたか」でしかなく、
     * キャッシュを使い回すと実際より新しく見える（元期は落とした時点で数時間〜数日前）。
     */
    fun elementAgeDays(nowMillis: Long): Double? {
        if (named.isEmpty()) return null
        val ages = named.map { (nowMillis - it.tle.epochUnixMillis) / DAY_MILLIS.toDouble() }.sorted()
        return ages[ages.size / 2]
    }

    /** 空にいる衛星 1 機ぶんの情報。スマホ側の一覧にも使う */
    /** 視野に入っている衛星を返す */
    fun tracksInView(
        observer: Observer,
        epochMillis: Long,
        look: Look,
        fovDeg: Double,
        /**
         * 表示面の縦横比（高さ／幅）。軌道計算はパネルの都合を知らないほうが検算しやすいので、
         * glass の寸法は呼び出し側から渡す（既定値にすると値の複製になる）
         */
        panelAspect: Double,
        maxNamed: Int = MAX_NAMED,
        maxStarlink: Int = MAX_STARLINK,
    ): List<SkyTrack> {
        val forward = enu(look.azDeg, look.altDeg)
        // **fovDeg は視野の「横幅」なので、視線からの角度は半分で見る。**
        // パネルの対角まで含め、端へ入ってくる機体を少し早めに拾う。
        val diagonalScale = hypot(1.0, panelAspect)
        val radiusDeg = fovDeg * 0.5 * diagonalScale * VIEW_MARGIN_SCALE
        val cosLimit = kotlin.math.cos(radiusDeg * (Math.PI / 180.0))

        fun inView(azDeg: Double, altDeg: Double): Boolean {
            val v = enu(azDeg, altDeg)
            return (v dot forward) > cosLimit
        }

        // **視野で絞ってから動きを調べる。** 動きの計算は 1 機あたり 51 回の伝播で、
        // 視野に入るのはたいてい 0〜3 機。先に motion まで作ると、そのほとんどを捨てることになる
        // （視野の判定に使うのは「いま」の方位と高度だけなので、順番を変えても答えは同じ）
        val namedTracks = named.mapNotNull { sgp4 -> track(sgp4, observer, epochMillis, labelled = true) }
            .filter { inView(it.track.nowAzDeg, it.track.nowAltDeg) }
            .sortedByDescending { it.track.nowAltDeg }
            .take(maxNamed)
            .map { it.withMotion(observer, epochMillis) }

        // **近い順に選ぶ。** カタログの並び順で先着 8 機にすると、
        // 視野の隅にいる遠い機体が、真ん中を通る近い機体を押しのける。
        // maxStarlink が 0 なら 10,748 機を回さずに帰る（印だけ動かすときはこの道）
        if (maxStarlink <= 0) return namedTracks
        val starlinkCandidates = ArrayList<Pair<Double, Sgp4>>()
        for (sgp4 in starlink) {
            val state = sgp4.at(epochMillis) ?: continue
            val now = observer.look(state, epochMillis).apparent()
            if (now.altDeg <= 0.0 || !inView(now.azDeg, now.altDeg)) continue
            starlinkCandidates += now.rangeKm to sgp4
        }
        val starlinkTracks = starlinkCandidates
            .sortedBy { it.first }
            .take(maxStarlink)
            .mapNotNull { (_, sgp4) -> track(sgp4, observer, epochMillis, labelled = false)?.track }

        return namedTracks + starlinkTracks
    }

    /** 名前付き衛星を案内候補へ変える。地平線の下も断る根拠として返す。 */
    fun guidanceTargets(
        observer: Observer,
        epochMillis: Long,
        include: (String) -> Boolean = { true },
    ): List<GuidanceTarget> = named.mapNotNull { sgp4 ->
        if (!include(sgp4.tle.name)) return@mapNotNull null
        val state = sgp4.at(epochMillis) ?: return@mapNotNull null
        val look = observer.look(state, epochMillis).apparent()
        GuidanceTarget(
            id = "satellite:${sgp4.tle.noradId}",
            nameJa = sgp4.tle.name,
            kind = GuidanceTargetKind.SATELLITE,
            aim = Look(look.azDeg, look.altDeg),
            aliases = satelliteAliases(sgp4.tle.name),
        )
    }

    /** 60秒の案内中に動く衛星だけ、同じIDから現在位置を引き直す。 */
    fun refreshGuidanceTarget(
        target: GuidanceTarget,
        observer: Observer,
        epochMillis: Long,
    ): GuidanceTarget? {
        if (target.kind != GuidanceTargetKind.SATELLITE) return target
        val number = target.id.substringAfter("satellite:").toIntOrNull() ?: return null
        val sgp4 = named.firstOrNull { it.tle.noradId == number } ?: return null
        val state = sgp4.at(epochMillis) ?: return null
        val look = observer.look(state, epochMillis).apparent()
        return target.copy(aim = Look(look.azDeg, look.altDeg))
    }

    private fun satelliteAliases(name: String): Set<String> = when {
        name == "ISS" -> setOf("国際宇宙ステーション", "きぼう")
        name.startsWith("みちびき") -> setOf(name + "号", name.replace("R", "号機後継機"))
        name.startsWith("ひまわり") -> setOf(name + "号")
        else -> emptySet()
    }

    /** 軌道要素を読めたか。assets が入っていないと空になる */
    val loaded: Boolean get() = named.isNotEmpty()

    /** 頭上にいるスターリンクの数。「いま何機飛んでいるか」を出すために数えるだけ */
    fun starlinkAboveHorizon(observer: Observer, epochMillis: Long): Int =
        starlink.count { sgp4 ->
            val state = sgp4.at(epochMillis) ?: return@count false
            observer.look(state, epochMillis).apparent().altDeg > 0.0
        }

    /**
     * 1 機ぶんの見え方。
     *
     * **軌跡の線をやめたので、伝播するのは「いま」の 1 点だけ。**
     * 以前は前後 3 分ぶんを 10 秒刻みで 19 回伝播していた（線を引くため）。
     */
    private fun track(sgp4: Sgp4, observer: Observer, epochMillis: Long, labelled: Boolean): Located? {
        val state = sgp4.at(epochMillis) ?: return null
        val now = observer.look(state, epochMillis).apparent()
        if (now.altDeg <= 0.0) return null
        return Located(
            sgp4,
            SkyTrack(
                name = sgp4.tle.name,
                nowAzDeg = now.azDeg,
                nowAltDeg = now.altDeg,
                sunlit = isSunlit(state, epochMillis),
                labelled = labelled,
            ),
        )
    }

    /**
     * 「いま」の位置まで求めた 1 機。**動きはまだ調べていない。**
     *
     * 視野で絞ってから [withMotion] を呼ぶために、元の軌道要素を持ったまま渡す。
     */
    private inner class Located(private val sgp4: Sgp4, val track: SkyTrack) {
        /** 名前を出さない機体では時間も出さないので、そのぶんの伝播を省く */
        fun withMotion(observer: Observer, epochMillis: Long): SkyTrack =
            if (track.labelled) track.copy(motion = motion(sgp4, observer, epochMillis)) else track
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
            val at = epochMillis + (minutes * MINUTE_MILLIS).toLong()
            val state = sgp4.at(at) ?: return null
            val look = observer.look(state, at).apparent()
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
