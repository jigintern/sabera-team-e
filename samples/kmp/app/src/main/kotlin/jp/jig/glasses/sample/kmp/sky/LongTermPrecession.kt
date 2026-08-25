package jp.jig.glasses.sample.kmp.sky

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 長期歳差（Vondrák, Capitaine & Wallace 2011, A&A 534, A22）。
 *
 * **[precess] の IAU 1976 の 3 次式は数世紀ぶんしか合わない。** 1 万年（T = 100 世紀）だと
 * `theta` の 3 次項だけで −41,833″（−11.6°）になり、式そのものが発散する。
 * このモデルは **±200,000 年**を受け持つので、深い時代はこちらへ切り替える。
 *
 * 作りは黄道の極と赤道の極を J2000 の座標で別々に求め、その 2 つから
 * 「その時代の赤道座標系」の基底を組む方式（外積で春分点を出す）。
 *
 * **係数表は論文の Table 1・2 の写し。** 桁を 1 つ間違えても静かに間違った空が出るだけなので、
 * 係数に依存しない性質（J2000 で恒等・±3 世紀で IAU 1976 と一致・極が黄道の極を
 * 円錐で回る）を [LongTermPrecessionTest] で検査している。
 */

/** J2000.0 の平均黄道傾斜角[秒]。Vondrák の定義に合わせる */
private const val EPS0_ARCSEC = 84_381.406

private const val ARCSEC_TO_RAD = Math.PI / (180.0 * 3600.0)

/** 黄道の極（P, Q）の多項式。列は [定数, T, T², T³]（単位は秒） */
private val PQ_POLY = arrayOf(
    doubleArrayOf(5851.607687, -0.1189000, -0.00028913, 0.000000101),
    doubleArrayOf(-1600.886300, 0.1440000, 0.00069478, -0.000000650),
)

/** 黄道の極の周期項。列は [周期(世紀), Pcos, Qcos, Psin, Qsin] */
private val PQ_PERIODIC = arrayOf(
    doubleArrayOf(708.15, -5486.751211, -684.661560, 667.666730, -5523.863691),
    doubleArrayOf(2309.00, -17.127623, 2446.283880, -2354.886252, -549.747450),
    doubleArrayOf(1620.00, -617.517403, 399.671049, -428.152441, -310.998056),
    doubleArrayOf(492.20, 413.442940, -356.652376, 376.202861, 421.535876),
    doubleArrayOf(1183.00, 78.614193, -186.387003, 184.778874, -36.776172),
    doubleArrayOf(622.00, -180.732815, -316.800070, 335.321713, -145.278396),
    doubleArrayOf(882.00, -87.676083, 198.296701, -185.138669, -34.744450),
    doubleArrayOf(547.00, 46.140315, 101.135679, -120.972830, 22.885731),
)

/** 赤道の極（X, Y）の多項式。列は [定数, T, T², T³]（単位は秒） */
private val XY_POLY = arrayOf(
    doubleArrayOf(5453.282155, 0.4252841, -0.00037173, -0.000000152),
    doubleArrayOf(-73750.930350, -0.7675452, -0.00018725, 0.000000231),
)

/**
 * 赤道の極の周期項。列は [周期(世紀), Xcos, Ycos, Xsin, Ysin]。
 *
 * 先頭の 256.75 世紀 ＝ **25,675 年**が歳差の主項（よく言う 25,800 年周期）。
 * その行の振幅 81,491″ ＝ 22.6° が、極が黄道の極のまわりに描く円錐の大きさにあたる。
 */
private val XY_PERIODIC = arrayOf(
    doubleArrayOf(256.75, -819.940624, 75004.344875, 81491.287984, 1558.515853),
    doubleArrayOf(708.15, -8444.676815, 624.033993, 787.163481, 7774.939698),
    doubleArrayOf(274.20, 2600.009459, 1251.136893, 1251.296102, -2219.534038),
    doubleArrayOf(241.45, 2755.175630, -1102.212834, -1257.950837, -2523.969396),
    doubleArrayOf(2309.00, -167.659835, -2660.664980, -2966.799730, 247.850422),
    doubleArrayOf(492.20, 871.855056, 699.291817, 639.744522, -846.485643),
    doubleArrayOf(396.10, 44.769698, 153.167220, 131.600209, -1393.124055),
    doubleArrayOf(288.90, -512.313065, -950.865637, -445.040117, 368.526116),
    doubleArrayOf(231.10, -819.415595, 499.754645, 584.522874, 749.045012),
    doubleArrayOf(1610.00, -538.071099, -145.188210, -89.756563, 444.704518),
    doubleArrayOf(620.00, -189.793622, 558.116553, 524.429630, 235.934465),
    doubleArrayOf(157.87, -402.922932, -23.923029, -13.549067, 374.049623),
    doubleArrayOf(220.30, 179.516345, -165.405086, -210.157124, -171.330180),
    doubleArrayOf(1200.00, -9.814756, 9.344131, -44.919798, -22.899655),
)

