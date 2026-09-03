package jp.jig.glasses.sample.kmp.sky

import jp.jig.glasses.sample.kmp.support.DAY_MILLIS
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * 座標変換パイプラインの ①〜⑤。
 * 仕様は docs/team-e/20_coordinate-system.md。Android に依存しないので JVM テストから直接叩ける。
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

    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(scale: Double) = Vec3(x * scale, y * scale, z * scale)
    operator fun div(scale: Double) = Vec3(x / scale, y / scale, z / scale)
    operator fun unaryMinus() = Vec3(-x, -y, -z)

    fun length(): Double = sqrt(x * x + y * y + z * z)

    fun normalized(): Vec3 {
        val n = length()
        return if (n < 1e-12) Vec3(0.0, 1.0, 0.0) else Vec3(x / n, y / n, z / n)
    }

    /** [axis] 方向の成分を落とした残り。長さは残す（0 に近いかで有効性を見たいので） */
    fun dropAlong(axis: Vec3): Vec3 = this - axis * (this dot axis)
}

/**
 * グラスのヨーを方位[度]へ直す。**符号の規約はここだけに置く。**
 *
 * **ヨーは左を向くと増え、方位は右を向くと増える。** 実機で測った 3 つから決まる:
 *
 * | 実測 | 出どころ |
 * |---|---|
 * | 上 = 加速度の +X 軸 | 加速度軸の自動判定 |
 * | ジャイロは右手系 | 同上（基底と回転の向きを突き合わせ） |
 * | `gyroXDps` とヨーは同符号 | 90° を 7 回まわして確認 |
 *
 * 右手系で回転軸が上を向いていれば、正の角速度は**上から見て反時計回り＝左**。
 * ヨーがそれと同符号なので、**ヨーは方位と逆に回る**。
 *
 * **2026-08-21 に実機で確認した。** 誘導が「左へ 30°」と言う状態から左を向くと
 * 30 → 40 → 50 と増え、目標が逃げていった。足し算のままだと、
 * 首を振った分だけ 2 倍の速さでずれる（合わせた瞬間だけ正しい）。
 */
fun azimuthFromYaw(yawDeg: Double, headingOffsetDeg: Double): Double =
    (normalizeDeg(headingOffsetDeg - yawDeg) + 360.0) % 360.0

/** [azimuthFromYaw] の逆。「この方位を向いていたときヨーがこうだった」からオフセットを作る */
fun headingOffsetFor(azimuthDeg: Double, yawDeg: Double): Double = normalizeDeg(azimuthDeg + yawDeg)

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
fun daysFromJ2000(epochMillis: Long): Double = epochMillis / DAY_MILLIS.toDouble() - 10957.5

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
 *
 * **±3 世紀を超えたら長期歳差へ渡す**（[longTermPrecess]）。ここの 3 次式は
 * 1 万年（T = 100 世紀）だと `theta` の 3 次項だけで −41,833″ ＝ −11.6° になり、
 * 星座がどこにあるかすら合わなくなる（#45）。
 */
