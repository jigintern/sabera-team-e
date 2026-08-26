package jp.jig.glasses.sample.kmp.catalog

import android.content.Context
import java.time.Instant
import jp.jig.glasses.sample.kmp.sky.ObservationSnapshot
import jp.jig.glasses.sample.kmp.sky.daysFromJ2000
import jp.jig.glasses.sample.kmp.sky.localSiderealDeg
import jp.jig.glasses.sample.kmp.sky.toApparentAltAz
import org.json.JSONObject
import kotlin.math.abs

/**
 * 主な流星群。**日付だけで決まるので通信は要らない**（星座の解説文と同じ理由）。
 *
 * **「今夜がピーク」は見に行く理由そのもの。** 星座は一年中どれかが見えているが、
 * 流星群は逃すと次が一年後になる。それなのにアプリのどこにも出ていなかった。
 *
 * 実体は `data/meteor-showers.json`（生成物）で、値は `tools/build-meteor-showers.py` が持つ。
 * **引き当ては [Shower] 側にあり Android に触らない**ので、JVM テストで日付を固定できる。
 */
class MeteorShowers(
    val showers: List<Shower>,
    private val peakWindowDays: Int,
    /**
     * 通知（#70）で知らせる群の下限 ZHR。**正本は `meteor-showers.json`**（既定は取りこぼし防止の保険）。
     *
     * アプリ側に定数を置くと、群を足したときにデータと二重管理になる。
     */
    private val notifyZhrThreshold: Int = 50,
) {

    /**
     * 流星群 1 つ。放射点は **J2000 の赤経・赤緯**で、星表と同じ座標系。
     *
     * [zhr] は**理想条件**の 1 時間あたりの出現数。実際は空の暗さと放射点の高度で減るので、
     * 喋るときは必ず「空が暗ければ」と断る。
     */
    class Shower(
        val nameJa: String,
        val peakMonth: Int,
        val peakDay: Int,
        val startMonth: Int,
        val startDay: Int,
        val endMonth: Int,
        val endDay: Int,
        val zhr: Int,
        val raDeg: Double,
        val decDeg: Double,
    ) {
        /** 活動期間の中か。**しぶんぎ座のように年をまたぐ群がある**ので通日の大小では見ない */
        fun active(month: Int, day: Int): Boolean {
            val today = dayOfYear(month, day)
            val first = dayOfYear(startMonth, startDay)
            val last = dayOfYear(endMonth, endDay)
            return if (first <= last) today in first..last else today >= first || today <= last
        }

        /** 極大まであと何日か。過ぎていればマイナス。**年をまたぐぶんは近いほうで数える** */
        fun daysToPeak(month: Int, day: Int): Int {
            val today = dayOfYear(month, day)
            val peak = dayOfYear(peakMonth, peakDay)
            val direct = peak - today
            return when {
                direct > DAYS_IN_YEAR / 2 -> direct - DAYS_IN_YEAR
                direct < -DAYS_IN_YEAR / 2 -> direct + DAYS_IN_YEAR
                else -> direct
            }
        }
    }

    /**
     * その日にいちばん出番のある群。無ければ null。
     *
     * **極大に近いものを優先し、同じなら数の多いほうを採る。** 活動期間が 2 か月ある群
     * （おうし座南）は、ふたご座の極大の日にまで顔を出してはいけない。
     */
    fun today(month: Int, day: Int): Shower? = showers
        .filter { it.active(month, day) }
        .minWithOrNull(compareBy({ abs(it.daysToPeak(month, day)) }, { -it.zhr }))

    /** 極大の前後何日を「極大のころ」と言うか */
    fun nearPeak(shower: Shower, month: Int, day: Int): Boolean =
        abs(shower.daysToPeak(month, day)) <= peakWindowDays

    /**
     * その日に通知で知らせることがあるか。無ければ null。
     *
     * **知らせるのは「いちばんよく流れる夜」の前日と当日だけ。** 活動期間はひと月あることも
     * あるが、毎晩鳴らせば通知ごと切られる。[nearPeak] の前後 2 日ですら通知には広い。
     *
     * **数の少ない群では鳴らさない**（[notifyZhrThreshold]）。1 時間に 5 個の群で人を
     * 夜の屋外へ呼び出すと、次からもう見てもらえない。
     *
     * **Android に触らない**ので、日付を固定して JVM テストできる。
     */
    fun noticeOn(month: Int, day: Int): Notice? = showers
        .filter { it.zhr >= notifyZhrThreshold }
        .mapNotNull { shower ->
            when (shower.daysToPeak(month, day)) {
                0 -> Notice(shower, onPeakDay = true)
                1 -> Notice(shower, onPeakDay = false)
                else -> null
            }
        }
        // 前日と当日が重なる日（極大が 2 日続く群を足したとき）は**今夜のほうを採る**
        .minWithOrNull(compareBy({ if (it.onPeakDay) 0 else 1 }, { -it.shower.zhr }))

    /** 通知 1 通ぶん。[onPeakDay] が false なら「明日の夜」の予告 */
    class Notice(val shower: Shower, val onPeakDay: Boolean)

    companion object {
        val empty = MeteorShowers(emptyList(), 0, 0)

        private const val DAYS_IN_YEAR = 365

        /** うるう年を無視した通日。**流星群の極大は年で半日しか動かない**ので 1 日の差は問題にならない */
        internal fun dayOfYear(month: Int, day: Int): Int {
            val lengths = intArrayOf(31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
            var total = day
            for (i in 0 until (month - 1).coerceIn(0, 11)) total += lengths[i]
            return total
        }

        fun load(context: Context): MeteorShowers {
            val json = runCatching {
                JSONObject(
                    context.assets.open("meteor-showers.json").use {
                        it.readBytes().toString(Charsets.UTF_8)
                    },
                )
            }.getOrNull() ?: return empty
            val array = json.optJSONArray("showers") ?: return empty
            val showers = ArrayList<Shower>(array.length())
            for (i in 0 until array.length()) {
                val entry = array.getJSONObject(i)
                showers += Shower(
                    nameJa = entry.getString("nameJa"),
                    peakMonth = entry.getInt("peakMonth"),
                    peakDay = entry.getInt("peakDay"),
                    startMonth = entry.getInt("startMonth"),
                    startDay = entry.getInt("startDay"),
                    endMonth = entry.getInt("endMonth"),
                    endDay = entry.getInt("endDay"),
                    zhr = entry.getInt("zhr"),
                    raDeg = entry.getDouble("raDeg"),
                    decDeg = entry.getDouble("decDeg"),
                )
            }
            return MeteorShowers(
                showers = showers,
                peakWindowDays = json.optInt("peakWindowDays", 2),
                notifyZhrThreshold = json.optInt("notifyZhrThreshold", 50),
            )
        }
    }
}

/**
 * 今夜活動している群。放射点は **J2000 の赤経・赤緯**で持っている（星表と同じ座標系）。
 *
 * **星図の印と一口メモがここを共有する**ので、印の場所と喋る方角が食い違わない。
 */
fun MeteorShowers.activeShower(observation: ObservationSnapshot): MeteorShowers.Shower? {
    val local = Instant.ofEpochMilli(observation.epochMillis).atZone(observation.zoneId)
    return today(local.monthValue, local.dayOfMonth)
}

/** 放射点のいまの方位・高度 */
fun MeteorShowers.Shower.radiantAltAz(observation: ObservationSnapshot): DoubleArray {
    val lst = localSiderealDeg(daysFromJ2000(observation.epochMillis), observation.site.lonDeg)
    return toApparentAltAz(raDeg, decDeg, lst, observation.site.latDeg)
}