/** J2000 の赤道座標で表した、[centuries] 世紀後の黄道の極。 */
internal fun eclipticPole(centuries: Double): Vec3 {
    var p = polynomial(PQ_POLY[0], centuries)
    var q = polynomial(PQ_POLY[1], centuries)
    for (term in PQ_PERIODIC) {
        val angle = 2.0 * Math.PI * centuries / term[0]
        val c = cos(angle)
        val s = sin(angle)
        p += c * term[1] + s * term[3]
        q += c * term[2] + s * term[4]
    }
    val pr = p * ARCSEC_TO_RAD
    val qr = q * ARCSEC_TO_RAD
    val w = sqrt((1.0 - pr * pr - qr * qr).coerceAtLeast(0.0))
    val eps = EPS0_ARCSEC * ARCSEC_TO_RAD
    val sinEps = sin(eps)
    val cosEps = cos(eps)
    // P・Q は黄道面での成分なので、J2000 の黄道傾斜角ぶん起こして赤道座標へ移す
    return Vec3(pr, -qr * cosEps - w * sinEps, -qr * sinEps + w * cosEps)
}

/** J2000 の赤道座標で表した、[centuries] 世紀後の赤道の極（＝その時代の天の北極）。 */
internal fun equatorPole(centuries: Double): Vec3 {
    var x = polynomial(XY_POLY[0], centuries)
    var y = polynomial(XY_POLY[1], centuries)
    for (term in XY_PERIODIC) {
        val angle = 2.0 * Math.PI * centuries / term[0]
        val c = cos(angle)
        val s = sin(angle)
        x += c * term[1] + s * term[3]
        y += c * term[2] + s * term[4]
    }
    val xr = x * ARCSEC_TO_RAD
    val yr = y * ARCSEC_TO_RAD
    return Vec3(xr, yr, sqrt((1.0 - xr * xr - yr * yr).coerceAtLeast(0.0)))
}

private fun polynomial(coefficients: DoubleArray, t: Double): Double =
    coefficients[0] + t * (coefficients[1] + t * (coefficients[2] + t * coefficients[3]))

/**
 * J2000 の赤道座標 → [centuries] 世紀後の平均赤道座標へ移す回転（行ベクトルで 3 本）。
 *
 * 春分点は「その時代の赤道の極 × 黄道の極」で決まる。2 つの極から組むので、
 * 黄道傾斜角の長期変化（22.0°〜24.5°）も自然に入る。
 */
internal fun longTermPrecessionBasis(centuries: Double): Array<Vec3> {
    val equator = equatorPole(centuries).normalized()
    val ecliptic = eclipticPole(centuries).normalized()
    val equinox = (equator cross ecliptic).normalized()
    val north = equator cross equinox
    return arrayOf(equinox, north, equator)
}

/**
 * 1 世紀ぶんの基底を覚えておく。
 *
 * **1 枚の星図では [centuries] が全星で同じ**（1,627 星ぶん同じ計算を繰り返す）。
 * 周期項が 22 行あるので、毎回組むと 1 枚で 3 万回を超える三角関数になる。
 * 取り違えても組み直すだけなので、ロックは要らない。
 */
private class CachedBasis(val centuries: Double, val basis: Array<Vec3>)

@Volatile
private var cachedBasis: CachedBasis? = null

internal fun longTermPrecessionBasisCached(centuries: Double): Array<Vec3> {
    val cached = cachedBasis
    if (cached != null && cached.centuries == centuries) return cached.basis
    val basis = longTermPrecessionBasis(centuries)
    cachedBasis = CachedBasis(centuries, basis)
    return basis
}

