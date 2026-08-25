package jp.jig.glasses.sample.kmp.sky

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

/**
 * 星図に出す太陽系の天体。**地球以外の 8 惑星と月**（水金地火木土天海冥の地は足元）。
 *
 * **肉眼で見えないものも出す。** 天王星（5.7 等）から先は目では見えないが、
 * 「そこに何があるか」を知りたいのがこのアプリなので、名前つきの点として置く。
 * 見えないことは等級で AI へ伝わる（[ObservedStarFact.magnitude]）。
 */
enum class SolarSystemBody(val nameJa: String, val id: Int) {
    /** 一番確実な目印。名前を知らない人が居ない */
    MOON("月", -1),
    VENUS("金星", -2),
    MARS("火星", -3),
    JUPITER("木星", -4),
    SATURN("土星", -5),
    MERCURY("水星", -6),
    URANUS("天王星", -7),
    NEPTUNE("海王星", -8),

    /** 惑星ではないが、水金地火木土天海冥で覚えた並びの最後。14 等なので目では見えない */
    PLUTO("冥王星", -9),
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
        distanceAu = distance * EARTH_EQUATORIAL_RADIUS_KM / AU_KM,
        magnitude = moonMagnitude(norm360(lon) - sunLon),
    )
}

/** 惑星の地心黄道座標。木星と土星は共鳴による大きな摂動（最大 0.8°）まで入れる */
fun planetPosition(body: SolarSystemBody, epochMillis: Long): BodyPosition {
    require(body != SolarSystemBody.MOON) { "月は moonPosition で計算する" }
    val d = schlyterDays(epochMillis)
    // **冥王星だけはケプラー要素では出せない。** 海王星との 3:2 共鳴で周期的に大きく振れるので、
    // Schlyter の専用級数（1800〜2100 年で 1〜2′）を使う
    if (body == SolarSystemBody.PLUTO) return plutoPosition(d)
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
        SolarSystemBody.URANUS -> {
            val uranusMean = norm360(142.5905 + 0.011725806 * d)
            helioLon += 0.040 * sinD(saturnMean - 2.0 * uranusMean + 6.0) +
                0.035 * sinD(saturnMean - 3.0 * uranusMean + 33.0) -
                0.015 * sinD(jupiterMean - uranusMean + 20.0)
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
        (EARTH_EQUATORIAL_RADIUS_KM / (position.distanceAu * AU_KM)).coerceIn(-1.0, 1.0),
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
    val hundredth = phase / 100.0
    return when (body) {
        // 水星は位相角が 0〜180° まで回るので、3 次まで要る（内惑星なので細く欠ける）
        SolarSystemBody.MERCURY ->
            -0.36 + base + 3.80 * hundredth - 2.73 * hundredth * hundredth +
                2.00 * hundredth * hundredth * hundredth
        SolarSystemBody.VENUS ->
            -4.40 + base + 0.0009 * phase + 0.000239 * phase * phase - 6.5e-7 * phase * phase * phase
        SolarSystemBody.MARS -> -1.52 + base + 0.016 * phase
        SolarSystemBody.JUPITER -> -9.40 + base + 0.005 * phase
        SolarSystemBody.SATURN -> -8.88 + base + 0.044 * phase
        // 外の 3 つは位相角が 3° も動かないので、距離だけで決まる
        SolarSystemBody.URANUS -> -7.19 + base
        SolarSystemBody.NEPTUNE -> -6.87 + base
        SolarSystemBody.PLUTO -> -1.01 + base
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
    SolarSystemBody.MERCURY -> Elements(
        node = 48.3313 + 3.24587e-5 * d,
        inclination = 7.0047 + 5.00e-8 * d,
        perihelion = 29.1241 + 1.01444e-5 * d,
        axis = 0.387098,
        eccentricity = 0.205635 + 5.59e-10 * d,
        meanAnomaly = norm360(168.6562 + 4.0923344368 * d),
    )
    SolarSystemBody.URANUS -> Elements(
        node = 74.0005 + 1.3978e-5 * d,
        inclination = 0.7733 + 1.9e-8 * d,
        perihelion = 96.6612 + 3.0565e-5 * d,
        axis = 19.18171 - 1.55e-8 * d,
        eccentricity = 0.047318 + 7.45e-9 * d,
        meanAnomaly = norm360(142.5905 + 0.011725806 * d),
    )
    SolarSystemBody.NEPTUNE -> Elements(
        node = 131.7806 + 3.0173e-5 * d,
        inclination = 1.7700 - 2.55e-7 * d,
        perihelion = 272.8461 - 6.027e-6 * d,
        axis = 30.05826 + 3.313e-8 * d,
        eccentricity = 0.008606 + 2.15e-9 * d,
        meanAnomaly = norm360(260.2471 + 0.005995147 * d),
    )
    SolarSystemBody.PLUTO -> error("冥王星は plutoPosition で計算する")
    SolarSystemBody.MOON -> error("月は moonPosition で計算する")
}

/**
 * 冥王星の地心黄道座標。
 *
 * 海王星との 3:2 共鳴で軌道要素が周期的に大きく動くので、ケプラー要素では出せない。
 * Schlyter の級数（平均近点角 P と土星との組み合わせ S の三角級数）で日心座標を出し、
 * 太陽の地心ベクトルを足して地心へ移す。**1800〜2100 年の外では使えない。**
 */
private fun plutoPosition(d: Double): BodyPosition {
    val s = 50.03 + 0.033459652 * d
    val p = 238.95 + 0.003968789 * d

    val helioLon = 238.9508 + 0.00400703 * d -
        19.799 * sinD(p) + 19.848 * cosD(p) +
        0.897 * sinD(2.0 * p) - 4.956 * cosD(2.0 * p) +
        0.610 * sinD(3.0 * p) + 1.211 * cosD(3.0 * p) -
        0.341 * sinD(4.0 * p) - 0.190 * cosD(4.0 * p) +
        0.128 * sinD(5.0 * p) - 0.034 * cosD(5.0 * p) -
        0.038 * sinD(6.0 * p) + 0.031 * cosD(6.0 * p) +
        0.020 * sinD(s - p) - 0.010 * cosD(s - p)
    val helioLat = -3.9082 -
        5.453 * sinD(p) - 14.975 * cosD(p) +
        3.527 * sinD(2.0 * p) + 1.673 * cosD(2.0 * p) -
        1.051 * sinD(3.0 * p) + 0.328 * cosD(3.0 * p) +
        0.179 * sinD(4.0 * p) - 0.292 * cosD(4.0 * p) +
        0.019 * sinD(5.0 * p) + 0.100 * cosD(5.0 * p) -
        0.031 * sinD(6.0 * p) - 0.026 * cosD(6.0 * p) +
        0.011 * cosD(s - p)
    val helioRadius = 40.72 +
        6.68 * sinD(p) + 6.90 * cosD(p) -
        1.18 * sinD(2.0 * p) - 0.03 * cosD(2.0 * p) +
        0.15 * sinD(3.0 * p) - 0.14 * cosD(3.0 * p)

    val sun = sunEcliptic(d)
    val x = helioRadius * cosD(helioLat) * cosD(helioLon) + sun[1] * cosD(sun[0])
    val y = helioRadius * cosD(helioLat) * sinD(helioLon) + sun[1] * sinD(sun[0])
    val z = helioRadius * sinD(helioLat)
    val geoDistance = sqrt(x * x + y * y + z * z)
    return BodyPosition(
        lonDeg = norm360(atan2(y, x) * DEG),
        latDeg = atan2(z, hypot(x, y)) * DEG,
        distanceAu = geoDistance,
        magnitude = planetMagnitude(SolarSystemBody.PLUTO, helioRadius, geoDistance, sun[1]),
    )
}

/** 月の見え方。**他の星見アプリが必ず出している情報**で、その夜の空の明るさを決める */
class MoonPhase(val ageDays: Double, val illuminated: Double, val nameJa: String)

/**
 * 月齢と満ち欠け。
 *
 * 太陽との黄経差（離角）だけで決まる。0° が新月、180° が満月。
 * **月齢は「離角 ÷ 360 × 朔望月」の近似**で、実際の朔望月は 29.27〜29.83 日で揺れるが、
 * 名前を付けるには十分（新月／三日月／上弦…）。
 */
fun moonPhase(epochMillis: Long): MoonPhase {
    val elongation = norm360(moonPosition(epochMillis).lonDeg - sunEclipticLonDeg(epochMillis))
    val age = elongation / 360.0 * SYNODIC_MONTH_DAYS
    return MoonPhase(
        ageDays = age,
        illuminated = (1.0 - cosD(elongation)) / 2.0,
        nameJa = when {
            age < 1.5 -> "新月"
            age < 5.5 -> "三日月"
            age < 9.5 -> "上弦の月"
            age < 13.0 -> "十三夜の月"
            age < 16.5 -> "満月"
            age < 20.5 -> "寝待月"
            age < 24.5 -> "下弦の月"
            age < 27.5 -> "有明の月"
            else -> "新月"
        },
    )
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

/**
 * 地平線より上にいる月・惑星。**視野は問わない**。
 *
 * [bodiesInView] が「いま向いている先に何があるか」なのに対し、こちらは
 * 「**いまこの空に何が出ているか**」。一口メモ（`SkyTips`）のように、
 * 視線と無関係に「今夜は木星が見えます」と言いたい場面がある。
 *
 * 並びは**明るい順**。目印になるかどうかは等級で決まる。
 */
fun bodiesUp(
    site: Site,
    epochMillis: Long,
    limitMagnitude: Double = NAKED_EYE_MAGNITUDE,
): List<ObservedStarFact> = SolarSystemBody.entries.mapNotNull { body ->
    val position = bodyPosition(body, epochMillis)
    if (position.magnitude > limitMagnitude) return@mapNotNull null
    val aa = bodyAltAz(body, site, epochMillis)
    if (aa[1] < 0.0) return@mapNotNull null
    ObservedStarFact(body.nameJa, position.magnitude, aa[0], aa[1], distanceFromCenterDeg = 0.0)
}.sortedBy { it.magnitude }

/**
 * 星図に出す太陽系天体の等級の下限。
 *
 * **肉眼の限界（6 等）では切らない。** 天王星・海王星・冥王星は目に見えないが、
 * 「いまその方向に何があるか」を知りたいのがこのアプリなので、名前つきの点として出す。
 * 冥王星が 16 等まで暗くなるので、そこが入る値にしてある。
 */
const val BODY_LIMIT_MAGNITUDE = 17.0

/** ここより暗いものは肉眼では見えない。AI に「見えている」と言わせないために渡す */
const val NAKED_EYE_MAGNITUDE = 6.0

/** 朔望月[日]。月齢の目安に使う */
private const val SYNODIC_MONTH_DAYS = 29.53059

private const val KEPLER_ITERATIONS = 5
