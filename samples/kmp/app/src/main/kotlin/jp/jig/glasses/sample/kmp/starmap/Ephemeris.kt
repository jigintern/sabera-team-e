package jp.jig.glasses.sample.kmp.starmap

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 月と惑星の位置。**天体アライメントの合わせ先を「誰でも分かるもの」にするために要る。**
 *
 * 同梱の星表は 1.6 等までの 25 星しか名前を持っておらず、そのうち高度 15〜60° に入るのは
 * 夜によって 2〜6 個で、建物や雲に隠れると合わせ先が無くなる。**月と明るい惑星が入れば、
 * 初心者がいちばん確実に見つけられる相手になる**（スマホの星見アプリはどれも
 * 「太陽・月・金星・木星に合わせろ」と案内している）。
 *
 * 精度は**視差と摂動を入れて 0.1° 以内**を狙う。星図を重ねる要求（±2〜3°）より一桁良ければよく、
 * 合わせ先の位置そのものがアライメントの基準になるので、ここが 0.5° ずれるとその分そのまま乗る。
 *
 * 出典は Paul Schlyter の軌道要素と摂動項（Astronomical Almanac の簡易式と同系）。
 * 等級は Astronomical Almanac (1984) の位相角多項式。**Android に依存させず JVM テストで固定する。**
 */

/** 動く合わせ先。恒星と違って毎回計算する */
enum class SolarSystemBody(val nameJa: String, val id: Int) {
    /** 一番確実な合わせ先。名前を知らない人が居ない */
    MOON("月", -1),
    VENUS("金星", -2),
    MARS("火星", -3),
    JUPITER("木星", -4),
    SATURN("土星", -5),
}

/** 地心の黄道座標（その日の平均分点）と、見かけの明るさ */
class BodyPosition(
    val lonDeg: Double,
    val latDeg: Double,
    val distanceAu: Double,
    val magnitude: Double,
)

/**
 * Schlyter の軌道要素は 2000 年 1 月 0.0 日（＝ 1999-12-31 00:00 UT、JD 2451543.5）を原点にする。
 * こちらの [daysFromJ2000] は J2000.0（JD 2451545.0）なので 1.5 日ずれる。
 * **ここを間違えると月が 20° 動く。**
 */
private fun schlyterDays(epochMillis: Long): Double = daysFromJ2000(epochMillis) + 1.5

private fun sinD(deg: Double) = sin(deg * RAD)
private fun cosD(deg: Double) = cos(deg * RAD)
private fun norm360(deg: Double) = ((deg % 360.0) + 360.0) % 360.0

/** 太陽の黄経[度]と距離[au]。惑星を地心へ移すのに使う（[sunPosition] とは別の系統だが同じ精度帯） */
private fun sunEcliptic(d: Double): DoubleArray {
    val w = 282.9404 + 4.70935e-5 * d
    val e = 0.016709 - 1.151e-9 * d
    val m = norm360(356.0470 + 0.9856002585 * d)
    val eccentric = m + e * DEG * sinD(m) * (1.0 + e * cosD(m))
    val xv = cosD(eccentric) - e
    val yv = sqrt(1.0 - e * e) * sinD(eccentric)
    val trueAnomaly = atan2(yv, xv) * DEG
    return doubleArrayOf(norm360(trueAnomaly + w), hypot(xv, yv))
}

/**
 * 月の地心黄道座標。Schlyter の主要摂動（出差・二均差・年差・視差不等など）まで入れて 2′ 程度。
 *
 * **視差の補正は [bodyAltAz] 側で入れる。** 月は地心と地上で最大 57′（満月の直径 2 個ぶん）
 * ずれるので、入れないとアライメントの基準が 1° 狂う。
 */
