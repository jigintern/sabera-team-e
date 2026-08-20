package jp.jig.glasses.sample.kmp.satellite

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * SGP4（近地球）の伝播計算。
 *
 * Vallado の参照実装（CelesTrak が配布している `SGP4.cpp`）を Kotlin に写したもの。
 * **変数名は参照実装のまま**にしてある。読みやすさより、元と 1 行ずつ突き合わせられることを取った。
 * 検算は `Sgp4Test` が参照実装の出力と突き合わせている。
 *
 * **周期 225 分以上（みちびき・ひまわりなど）は深宇宙の分岐に入るが、まだ書いていない。**
 * `deepSpace` が true のものは伝播できない。
 */
class Sgp4(val tle: Tle) {

    /** 深宇宙（SDP4）の対象か。周期 225 分以上 */
    val deepSpace: Boolean

    // --- near earth ---
    private var isimp = 0
    private var aycof = 0.0
    private var con41 = 0.0
    private var cc1 = 0.0
    private var cc4 = 0.0
    private var cc5 = 0.0
    private var d2 = 0.0
    private var d3 = 0.0
    private var d4 = 0.0
    private var delmo = 0.0
    private var eta = 0.0
    private var argpdot = 0.0
    private var omgcof = 0.0
    private var sinmao = 0.0
    private var t2cof = 0.0
    private var t3cof = 0.0
    private var t4cof = 0.0
    private var t5cof = 0.0
    private var x1mth2 = 0.0
    private var x7thm1 = 0.0
    private var mdot = 0.0
    private var nodedot = 0.0
    private var xlcof = 0.0
    private var xmcof = 0.0
    private var nodecf = 0.0

    private var noUnkozai = 0.0
    private var gsto = 0.0