/** J2000 の赤道座標を、J2000 から [d] 日後の平均赤道座標へ移す。返すのは [赤経, 赤緯] */
fun longTermPrecess(raDeg: Double, decDeg: Double, d: Double): DoubleArray {
    val basis = longTermPrecessionBasisCached(centuriesFromJ2000(d))
    val v = equatorialUnitVector(raDeg, decDeg)
    return equatorialDegrees(Vec3(v dot basis[0], v dot basis[1], v dot basis[2]))
}

/** [longTermPrecess] の逆。基底の転置を掛けるので、同じ表の中では正確に戻る */
fun longTermInversePrecess(raDeg: Double, decDeg: Double, d: Double): DoubleArray {
    val basis = longTermPrecessionBasisCached(centuriesFromJ2000(d))
    val v = equatorialUnitVector(raDeg, decDeg)
    return equatorialDegrees(basis[0] * v.x + basis[1] * v.y + basis[2] * v.z)
}

/** ユリウス世紀。1 ユリウス年 = 365.25 日 */
fun centuriesFromJ2000(d: Double): Double = d / 36_525.0

internal fun equatorialUnitVector(raDeg: Double, decDeg: Double): Vec3 {
    val ra = raDeg * RAD
    val dec = decDeg * RAD
    return Vec3(cos(dec) * cos(ra), cos(dec) * sin(ra), sin(dec))
}

internal fun equatorialDegrees(v: Vec3): DoubleArray {
    val n = v.normalized()
    return doubleArrayOf(
        ((Math.atan2(n.y, n.x) * DEG) % 360.0 + 360.0) % 360.0,
        Math.asin(n.z.coerceIn(-1.0, 1.0)) * DEG,
    )
}

/**
 * ここを超えたら長期歳差へ切り替える境目。**3 世紀。**
 *
 * IAU 1976 の式はこのあたりまでなら数秒角で一致する（[LongTermPrecessionTest] が検査）。
 * 近い時代で式を変えると、これまでに実機で確かめた星図が動いてしまうので切り替えない。
 */
const val LONG_TERM_PRECESSION_DAYS = 3.0 * 36_525.0

/**
 * 固有運動を [d] 日ぶん進めてから歳差をかける。
 *
 * **1 万年前の空の見どころは、星座の形が変わること。** アークトゥルスは 2,310 mas/年 ＝
 * 1 万年で **6.4°** 動くので、歳差だけ入れて固有運動を入れないと「向きだけ違う今の星空」に
 * しかならない。
 *
 * 動かし方は接平面での大円移動（[raDeg] に度をそのまま足さない）。足し算だと
 * **天の極の近くで破綻する**し、数度動かすと形も歪む。
 *
 * 視線速度を持っていないので**見かけの固有運動が変わっていく効果は入らない**。
 * 近い星ほど誤差が出るが、星座の同定（±20°）には十分な近似。
 *
 * @param pmRaMasPerYear μα·cos δ [mas/年]。**すでに cos δ が掛かっている**ので割り戻さない
 */
fun precessWithProperMotion(
    raDeg: Double,
    decDeg: Double,
    pmRaMasPerYear: Double,
    pmDecMasPerYear: Double,
    d: Double,
): DoubleArray {
    if (pmRaMasPerYear == 0.0 && pmDecMasPerYear == 0.0) return precess(raDeg, decDeg, d)

    val years = d / 365.25
    val perYearToRad = ARCSEC_PER_MAS * ARCSEC_TO_RAD
    val eastRad = pmRaMasPerYear * perYearToRad * years
    val northRad = pmDecMasPerYear * perYearToRad * years
    val theta = Math.hypot(eastRad, northRad)
    if (theta < 1e-12) return precess(raDeg, decDeg, d)

    val ra = raDeg * RAD
    val dec = decDeg * RAD
    val sinRa = sin(ra)
    val cosRa = cos(ra)
    val sinDec = sin(dec)
    val cosDec = cos(dec)
    val here = Vec3(cosDec * cosRa, cosDec * sinRa, sinDec)
    val east = Vec3(-sinRa, cosRa, 0.0)
    val north = Vec3(-sinDec * cosRa, -sinDec * sinRa, cosDec)
    val direction = (east * (eastRad / theta) + north * (northRad / theta))
    val moved = here * cos(theta) + direction * sin(theta)

    val angles = equatorialDegrees(moved)
    return precess(angles[0], angles[1], d)
}

private const val ARCSEC_PER_MAS = 1.0 / 1000.0