fun moonPosition(epochMillis: Long): BodyPosition {
    val d = schlyterDays(epochMillis)
    val node = 125.1228 - 0.0529538083 * d
    val inclination = 5.1454
    val perigee = 318.0634 + 0.1643573223 * d
    val axis = 60.2666
    val eccentricity = 0.054900
    val meanAnomaly = norm360(115.3654 + 13.0649929509 * d)

    var eccentric = meanAnomaly + eccentricity * DEG * sinD(meanAnomaly) *
        (1.0 + eccentricity * cosD(meanAnomaly))
    repeat(KEPLER_ITERATIONS) {
        val delta = (eccentric - eccentricity * DEG * sinD(eccentric) - meanAnomaly) /
            (1.0 - eccentricity * cosD(eccentric))
        eccentric -= delta
    }
    val xv = axis * (cosD(eccentric) - eccentricity)
    val yv = axis * sqrt(1.0 - eccentricity * eccentricity) * sinD(eccentric)
    val trueAnomaly = atan2(yv, xv) * DEG
    val radius = hypot(xv, yv)

    val argument = trueAnomaly + perigee
    val xh = radius * (cosD(node) * cosD(argument) - sinD(node) * sinD(argument) * cosD(inclination))
    val yh = radius * (sinD(node) * cosD(argument) + cosD(node) * sinD(argument) * cosD(inclination))
    val zh = radius * sinD(argument) * sinD(inclination)
    var lon = norm360(atan2(yh, xh) * DEG)
    var lat = atan2(zh, hypot(xh, yh)) * DEG
    var distance = sqrt(xh * xh + yh * yh + zh * zh)

    // 摂動の引数。太陽との位置関係で決まる
    val sunMean = norm360(356.0470 + 0.9856002585 * d)
    val sunPerihelion = 282.9404 + 4.70935e-5 * d
    val sunLon = sunMean + sunPerihelion
    val moonLon = node + perigee + meanAnomaly
    val elongation = moonLon - sunLon
    val latitudeArgument = moonLon - node

    lon += -1.274 * sinD(meanAnomaly - 2.0 * elongation) +
        0.658 * sinD(2.0 * elongation) -
        0.186 * sinD(sunMean) -
        0.059 * sinD(2.0 * meanAnomaly - 2.0 * elongation) -
        0.057 * sinD(meanAnomaly - 2.0 * elongation + sunMean) +
        0.053 * sinD(meanAnomaly + 2.0 * elongation) +
        0.046 * sinD(2.0 * elongation - sunMean) +
        0.041 * sinD(meanAnomaly - sunMean) -
        0.035 * sinD(elongation) -
        0.031 * sinD(meanAnomaly + sunMean) -
        0.015 * sinD(2.0 * latitudeArgument - 2.0 * elongation) +
        0.011 * sinD(meanAnomaly - 4.0 * elongation)
    lat += -0.173 * sinD(latitudeArgument - 2.0 * elongation) -
        0.055 * sinD(meanAnomaly - latitudeArgument - 2.0 * elongation) -
        0.046 * sinD(meanAnomaly + latitudeArgument - 2.0 * elongation) +
        0.033 * sinD(latitudeArgument + 2.0 * elongation) +
        0.017 * sinD(2.0 * meanAnomaly + latitudeArgument)
    distance += -0.58 * cosD(meanAnomaly - 2.0 * elongation) - 0.46 * cosD(2.0 * elongation)

    return BodyPosition(
        lonDeg = norm360(lon),
        latDeg = lat,
        distanceAu = distance * EARTH_RADIUS_KM / AU_KM,
        magnitude = moonMagnitude(norm360(lon) - sunLon),
    )
}