fun precess(raDeg: Double, decDeg: Double, d: Double): DoubleArray {
    if (abs(d) > LONG_TERM_PRECESSION_DAYS) return longTermPrecess(raDeg, decDeg, d)
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

/**
 * 指定日の赤道座標を J2000.0 へ戻す。IAU 星座境界が定義された B1875.0 へ変換する前段で使う。
 * [precess] が作る回転行列の転置を掛けるため、同じ近似内では正確に逆変換できる。
 */
fun inversePrecess(raDeg: Double, decDeg: Double, d: Double): DoubleArray {
    if (abs(d) > LONG_TERM_PRECESSION_DAYS) return longTermInversePrecess(raDeg, decDeg, d)
    val basisX = precess(0.0, 0.0, d).let { equatorialVector(it[0], it[1]) }
    val basisY = precess(90.0, 0.0, d).let { equatorialVector(it[0], it[1]) }
    val basisZ = precess(0.0, 90.0, d).let { equatorialVector(it[0], it[1]) }
    val current = equatorialVector(raDeg, decDeg)
    return equatorialAngles(
        Vec3(current dot basisX, current dot basisY, current dot basisZ).normalized(),
    )
}

private fun equatorialVector(raDeg: Double, decDeg: Double): Vec3 {
    val ra = raDeg * RAD
    val dec = decDeg * RAD
    return Vec3(cos(dec) * cos(ra), cos(dec) * sin(ra), sin(dec))
}

private fun equatorialAngles(v: Vec3): DoubleArray = doubleArrayOf(
    ((atan2(v.y, v.x) * DEG) % 360.0 + 360.0) % 360.0,
    asin(v.z.coerceIn(-1.0, 1.0)) * DEG,
)

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
 * 標準大気での Sæmundsson の近似式（**真高度 → 見かけの高度**の向き）。
 * 地平線では約 0.48° 持ち上がり、10°以上では 0.1°未満になる。
 * 気温・気圧が無いので低高度の完全補正ではないが、補正しない場合の系統誤差を減らす。
 *
 * **Bennett の式と取り違えない。** あちらは見かけ → 真の向きで、係数も 7.31 / 4.4 と違う。
 * 逆向きが要るときは [geometricAltitudeDeg]（この式を反復で戻す）を使う。
 */
fun apparentAltitudeDeg(geometricAltitudeDeg: Double): Double {
    if (geometricAltitudeDeg < -1.0 || geometricAltitudeDeg >= 90.0) return geometricAltitudeDeg
    val correction = 1.02 / tan(
        (geometricAltitudeDeg + 10.3 / (geometricAltitudeDeg + 5.11)) * RAD,
    ) / 60.0
    return (geometricAltitudeDeg + correction).coerceAtMost(90.0)
}

/** 見かけの高度を、星表計算で使う幾何学的高度へ反復で戻す。 */
fun geometricAltitudeDeg(apparentDeg: Double): Double {
    var geometric = apparentDeg
    repeat(8) { geometric -= apparentAltitudeDeg(geometric) - apparentDeg }
    return geometric
}

/** 赤道座標 → 大気差を含む見かけの地平座標。 */
fun toApparentAltAz(raDeg: Double, decDeg: Double, lstDeg: Double, latDeg: Double): DoubleArray {
    val geometric = toAltAz(raDeg, decDeg, lstDeg, latDeg)
    geometric[1] = apparentAltitudeDeg(geometric[1])
    return geometric
}

/** 地平座標 → その日の赤道座標。星座境界の照合用に [toAltAz] を逆変換する。 */
fun toRaDec(azDeg: Double, altDeg: Double, lstDeg: Double, latDeg: Double): DoubleArray {
    val horizontal = enu(azDeg, altDeg)
    val lat = latDeg * RAD
    val sinDec = horizontal.y * cos(lat) + horizontal.z * sin(lat)
    val dec = asin(sinDec.coerceIn(-1.0, 1.0))
    val cosDecCosHour = -horizontal.y * sin(lat) + horizontal.z * cos(lat)
    val hourAngle = atan2(-horizontal.x, cosDecCosHour)
    val ra = ((lstDeg - hourAngle * DEG) % 360.0 + 360.0) % 360.0
    return doubleArrayOf(ra, dec * DEG)
}

/** 指定日の赤道座標を Roman (1987) の境界表が使う B1875.0 へ変換する。 */
fun precessDateToB1875(raDeg: Double, decDeg: Double, d: Double): DoubleArray {
    val j2000 = inversePrecess(raDeg, decDeg, d)
    return precess(j2000[0], j2000[1], B1875_DAYS_FROM_J2000)
}

/**
 * 視線を中心とした接平面の基底。
 * up は天頂を視線に直交する成分だけ残したもので、これが「地平線を水平に固定する」の実体。
 *
 * **[rollDeg] で首の傾きに追従する。** パネルは頭に固定されているので、首を傾けると
 * 本物の地平線は画面の中で回る。絵を水平のまま出すと**地平線だけが傾いて残る**
 * （実機で確認・2026-08-22）。6DoF にロールは無いので、加速度から起こして渡す。
 */
class Basis(azDeg: Double, altDeg: Double, rollDeg: Double = 0.0) {
    val forward: Vec3 = enu(azDeg, altDeg)
    val up: Vec3
    val right: Vec3

    init {
        val f = forward
        val u = Vec3(-f.z * f.x, -f.z * f.y, 1.0 - f.z * f.z)
        val level = if (hypot(hypot(u.x, u.y), u.z) < 1e-9) Vec3(0.0, 1.0, 0.0) else u.normalized()
        val levelRight = f cross level
        if (rollDeg == 0.0) {
            up = level
            right = levelRight
        } else {
            // 視線まわりに基底ごと回す。**絵を回すのではなく見ている枠を回す**ので、
            // 星も星座線も地平線も同じだけ回り、互いの位置関係は崩れない
            val c = cos(rollDeg * RAD)
            val s = sin(rollDeg * RAD)
            up = Vec3(
                c * level.x + s * levelRight.x,
                c * level.y + s * levelRight.y,
                c * level.z + s * levelRight.z,
            ).normalized()
            right = f cross up
        }
    }
}

/**
 * 加速度から首の傾き[度]を出す。**右耳が下がる向きを正**にする。
 *
 * 6DoF はピッチとヨーしか返さない（SDK 0.6.0）ので、重力そのものから起こす。
 *
 * **機体の軸は実測してある**（docs/team-e/70_measurements.md・静止 865 件・ピッチ幅 76°）。
 * **上 = +X・前 = −Y**、したがって **右 = 前 × 上 = +Z**。
 * 静止中の加速度はこの 3 軸で
 *
 * ```
 * a = 1000 ( sinθ·前 + cosθ·cosφ·上 − cosθ·sinφ·右 )      θ=ピッチ φ=ロール
 * ```
 *
 * になるので、`aX = 1000·cosθ·cosφ` と `aZ = −1000·cosθ·sinφ` から
 * **ピッチに影響されずに φ だけ**が出る（`tanφ = −aZ / aX`）。
 *
 * **「X 軸が右」と仮定してはいけない。** そう書いていたときは水平に構えただけで
 * `atan2(1000, 0) = 90°` を返し、**返る値は実際には (90° − ピッチ) だった**。
 * 星図が枠ごと 90° 回り、見上げるほど回転量が変わるので、**目標が画面の中を動いて逃げた**。
 * 左右の傾きも区別できていなかった（ロール +20° と −20° がどちらも 70°）。
 *
 * **0 を返すのは「真上」ではなく、天頂から 11.54° の帯。** ロールが定義できなくなるのは
 * cosθ → 0 だが、門は `hypot(aX, aZ) = 1000·|cosθ| < 200`、つまり **cosθ < 0.2
 * ＝ ピッチ > 78.46°**（半球の立体角の約 2%）で閉じる。ロールに依存しない純粋なピッチ門。
 *
 * **200 の根拠は台帳（70_measurements.md）に無い**（他の定数を全部置くこの repo では異例）。
 * 天頂付近は重力の直交成分が 173mG しかないので、**首振り中に乗る線形加速度の暴れ止めを
 * 兼ねている可能性がある。測るまで狭めない**（#179。測る項目は台帳の「まだ測っていない」）。
 *
 * 動いている間は重力以外の加速度が乗るので、**首が止まっているときだけ使う**前提で書いてある。
 * 実際の呼び出しは 6DoF の全サンプルに対して走り、`ROLL_SMOOTHING` で平滑した値を使っている。
 */
fun rollFromAccel(xMilliG: Int, yMilliG: Int, zMilliG: Int): Double {
    val up = xMilliG.toDouble()
    val right = zMilliG.toDouble()
    // 真上を向くと上も右も 0 に落ちる。**ここで Y（前）を混ぜない**（混ぜるとピッチが漏れる）
    if (hypot(up, right) < 200.0) return 0.0
    return atan2(ROLL_SIGN * -right, ROLL_SIGN * up) * DEG
}

/**
 * ロールの向き。**実機で 180° 回っていたら符号を変えるだけ**で直る。
 *
 * 加速度計が重力を「反力」で返すか「加速度」で返すかは SDK に書かれていない。
 * 反力なら水平で `aX = +1000`、加速度なら `−1000` になり、**ロールが 180° ずれる**
 * （71_yaw-drift.md に「実機のログで −177° と出た」の記録がある）。
 * **上と右を同時に反転させる**ので、片方だけに掛けてはいけない。
 */
private const val ROLL_SIGN = 1.0

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

/** 2 方向のなす角[度]。視野に入っているかを度で判定するのに使う */
fun angleBetweenDeg(a: Vec3, b: Vec3): Double = acos((a dot b).coerceIn(-1.0, 1.0)) * DEG

/**
 * 横画角 [fovDeg]・縦横比 [panelAspect]（高さ ÷ 幅）のパネルに、この向きが入るか。
 *
 * **円で切らない。** 星図は横長で、544×340・画角 35° なら**横は ±17.5° あるのに縦は ±11.0°**、
 * 隅は 20.6° まで届く。半径 fov/2 の円で切ると、**上下は絵に無いものを拾い、隅は絵にあるものを
 * 落とす**。AI へ渡す根拠がそこで絵とずれる（#37）。
 *
 * 判定は [project] の枠内判定そのもので、画素に直す前の長さで測るだけ
 * （画面 x が 0..w に入る ⇔ |r·x/len| ≤ (w/2)/k = 2 tan(fov/4)）。
 * **[projectionScale] と同じ式を使うので、星図の幅が 544 でも 528 でも答えは変わらない。**
 */
fun withinPanel(v: Vec3, b: Basis, fovDeg: Double, panelAspect: Double): Boolean {
    val cosTheta = v dot b.forward
    if (cosTheta <= 0.0) return false
    val x = v dot b.right
    val y = v dot b.up
    val len = hypot(x, y)
    val halfWidth = 2.0 * tan(fovDeg * RAD / 4.0)
    if (len < 1e-12) return true
    val r = 2.0 * tan(acos(cosTheta.coerceIn(-1.0, 1.0)) / 2.0)
    return abs(r * x / len) <= halfWidth && abs(r * y / len) <= halfWidth * panelAspect
}

/** 横 fovDeg が幅 w に収まるときの倍率。r = 2 tan(θ/2) の θ = fov/2 が w/2 に来る */
fun projectionScale(w: Int, fovDeg: Double): Double = (w / 2.0) / (2.0 * tan(fovDeg * RAD / 4.0))

/** 高度を ±90° に収める。オフセットの足し込みで天頂・天底を越えたときの保険 */
fun clampAltDeg(deg: Double): Double = deg.coerceIn(-90.0, 90.0)

/** 角度の差を -180..180 に畳む。ヨーが ±180 で折り返すので、差分を取るときは必ず通す */
fun normalizeDeg(deg: Double): Double {
    var v = deg % 360.0
    if (v > 180.0) v -= 360.0
    if (v < -180.0) v += 360.0
    return if (abs(v) < 1e-12) 0.0 else v
}

private const val B1875_DAYS_FROM_J2000 = -45_655.74145
