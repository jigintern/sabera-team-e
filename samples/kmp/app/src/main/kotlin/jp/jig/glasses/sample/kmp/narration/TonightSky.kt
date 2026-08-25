package jp.jig.glasses.sample.kmp.narration

import android.content.Context
import jp.jig.glasses.sample.kmp.sky.Site
import jp.jig.glasses.sample.kmp.sky.SolarSystemBody
import jp.jig.glasses.sample.kmp.sky.bodiesUp
import jp.jig.glasses.sample.kmp.sky.bodyAltAz
import jp.jig.glasses.sample.kmp.sky.daysFromJ2000
import jp.jig.glasses.sample.kmp.sky.localSiderealDeg
import jp.jig.glasses.sample.kmp.sky.moonPhase
import jp.jig.glasses.sample.kmp.sky.sunAltitudeDeg
import jp.jig.glasses.sample.kmp.sky.toApparentAltAz
import jp.jig.glasses.sample.kmp.support.BundledData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId

/**
 * いまの空から、一口メモの材料（[SkyTips.Sky]）を作る。
 *
 * **Android に触るのはここまで。** 文面を組むのは [SkyTips] で、そちらは Android に触らないので
 * JVM テストで固定できる。同梱データを読むのと時計を見るのがここの仕事。
 *
 * **同じ材料をいくつもの画面が要る**（起動直後のグラス表示・読み込み中・ダブルタップ）ので、
 * 作り方を 1 か所に置く。散らばると、画面ごとに違うことを言い始める。
 *
 * **星表は要らない。** 中身は時刻と場所の計算と、2KB の流星群だけなので、
 * **星表を読み終わる前でも作れる**（起動直後に出せるのはこのため）。
 */
suspend fun tonightSky(
    context: Context,
    site: Site,
    atMillis: Long,
    zoneId: ZoneId = ZoneId.systemDefault(),
    /**
     * まもなく上がってくる衛星。**軌道要素（10,748 機・1.8MB）はここでは読まない**ので、
     * 出せる画面が呼ぶ側で用意して渡す。
     */
    risingPass: SkyTips.RisingPass? = null,
): SkyTips.Sky {
    val showers = BundledData.showers(context)
    return withContext(Dispatchers.Default) {
        // シミュレーション中は端末の暦ではなく、指定都市の暦で「今日」と「今夜」を決める。
        val local = Instant.ofEpochMilli(atMillis).atZone(zoneId)
        val month = local.monthValue
        val day = local.dayOfMonth
        val shower = showers.today(month, day)?.let { target ->
            // 放射点は J2000 の赤経・赤緯。星図に焼く印と同じ変換を通す
            val lst = localSiderealDeg(daysFromJ2000(atMillis), site.lonDeg)
            val aa = toApparentAltAz(target.raDeg, target.decDeg, lst, site.latDeg)
            SkyTips.ActiveShower(
                nameJa = target.nameJa,
                zhr = target.zhr,
                nearPeak = showers.nearPeak(target, month, day),
                daysToPeak = target.daysToPeak(month, day),
                radiantAzDeg = aa[0],
                radiantAltDeg = aa[1],
            )
        }
        SkyTips.Sky(
            site = site,
            hourOfDay = local.hour,
            month = month,
            sunAltDeg = sunAltitudeDeg(site, atMillis),
            moon = moonPhase(atMillis),
            moonAltDeg = bodyAltAz(SolarSystemBody.MOON, site, atMillis)[1],
            bodiesUp = bodiesUp(site, atMillis),
            risingPass = risingPass,
            shower = shower,
        )
    }
}