/** 惑星の地心黄道座標。木星と土星は共鳴による大きな摂動（最大 0.8°）まで入れる */
fun planetPosition(body: SolarSystemBody, epochMillis: Long): BodyPosition {
    require(body != SolarSystemBody.MOON) { "月は moonPosition で計算する" }
    val d = schlyterDays(epochMillis)
    val element = elements(body, d)

    var eccentric = element.meanAnomaly + element.eccentricity * DEG * sinD(element.meanAnomaly) *
        (1.0 + element.eccentricity * cosD(element.meanAnomaly))
    repeat(KEPLER_ITERATIONS) {
        val delta = (eccentric - element.eccentricity * DEG * sinD(eccentric) - element.meanAnomaly) /
            (1.0 - element.eccentricity * cosD(eccentric))
        eccentric -= delta
    }
    val xv = element.axis * (cosD(eccentric) - element.eccentricity)
    val yv = element.axis * sqrt(1.0 - element.eccentricity * element.eccentricity) * sinD(eccentric)
    val trueAnomaly = atan2(yv, xv) * DEG
    val radius = hypot(xv, yv)

    val argument = trueAnomaly + element.perihelion
    val xh = radius * (
        cosD(element.node) * cosD(argument) -
            sinD(element.node) * sinD(argument) * cosD(element.inclination)
        )
    val yh = radius * (
        sinD(element.node) * cosD(argument) +
            cosD(element.node) * sinD(argument) * cosD(element.inclination)
        )
    val zh = radius * sinD(argument) * sinD(element.inclination)

    var helioLon = norm360(atan2(yh, xh) * DEG)
    var helioLat = atan2(zh, hypot(xh, yh)) * DEG
    val helioRadius = sqrt(xh * xh + yh * yh + zh * zh)

    // 木星・土星の大摂動。入れないと 0.3〜0.8° ずれる（合わせ先の位置がそのまま基準になるので効く）
    val jupiterMean = norm360(19.8950 + 0.0830853001 * d)
    val saturnMean = norm360(316.9670 + 0.0334442282 * d)
    when (body) {
        SolarSystemBody.JUPITER -> {
            helioLon += -0.332 * sinD(2.0 * jupiterMean - 5.0 * saturnMean - 67.6) -
                0.056 * sinD(2.0 * jupiterMean - 2.0 * saturnMean + 21.0) +
                0.042 * sinD(3.0 * jupiterMean - 5.0 * saturnMean + 21.0) -
                0.036 * sinD(jupiterMean - 2.0 * saturnMean) +
                0.022 * cosD(jupiterMean - saturnMean) +
                0.023 * sinD(2.0 * jupiterMean - 3.0 * saturnMean + 52.0) -
                0.016 * sinD(jupiterMean - 5.0 * saturnMean - 69.0)
        }
        SolarSystemBody.SATURN -> {
            helioLon += 0.812 * sinD(2.0 * jupiterMean - 5.0 * saturnMean - 67.6) -
                0.229 * cosD(2.0 * jupiterMean - 4.0 * saturnMean - 2.0) +
                0.119 * sinD(jupiterMean - 2.0 * saturnMean - 3.0) +
                0.046 * sinD(2.0 * jupiterMean - 6.0 * saturnMean - 69.0) +
                0.014 * sinD(jupiterMean - 3.0 * saturnMean + 32.0)
            helioLat += -0.020 * cosD(2.0 * jupiterMean - 4.0 * saturnMean - 2.0) +
                0.018 * sinD(2.0 * jupiterMean - 6.0 * saturnMean - 49.0)
        }
        else -> Unit
    }

    val sun = sunEcliptic(d)
    val sunDistance = sun[1]
    // 太陽の地心ベクトルを足すと地心になる（地球の日心位置 = −太陽の地心ベクトル）
    val x = helioRadius * cosD(helioLat) * cosD(helioLon) + sunDistance * cosD(sun[0])
    val y = helioRadius * cosD(helioLat) * sinD(helioLon) + sunDistance * sinD(sun[0])
    val z = helioRadius * sinD(helioLat)
    val geoDistance = sqrt(x * x + y * y + z * z)

    return BodyPosition(
        lonDeg = norm360(atan2(y, x) * DEG),
        latDeg = atan2(z, hypot(x, y)) * DEG,
        distanceAu = geoDistance,
        magnitude = planetMagnitude(body, helioRadius, geoDistance, sunDistance),
    )
}