    init {
        // initl 相当
        val ecco = tle.ecco
        val cosio = cos(tle.inclo)
        val cosio2 = cosio * cosio
        val eccsq = ecco * ecco
        val omeosq = 1.0 - eccsq
        val rteosq = sqrt(omeosq)
        val ak = (XKE / tle.noKozai).pow(X2O3)
        val d1 = 0.75 * J2 * (3.0 * cosio2 - 1.0) / (rteosq * omeosq)
        var del = d1 / (ak * ak)
        val adel = ak * (1.0 - del * del - del * (1.0 / 3.0 + 134.0 * del * del / 81.0))
        del = d1 / (adel * adel)
        noUnkozai = tle.noKozai / (1.0 + del)
        val ao = (XKE / noUnkozai).pow(X2O3)
        val sinio = sin(tle.inclo)
        val po = ao * omeosq
        val con42 = 1.0 - 5.0 * cosio2
        con41 = -con42 - cosio2 - cosio2
        val posq = po * po
        val rp = ao * (1.0 - ecco)
        gsto = gstime(tle.epochDaysSince1950 + 2433281.5)

        deepSpace = (2.0 * PI / noUnkozai) >= 225.0

        // sgp4init 相当（近地球ぶんだけ）
        val ss = 78.0 / RADIUS_EARTH_KM + 1.0
        val qzms2ttemp = (120.0 - 78.0) / RADIUS_EARTH_KM
        val qzms2t = qzms2ttemp * qzms2ttemp * qzms2ttemp * qzms2ttemp

        if (omeosq >= 0.0 || noUnkozai >= 0.0) {
            isimp = if (rp < (220.0 / RADIUS_EARTH_KM + 1.0)) 1 else 0
            var sfour = ss
            var qzms24 = qzms2t
            val perige = (rp - 1.0) * RADIUS_EARTH_KM
            if (perige < 156.0) {
                sfour = if (perige < 98.0) 20.0 else perige - 78.0
                val qzms24temp = (120.0 - sfour) / RADIUS_EARTH_KM
                qzms24 = qzms24temp * qzms24temp * qzms24temp * qzms24temp
                sfour = sfour / RADIUS_EARTH_KM + 1.0
            }
            val pinvsq = 1.0 / posq
            val tsi = 1.0 / (ao - sfour)
            eta = ao * ecco * tsi
            val etasq = eta * eta
            val eeta = ecco * eta
            val psisq = abs(1.0 - etasq)
            val coef = qzms24 * tsi.pow(4.0)
            val coef1 = coef / psisq.pow(3.5)
            val cc2 = coef1 * noUnkozai * (
                ao * (1.0 + 1.5 * etasq + eeta * (4.0 + etasq)) +
                    0.375 * J2 * tsi / psisq * con41 * (8.0 + 3.0 * etasq * (8.0 + etasq))
                )
            cc1 = tle.bstar * cc2
            var cc3 = 0.0
            if (ecco > 1.0e-4) cc3 = -2.0 * coef * tsi * J3OJ2 * noUnkozai * sinio / ecco
            x1mth2 = 1.0 - cosio2
            cc4 = 2.0 * noUnkozai * coef1 * ao * omeosq * (
                eta * (2.0 + 0.5 * etasq) + ecco * (0.5 + 2.0 * etasq) -
                    J2 * tsi / (ao * psisq) * (
                        -3.0 * con41 * (1.0 - 2.0 * eeta + etasq * (1.5 - 0.5 * eeta)) +
                            0.75 * x1mth2 * (2.0 * etasq - eeta * (1.0 + etasq)) * cos(2.0 * tle.argpo)
                        )
                )
            cc5 = 2.0 * coef1 * ao * omeosq * (1.0 + 2.75 * (etasq + eeta) + eeta * etasq)
            val cosio4 = cosio2 * cosio2
            val temp1 = 1.5 * J2 * pinvsq * noUnkozai
            val temp2 = 0.5 * temp1 * J2 * pinvsq
            val temp3 = -0.46875 * J4 * pinvsq * pinvsq * noUnkozai
            mdot = noUnkozai + 0.5 * temp1 * rteosq * con41 +
                0.0625 * temp2 * rteosq * (13.0 - 78.0 * cosio2 + 137.0 * cosio4)
            argpdot = -0.5 * temp1 * con42 +
                0.0625 * temp2 * (7.0 - 114.0 * cosio2 + 395.0 * cosio4) +
                temp3 * (3.0 - 36.0 * cosio2 + 49.0 * cosio4)
            val xhdot1 = -temp1 * cosio
            nodedot = xhdot1 + (0.5 * temp2 * (4.0 - 19.0 * cosio2) + 2.0 * temp3 * (3.0 - 7.0 * cosio2)) * cosio
            omgcof = tle.bstar * cc3 * cos(tle.argpo)
            xmcof = if (ecco > 1.0e-4) -X2O3 * coef * tle.bstar / eeta else 0.0
            nodecf = 3.5 * omeosq * xhdot1 * cc1
            t2cof = 1.5 * cc1
            xlcof = if (abs(cosio + 1.0) > 1.5e-12) {
                -0.25 * J3OJ2 * sinio * (3.0 + 5.0 * cosio) / (1.0 + cosio)
            } else {
                -0.25 * J3OJ2 * sinio * (3.0 + 5.0 * cosio) / TEMP4
            }
            aycof = -0.5 * J3OJ2 * sinio
            val delmotemp = 1.0 + eta * cos(tle.mo)
            delmo = delmotemp * delmotemp * delmotemp
            sinmao = sin(tle.mo)
            x7thm1 = 7.0 * cosio2 - 1.0

            // 深宇宙はここで dscom / dpper / dsinit を呼ぶ。まだ書いていない
            if (deepSpace) isimp = 1

            if (isimp != 1) {
                val cc1sq = cc1 * cc1
                d2 = 4.0 * ao * tsi * cc1sq
                val temp = d2 * tsi * cc1 / 3.0
                d3 = (17.0 * ao + sfour) * temp
                d4 = 0.5 * temp * ao * tsi * (221.0 * ao + 31.0 * sfour) * cc1
                t3cof = d2 + 2.0 * cc1sq
                t4cof = 0.25 * (3.0 * d3 + cc1 * (12.0 * d2 + 10.0 * cc1sq))
                t5cof = 0.2 * (3.0 * d4 + 12.0 * cc1 * d3 + 6.0 * d2 * d2 + 15.0 * cc1sq * (2.0 * d2 + cc1sq))
            }
        }
    }

