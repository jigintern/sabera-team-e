package jp.jig.glasses.sample.kmp.satellite

import android.content.Context
import jp.jig.glasses.sample.kmp.starmap.Look
import jp.jig.glasses.sample.kmp.starmap.SkyTrack
import jp.jig.glasses.sample.kmp.starmap.enu
import kotlin.math.roundToInt

/**
 * いま空にいる人工衛星を集めて、星図に重ねられる形にする。
 *
 * **名前つきの 16 機とスターリンクの群れでは扱いが違う。**
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
    ) {
        /** 「南南西 高度 45° 720km」のような表示 */
        val where: String
            get() {
                val points = listOf(
                    "北", "北北東", "北東", "東北東", "東", "東南東", "南東", "南南東",
                    "南", "南南西", "南西", "西南西", "西", "西北西", "北西", "北北西",
                )
                val i = ((azDeg + 11.25) / 22.5).toInt() % 16
                return "${points[i]} 高度 ${altDeg.roundToInt()}° ${rangeKm.roundToInt()}km"
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
        // 画像は 16:10 なので対角の半分は横の半分の約 1.18 倍。
        // 画面の端に入ってくる機体も拾いたいので、さらに 1.3 倍の余裕を取る
        val radiusDeg = fovDeg * 0.5 * 1.18 * 1.3
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
        )
    }

    companion object {
        /** 名前を出す数。衛星モードでは星座名を出さないので、テキスト枠 8 個を丸ごと使える */
        const val MAX_NAMED = 8

        /** 点だけ打つスターリンクの数。多すぎると星図が点で埋まる */
        const val MAX_STARLINK = 8

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