fun bodyPosition(body: SolarSystemBody, epochMillis: Long): BodyPosition =
    if (body == SolarSystemBody.MOON) moonPosition(epochMillis) else planetPosition(body, epochMillis)

/** 地心の黄道座標 → その日の赤道座標[赤経, 赤緯]。星表の歳差済み座標と同じ系に乗る */
fun eclipticToEquatorial(lonDeg: Double, latDeg: Double, epochMillis: Long): DoubleArray {
    val obliquity = 23.4393 - 3.563e-7 * schlyterDays(epochMillis)
    val x = cosD(latDeg) * cosD(lonDeg)
    val y = cosD(latDeg) * sinD(lonDeg) * cosD(obliquity) - sinD(latDeg) * sinD(obliquity)
    val z = cosD(latDeg) * sinD(lonDeg) * sinD(obliquity) + sinD(latDeg) * cosD(obliquity)
    return doubleArrayOf(norm360(atan2(y, x) * DEG), asin(z.coerceIn(-1.0, 1.0)) * DEG)
}

/**
 * 観測地から見た、見かけの[方位角, 高度]。
 *
 * **地心視差と大気差を両方入れる。** 月の視差は最大 57′ で、これを落とすとアライメントの
 * 基準が 1° 狂う（惑星は 0.5″ 未満なので無視できるが、同じ式で通しても害はない）。
 */
fun bodyAltAz(body: SolarSystemBody, site: Site, epochMillis: Long): DoubleArray {
    val position = bodyPosition(body, epochMillis)
    val equatorial = eclipticToEquatorial(position.lonDeg, position.latDeg, epochMillis)
    val lst = localSiderealDeg(daysFromJ2000(epochMillis), site.lonDeg)
    val horizontal = toAltAz(equatorial[0], equatorial[1], lst, site.latDeg)
    val parallax = asin(
        (EARTH_RADIUS_KM / (position.distanceAu * AU_KM)).coerceIn(-1.0, 1.0),
    ) * DEG
    // 視差は天体を地平線の方向へ押し下げる。方位は動かない（同じ垂直圏の上を動く）
    val geometric = horizontal[1] - parallax * cosD(horizontal[1])
    return doubleArrayOf(horizontal[0], apparentAltitudeDeg(geometric))
}

/** 月の明るさ。位相角 0° の満月で −12.7 等、半月で −10 等あたり（Allen の近似） */
private fun moonMagnitude(elongationDeg: Double): Double {
    // 位相角 = 180° − 太陽との離角。満月で 0°、新月で 180°
    val radians = (180.0 - abs(normalizeDeg(elongationDeg))) * RAD
    return -12.73 + 1.49 * radians + 0.043 * radians * radians * radians * radians
}

/**
 * 惑星の明るさ。Astronomical Almanac (1984) の位相角多項式。
 * 土星の環の傾きは入れていないので、土星だけ 0.5 等ほど振れる。
 */
private fun planetMagnitude(
    body: SolarSystemBody,
    helioRadius: Double,
    geoDistance: Double,
    sunDistance: Double,
): Double {
    val cosPhase = (
        (helioRadius * helioRadius + geoDistance * geoDistance - sunDistance * sunDistance) /
            (2.0 * helioRadius * geoDistance)
        ).coerceIn(-1.0, 1.0)
    val phase = kotlin.math.acos(cosPhase) * DEG
    val base = 5.0 * log10(helioRadius * geoDistance)
    return when (body) {
        SolarSystemBody.VENUS ->
            -4.40 + base + 0.0009 * phase + 0.000239 * phase * phase - 6.5e-7 * phase * phase * phase
        SolarSystemBody.MARS -> -1.52 + base + 0.016 * phase
        SolarSystemBody.JUPITER -> -9.40 + base + 0.005 * phase
        SolarSystemBody.SATURN -> -8.88 + base + 0.044 * phase
        SolarSystemBody.MOON -> -12.7
    }
}