    /**
     * 元期から `tsince` 分後の位置と速度（TEME 座標、km と km/秒）。
     *
     * 伝播できないときは null。**軌道が壊れている TLE は珍しくない**ので、
     * 呼ぶ側で「出せなかった衛星は飛ばす」作りにする。
     */
    fun propagate(tsince: Double): TemeState? {
        if (deepSpace) return null

        val twopi = 2.0 * PI
        val vkmpersec = RADIUS_EARTH_KM * XKE / 60.0

        val xmdf = tle.mo + mdot * tsince
        val argpdf = tle.argpo + argpdot * tsince
        val nodedf = tle.nodeo + nodedot * tsince
        var argpm = argpdf
        var mm = xmdf
        val t2 = tsince * tsince
        var nodem = nodedf + nodecf * t2
        var tempa = 1.0 - cc1 * tsince
        var tempe = tle.bstar * cc4 * tsince
        var templ = t2cof * t2

        if (isimp != 1) {
            val delomg = omgcof * tsince
            val delmtemp = 1.0 + eta * cos(xmdf)
            val delm = xmcof * (delmtemp * delmtemp * delmtemp - delmo)
            val temp = delomg + delm
            mm = xmdf + temp
            argpm = argpdf - temp
            val t3 = t2 * tsince
            val t4 = t3 * tsince
            tempa = tempa - d2 * t2 - d3 * t3 - d4 * t4
            tempe += tle.bstar * cc5 * (sin(mm) - sinmao)
            templ = templ + t3cof * t3 + t4 * (t4cof + tsince * t5cof)
        }

        var nm = noUnkozai
        var em = tle.ecco
        val inclm = tle.inclo
        if (nm <= 0.0) return null

        val am = (XKE / nm).pow(X2O3) * tempa * tempa
        nm = XKE / am.pow(1.5)
        em -= tempe
        if (em >= 1.0 || em < -0.001) return null
        if (em < 1.0e-6) em = 1.0e-6
        mm += noUnkozai * templ
        var xlm = mm + argpm + nodem

        nodem %= twopi
        argpm %= twopi
        xlm %= twopi
        mm = (xlm - argpm - nodem) % twopi

        val sinim = sin(inclm)
        val cosim = cos(inclm)
        val ep = em
        val xincp = inclm
        val argpp = argpm
        val nodep = nodem
        val mp = mm
        val sinip = sinim
        val cosip = cosim

        val axnl = ep * cos(argpp)
        var temp = 1.0 / (am * (1.0 - ep * ep))
        val aynl = ep * sin(argpp) + temp * aycof
        val xl = mp + argpp + nodep + temp * xlcof * axnl

        // ケプラー方程式を解く
        val u = (xl - nodep) % twopi
        var eo1 = u
        var tem5 = 9999.9
        var ktr = 1
        var sineo1 = 0.0
        var coseo1 = 0.0
        while (abs(tem5) >= 1.0e-12 && ktr <= 10) {
            sineo1 = sin(eo1)
            coseo1 = cos(eo1)
            tem5 = 1.0 - coseo1 * axnl - sineo1 * aynl
            tem5 = (u - aynl * coseo1 + axnl * sineo1 - eo1) / tem5
            if (abs(tem5) >= 0.95) tem5 = if (tem5 > 0.0) 0.95 else -0.95
            eo1 += tem5
            ktr++
        }

        val ecose = axnl * coseo1 + aynl * sineo1
        val esine = axnl * sineo1 - aynl * coseo1
        val el2 = axnl * axnl + aynl * aynl
        val pl = am * (1.0 - el2)
        if (pl < 0.0) return null

        val rl = am * (1.0 - ecose)
        val rdotl = sqrt(am) * esine / rl
        val rvdotl = sqrt(pl) / rl
        val betal = sqrt(1.0 - el2)
        temp = esine / (1.0 + betal)
        val sinu = am / rl * (sineo1 - aynl - axnl * temp)
        val cosu = am / rl * (coseo1 - axnl + aynl * temp)
        var su = atan2(sinu, cosu)
        val sin2u = (cosu + cosu) * sinu
        val cos2u = 1.0 - 2.0 * sinu * sinu
        temp = 1.0 / pl
        val temp1 = 0.5 * J2 * temp
        val temp2 = temp1 * temp

        val mrt = rl * (1.0 - 1.5 * temp2 * betal * con41) + 0.5 * temp1 * x1mth2 * cos2u
        su -= 0.25 * temp2 * x7thm1 * sin2u
        val xnode = nodep + 1.5 * temp2 * cosip * sin2u
        val xinc = xincp + 1.5 * temp2 * cosip * sinip * cos2u
        val mvt = rdotl - nm * temp1 * x1mth2 * sin2u / XKE
        val rvdot = rvdotl + nm * temp1 * (x1mth2 * cos2u + 1.5 * con41) / XKE

        val sinsu = sin(su)
        val cossu = cos(su)
        val snod = sin(xnode)
        val cnod = cos(xnode)
        val sini = sin(xinc)
        val cosi = cos(xinc)
        val xmx = -snod * cosi
        val xmy = cnod * cosi
        val ux = xmx * sinsu + cnod * cossu
        val uy = xmy * sinsu + snod * cossu
        val uz = sini * sinsu
        val vx = xmx * cossu - cnod * sinsu
        val vy = xmy * cossu - snod * sinsu
        val vz = sini * cossu

        if (mrt < 1.0) return null // 地面より下 ＝ 落ちた

        return TemeState(
            x = mrt * ux * RADIUS_EARTH_KM,
            y = mrt * uy * RADIUS_EARTH_KM,
            z = mrt * uz * RADIUS_EARTH_KM,
            vx = (mvt * ux + rvdot * vx) * vkmpersec,
            vy = (mvt * uy + rvdot * vy) * vkmpersec,
            vz = (mvt * uz + rvdot * vz) * vkmpersec,
        )
    }

