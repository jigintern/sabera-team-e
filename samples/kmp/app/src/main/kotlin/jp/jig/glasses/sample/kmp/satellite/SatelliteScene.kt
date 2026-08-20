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
 * 名前つきは 1 機ずつ名前を出すが、スターリンクは数が多いので軌跡だけ描く
 * （線の本数そのものが「こんなに飛んでいるのか」になる）。
 */
class SatelliteScene(
    private val named: List<Sgp4>,
    private val starlink: List<Sgp4>,
) {

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

    /** 視野に入っている衛星を、軌跡つきで返す */
    fun tracksInView(
        observer: Observer,
        epochMillis: Long,
        look: Look,
        fovDeg: Double,
        maxNamed: Int = MAX_NAMED,
        maxStarlink: Int = MAX_STARLINK,
    ): List<SkyTrack> {
        val forward = enu(look.azDeg, look.altDeg)
        // 視野の少し外まで拾う。軌跡が画面の端から入ってくるのが見えるように
        val cosLimit = kotlin.math.cos((fovDeg * 0.9) * (Math.PI / 180.0))

        fun inView(azDeg: Double, altDeg: Double): Boolean {
            val v = enu(azDeg, altDeg)
            return (v dot forward) > cosLimit
        }

        val namedTracks = named.mapNotNull { track(it, observer, epochMillis, labelled = true) }
            .filter { inView(it.nowAzDeg, it.nowAltDeg) }
            .sortedByDescending { it.nowAltDeg }
            .take(maxNamed)

        val starlinkTracks = starlink.asSequence()
            .mapNotNull { sgp4 ->
                val state = sgp4.at(epochMillis) ?: return@mapNotNull null
                val now = observer.look(state, epochMillis)
                if (now.altDeg <= 0.0 || !inView(now.azDeg, now.altDeg)) return@mapNotNull null
                track(sgp4, observer, epochMillis, labelled = false)
            }
            .take(maxStarlink)
            .toList()

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

    /** 頭上にいるスターリンクの数。「いま何機飛んでいるか」を出すために数えるだけ */
    fun starlinkAboveHorizon(observer: Observer, epochMillis: Long): Int =
        starlink.count { sgp4 ->
            val state = sgp4.at(epochMillis) ?: return@count false
            observer.look(state, epochMillis).altDeg > 0.0
        }

    private fun track(sgp4: Sgp4, observer: Observer, epochMillis: Long, labelled: Boolean): SkyTrack? {
        val state = sgp4.at(epochMillis) ?: return null
        val now = observer.look(state, epochMillis)
        if (now.altDeg <= 0.0) return null

        val points = ArrayList<DoubleArray>()
        var t = -TRACK_BACK_MS
        while (t <= TRACK_AHEAD_MS) {
            val at = epochMillis + t
            val s = sgp4.at(at)
            if (s != null) {
                val look = observer.look(s, at)
                // 地平線より下は描かない。地面の下を通る線が出ると混乱する
                if (look.altDeg > 0.0) points += doubleArrayOf(look.azDeg, look.altDeg)
            }
            t += TRACK_STEP_MS
        }

        return SkyTrack(
            name = sgp4.tle.name,
            points = points,
            nowAzDeg = now.azDeg,
            nowAltDeg = now.altDeg,
            sunlit = isSunlit(state, epochMillis),
            labelled = labelled,
        )
    }

    companion object {
        /** 名前を出す数。キャンバスのテキスト枠 8 個を星座名と分け合う */
        const val MAX_NAMED = 3

        /** 軌跡だけ描くスターリンクの数。多すぎると星図が線で埋まる */
        const val MAX_STARLINK = 8

        private const val TRACK_BACK_MS = 30_000L
        private const val TRACK_AHEAD_MS = 150_000L
        private const val TRACK_STEP_MS = 10_000L

        /** 同梱した TLE を読む。**10,748 機ぶんあるので IO スレッドで呼ぶこと** */
        fun load(context: Context): SatelliteScene {
            fun read(name: String): List<Sgp4> = runCatching {
                context.assets.open(name).use { it.readBytes().toString(Charsets.UTF_8) }
            }.map { text -> Tle.parseAll(text).map { Sgp4(it) } }.getOrElse { emptyList() }

            return SatelliteScene(named = read("satellites.tle"), starlink = read("starlink.tle"))
        }
    }
}