private class Elements(
    val node: Double,
    val inclination: Double,
    val perihelion: Double,
    val axis: Double,
    val eccentricity: Double,
    val meanAnomaly: Double,
)

private fun elements(body: SolarSystemBody, d: Double): Elements = when (body) {
    SolarSystemBody.VENUS -> Elements(
        node = 76.6799 + 2.46590e-5 * d,
        inclination = 3.3946 + 2.75e-8 * d,
        perihelion = 54.8910 + 1.38374e-5 * d,
        axis = 0.723330,
        eccentricity = 0.006773 - 1.302e-9 * d,
        meanAnomaly = norm360(48.0052 + 1.6021302244 * d),
    )
    SolarSystemBody.MARS -> Elements(
        node = 49.5574 + 2.11081e-5 * d,
        inclination = 1.8497 - 1.78e-8 * d,
        perihelion = 286.5016 + 2.92961e-5 * d,
        axis = 1.523688,
        eccentricity = 0.093405 + 2.516e-9 * d,
        meanAnomaly = norm360(18.6021 + 0.5240207766 * d),
    )
    SolarSystemBody.JUPITER -> Elements(
        node = 100.4542 + 2.76854e-5 * d,
        inclination = 1.3030 - 1.557e-7 * d,
        perihelion = 273.8777 + 1.64505e-5 * d,
        axis = 5.20256,
        eccentricity = 0.048498 + 4.469e-9 * d,
        meanAnomaly = norm360(19.8950 + 0.0830853001 * d),
    )
    SolarSystemBody.SATURN -> Elements(
        node = 113.6634 + 2.38980e-5 * d,
        inclination = 2.4886 - 1.081e-7 * d,
        perihelion = 339.3939 + 2.97661e-5 * d,
        axis = 9.55475,
        eccentricity = 0.055546 - 9.499e-9 * d,
        meanAnomaly = norm360(316.9670 + 0.0334442282 * d),
    )
    SolarSystemBody.MOON -> error("月は moonPosition で計算する")
}

/** 太陽の地心黄経[度]。日食や合の検算に使う（[sunPosition] とは独立な系統） */
fun sunEclipticLonDeg(epochMillis: Long): Double = sunEcliptic(schlyterDays(epochMillis))[0]

/**
 * 視野に入っている月・惑星。**星図の点と AI へ渡す根拠を同じ 1 か所で作る。**
 *
 * 別々に作ると「絵には出ているのに解説では触れない」「解説だけが言う」が起きる。
 * 見えていないものの話をさせないのが AI 解説の前提なので、そこは崩さない。
 */
fun bodiesInView(
    site: Site,
    epochMillis: Long,
    look: Look,
    fovDeg: Double,
    limitMagnitude: Double = BODY_LIMIT_MAGNITUDE,
): List<ObservedStarFact> {
    val center = enu(look.azDeg, look.altDeg)
    return SolarSystemBody.entries.mapNotNull { body ->
        val position = bodyPosition(body, epochMillis)
        if (position.magnitude > limitMagnitude) return@mapNotNull null
        val aa = bodyAltAz(body, site, epochMillis)
        if (aa[1] < 0.0) return@mapNotNull null
        val distance = angleBetweenDeg(enu(aa[0], aa[1]), center)
        if (distance > fovDeg / 2.0) return@mapNotNull null
        ObservedStarFact(body.nameJa, position.magnitude, aa[0], aa[1], distance)
    }.sortedBy { it.distanceFromCenterDeg }
}

/** 肉眼で見える惑星の下限。水星は入れていない（低空にしか出ず、合わせ先にも解説にも向かない） */
const val BODY_LIMIT_MAGNITUDE = 3.0

private const val KEPLER_ITERATIONS = 5
private const val EARTH_RADIUS_KM = 6378.137
private const val AU_KM = 149_597_870.7
