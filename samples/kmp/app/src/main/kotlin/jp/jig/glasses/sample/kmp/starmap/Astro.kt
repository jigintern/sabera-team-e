package jp.jig.glasses.sample.kmp.starmap

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * 座標変換パイプラインの ①〜⑤。
 * 仕様は docs/team-e/coordinate-system.md。Android に依存しないので JVM テストから直接叩ける。
 *
 * world 座標系は ENU（X=東 / Y=北 / Z=天頂）、方位角は北 = 0° の東回り、右手系。
 * ここを変えると全段の符号が狂うので、規約はこのファイルだけに置く。
 */

const val DEG = 180.0 / Math.PI
const val RAD = Math.PI / 180.0

/** 単位ベクトル。角度で持つと天頂で方位角が定義できず cos h でも割れないため、内部はこれで統一する */
data class Vec3(val x: Double, val y: Double, val z: Double) {
    infix fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    infix fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)

    fun normalized(): Vec3 {
        val n = sqrt(x * x + y * y + z * z)
        return if (n < 1e-12) Vec3(0.0, 1.0, 0.0) else Vec3(x / n, y / n, z / n)
    }
}

/** 方位角・高度[度] → ENU の単位ベクトル */
fun enu(azDeg: Double, altDeg: Double): Vec3 {
    val a = azDeg * RAD
    val h = altDeg * RAD
    val c = cos(h)
    return Vec3(c * sin(a), c * cos(a), sin(h))
}

/**
 * J2000.0 からの経過日数。
 * JD ≒ 2,460,000 を Double で持つと仮数部の上位が食われるので、差を直接組み立てる。
 */
fun daysFromJ2000(epochMillis: Long): Double = epochMillis / 86_400_000.0 - 10957.5

/** グリニッジ平均恒星時[時間]。係数が 24 でなく 24.0657 なのは恒星日が太陽日より約 4 分短いため */
fun gmstHours(d: Double): Double {
    val h = 18.697375 + 24.065709824279 * d
    return ((h % 24.0) + 24.0) % 24.0
}

/** 地方恒星時[度]。東経を正とする */
fun localSiderealDeg(d: Double, lonDeg: Double): Double =
    (((gmstHours(d) * 15.0 + lonDeg) % 360.0) + 360.0) % 360.0

/**
 * 歳差（Meeus 21.4 / IAU 1976）。J2000 から 2026 年で約 0.36° ＝ 満月の直径に近い量あるので入れる。
 * 章動 17″・固有運動・光行差 20″ はこの用途では無視できる。
 */
fun precess(raDeg: Double, decDeg: Double, d: Double): DoubleArray {
    val t = d / 36525.0
    val t2 = t * t
    val t3 = t2 * t
    val zeta = (2306.2181 * t + 0.30188 * t2 + 0.017998 * t3) / 3600.0 * RAD
    val z = (2306.2181 * t + 1.09468 * t2 + 0.018203 * t3) / 3600.0 * RAD
    val theta = (2004.3109 * t - 0.42665 * t2 - 0.041833 * t3) / 3600.0 * RAD

    val a = raDeg * RAD
    val dec = decDeg * RAD
    val cosDec = cos(dec)
    val aPlus = a + zeta
    val bigA = cosDec * sin(aPlus)
    val bigB = cos(theta) * cosDec * cos(aPlus) - sin(theta) * sin(dec)
    val bigC = sin(theta) * cosDec * cos(aPlus) + cos(theta) * sin(dec)
    val ra = (((atan2(bigA, bigB) + z) * DEG % 360.0) + 360.0) % 360.0
    return doubleArrayOf(ra, asin(bigC.coerceIn(-1.0, 1.0)) * DEG)
}

/** 赤道座標 → 地平座標。返すのは [方位角, 高度]（真北基準・東回り） */
fun toAltAz(raDeg: Double, decDeg: Double, lstDeg: Double, latDeg: Double): DoubleArray {
    val hourAngle = (lstDeg - raDeg) * RAD
    val dec = decDeg * RAD
    val lat = latDeg * RAD
    val alt = asin((sin(lat) * sin(dec) + cos(lat) * cos(dec) * cos(hourAngle)).coerceIn(-1.0, 1.0))
    val az = atan2(
        -cos(dec) * sin(hourAngle),
        sin(dec) * cos(lat) - cos(dec) * sin(lat) * cos(hourAngle),
    )
    return doubleArrayOf(((az * DEG) % 360.0 + 360.0) % 360.0, alt * DEG)
}

/**
 * 視線を中心とした接平面の基底。
 * up は天頂を視線に直交する成分だけ残したもので、これが「地平線を水平に固定する」の実体。
 * ロールに追従させるならここだけ差し替える。
 */
class Basis(azDeg: Double, altDeg: Double) {
    val forward: Vec3 = enu(azDeg, altDeg)
    val up: Vec3
    val right: Vec3

    init {
        val f = forward
        val u = Vec3(-f.z * f.x, -f.z * f.y, 1.0 - f.z * f.z)
        up = if (hypot(hypot(u.x, u.y), u.z) < 1e-9) Vec3(0.0, 1.0, 0.0) else u.normalized()
        right = f cross up
    }
}

/**
 * ステレオ投影 r = 2 tan(θ/2)。等角なので星座の形が崩れない。
 * 視野外は null。画面座標は左上原点で、y は下向き。
 */
fun project(v: Vec3, b: Basis, k: Double, w: Int, h: Int): DoubleArray? {
    val cosTheta = v dot b.forward
    if (cosTheta <= -0.3) return null
    val x = v dot b.right
    val y = v dot b.up
    val len = hypot(x, y)
    if (len < 1e-12) return doubleArrayOf(w / 2.0, h / 2.0)
    val r = 2.0 * tan(acos(cosTheta.coerceIn(-1.0, 1.0)) / 2.0)
    return doubleArrayOf(w / 2.0 + k * r * x / len, h / 2.0 - k * r * y / len)
}

/** 横 fovDeg が幅 w に収まるときの倍率。r = 2 tan(θ/2) の θ = fov/2 が w/2 に来る */
fun projectionScale(w: Int, fovDeg: Double): Double = (w / 2.0) / (2.0 * tan(fovDeg * RAD / 4.0))

/** 角度の差を -180..180 に畳む。ヨーが ±180 で折り返すので、差分を取るときは必ず通す */
fun normalizeDeg(deg: Double): Double {
    var v = deg % 360.0
    if (v > 180.0) v -= 360.0
    if (v < -180.0) v += 360.0
    return if (abs(v) < 1e-12) 0.0 else v
}