    /** `epochMillis` の時点の位置と速度 */
    fun at(epochMillis: Long): TemeState? = propagate(tle.minutesSinceEpoch(epochMillis))

    companion object {
        // wgs72。TLE は wgs72 で作られているので、ここを wgs84 にしてはいけない
        const val MU = 398600.8
        const val RADIUS_EARTH_KM = 6378.135
        val XKE = 60.0 / sqrt(RADIUS_EARTH_KM * RADIUS_EARTH_KM * RADIUS_EARTH_KM / MU)
        const val J2 = 0.001082616
        const val J3 = -0.00000253881
        const val J4 = -0.00000165597
        const val J3OJ2 = J3 / J2
        private const val X2O3 = 2.0 / 3.0
        private const val TEMP4 = 1.5e-12

        /** グリニッジ平均恒星時[rad]。`Astro.kt` のものとは基準が違うので混ぜない */
        fun gstime(jdut1: Double): Double {
            val tut1 = (jdut1 - 2451545.0) / 36525.0
            var temp = -6.2e-6 * tut1 * tut1 * tut1 + 0.093104 * tut1 * tut1 +
                (876600.0 * 3600 + 8640184.812866) * tut1 + 67310.54841
            temp = (temp * (PI / 180.0) / 240.0) % (2.0 * PI)
            if (temp < 0.0) temp += 2.0 * PI
            return temp
        }
    }
}

/** TEME 座標での位置[km]と速度[km/秒] */
class TemeState(
    val x: Double,
    val y: Double,
    val z: Double,
    val vx: Double,
    val vy: Double,
    val vz: Double,
)
