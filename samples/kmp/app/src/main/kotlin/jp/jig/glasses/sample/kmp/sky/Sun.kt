package jp.jig.glasses.sample.kmp.sky

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * 太陽の位置。低精度（誤差 0.01° 程度）で足りる。
 *
 * 使いみちは 3 つ。
 * - **人工衛星が地球の影に入っているか**（入っていれば肉眼では見えない）
 * - **空が暗いか**（太陽高度 -6° より下なら衛星が見える薄暮以上）
 * - 星図の昼夜判定
 *
 * 出典は Astronomical Almanac の簡易式。
 */

/** 太陽の赤経・赤緯[度]と距離[km] */
class SunPosition(val raDeg: Double, val decDeg: Double, val distanceKm: Double)

/** 1 天文単位[km] */
private const val AU_KM = 149_597_870.7

fun sunPosition(epochMillis: Long): SunPosition {
    val d = daysFromJ2000(epochMillis)
    val meanLon = (280.460 + 0.9856474 * d) * RAD
    val meanAnomaly = (357.528 + 0.9856003 * d) * RAD
    // 黄経。中心差の 2 項まででこの用途には十分
    val lambda = meanLon + (1.915 * sin(meanAnomaly) + 0.020 * sin(2.0 * meanAnomaly)) * RAD
    val obliquity = (23.439 - 0.0000004 * d) * RAD
    val ra = atan2(cos(obliquity) * sin(lambda), cos(lambda))
    val dec = asin(sin(obliquity) * sin(lambda))
    val distanceAu = 1.00014 - 0.01671 * cos(meanAnomaly) - 0.00014 * cos(2.0 * meanAnomaly)
    return SunPosition(
        raDeg = ((ra * DEG) % 360.0 + 360.0) % 360.0,
        decDeg = dec * DEG,
        distanceKm = distanceAu * AU_KM,
    )
}

/** 観測地から見た太陽の高度[度]。**-6° より下なら薄暮以上に暗い** */
fun sunAltitudeDeg(site: Site, epochMillis: Long): Double {
    val sun = sunPosition(epochMillis)
    val d = daysFromJ2000(epochMillis)
    val lst = localSiderealDeg(d, site.lonDeg)
    return toAltAz(sun.raDeg, sun.decDeg, lst, site.latDeg)[1]
}

/** 空の暗さ。衛星が見えるかどうかの目安に使う */
enum class SkyDarkness {
    /** 太陽が出ている */
    DAY,

    /** 市民薄明。まだ明るく、いちばん明るい衛星しか見えない */
    CIVIL,

    /** 航海・天文薄明から夜。衛星がよく見える */
    NIGHT,
    ;

    companion object {
        fun of(sunAltDeg: Double): SkyDarkness = when {
            sunAltDeg > -0.833 -> DAY
            sunAltDeg > -6.0 -> CIVIL
            else -> NIGHT
        }
    }
}
