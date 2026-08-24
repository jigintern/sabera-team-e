package jp.jig.glasses.sample.kmp.satellite

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * SDP4（深宇宙）の係数と補正。
 *
 * **周期 225 分以上の衛星はこちらに落ちる。** みちびき（準天頂）とひまわり（静止）が該当する。
 * 月と太陽の引力による長周期の揺れと、地球の形による共鳴を扱う。
 *
 * Vallado の参照実装の `dscom` / `dpper` / `dsinit` / `dspace` をそのまま写したもの。
 * **変数名は参照実装のまま。** 意味の分かる名前に直すと、元と突き合わせられなくなる。
 */
internal class Sdp4DeepSpace {

    // dscom が作る係数
    var e3 = 0.0
    var ee2 = 0.0
    var peo = 0.0
    var pgho = 0.0
    var pho = 0.0
    var pinco = 0.0
    var plo = 0.0
    var se2 = 0.0
    var se3 = 0.0
    var sgh2 = 0.0
    var sgh3 = 0.0
    var sgh4 = 0.0
    var sh2 = 0.0
    var sh3 = 0.0
    var si2 = 0.0
    var si3 = 0.0
    var sl2 = 0.0
    var sl3 = 0.0
    var sl4 = 0.0
    var xgh2 = 0.0
    var xgh3 = 0.0
    var xgh4 = 0.0
    var xh2 = 0.0
    var xh3 = 0.0
    var xi2 = 0.0
    var xi3 = 0.0
    var xl2 = 0.0
    var xl3 = 0.0
    var xl4 = 0.0
    var zmol = 0.0
    var zmos = 0.0

    // dsinit が作る係数
    var irez = 0
    var d2201 = 0.0
    var d2211 = 0.0
    var d3210 = 0.0
    var d3222 = 0.0
    var d4410 = 0.0
    var d4422 = 0.0
    var d5220 = 0.0
    var d5232 = 0.0
    var d5421 = 0.0
    var d5433 = 0.0
    var dedt = 0.0
    var del1 = 0.0
    var del2 = 0.0
    var del3 = 0.0
    var didt = 0.0
    var dmdt = 0.0
    var dnodt = 0.0
    var domdt = 0.0
    var xfact = 0.0
    var xlamo = 0.0

    /** 共鳴の積分はここまで進んだ、という状態。**呼ぶたびに進む**ので使い回しに注意 */
    var atime = 0.0
    var xli = 0.0
    var xni = 0.0

    // dscom の途中結果。dsinit が使う
    private var s1 = 0.0
    private var s2 = 0.0
    private var s3 = 0.0
    private var s4 = 0.0
    private var s5 = 0.0
    private var ss1 = 0.0
    private var ss2 = 0.0
    private var ss3 = 0.0
    private var ss4 = 0.0
    private var ss5 = 0.0
    private var ss6 = 0.0
    private var ss7 = 0.0
    private var sz1 = 0.0
    private var sz3 = 0.0
    private var sz11 = 0.0
    private var sz13 = 0.0
    private var sz21 = 0.0
    private var sz23 = 0.0
    private var sz31 = 0.0
    private var sz33 = 0.0
    private var z1 = 0.0
    private var z3 = 0.0
    private var z11 = 0.0
    private var z13 = 0.0
    private var z21 = 0.0
    private var z23 = 0.0
    private var z31 = 0.0
    private var z33 = 0.0
    private var sinim = 0.0
    private var cosim = 0.0
    private var emsq = 0.0

    /** 月と太陽の位置から長周期項の係数を作る（`dscom`） */
    fun initCoefficients(
        epoch: Double,
        ep: Double,
        argpp: Double,
        tc: Double,
        inclp: Double,
        nodep: Double,
        np: Double,
    ) {
        val zes = 0.01675
        val zel = 0.05490
        val c1ss = 2.9864797e-6
        val c1l = 4.7968065e-7
        val zsinis = 0.39785416
        val zcosis = 0.91744867
        val zcosgs = 0.1945905
        val zsings = -0.98088458
        val twopi = 2.0 * PI

        val nm = np
        val em = ep
        val snodm = sin(nodep)
        val cnodm = cos(nodep)
        val sinomm = sin(argpp)
        val cosomm = cos(argpp)
        sinim = sin(inclp)
        cosim = cos(inclp)
        emsq = em * em
        val betasq = 1.0 - emsq
        val rtemsq = sqrt(betasq)

        peo = 0.0
        pinco = 0.0
        plo = 0.0
        pgho = 0.0
        pho = 0.0
        val day = epoch + 18261.5 + tc / 1440.0
        val xnodce = (4.5236020 - 9.2422029e-4 * day) % twopi
        val stem = sin(xnodce)
        val ctem = cos(xnodce)
        val zcosil = 0.91375164 - 0.03568096 * ctem
        val zsinil = sqrt(1.0 - zcosil * zcosil)
        val zsinhl = 0.089683511 * stem / zsinil
        val zcoshl = sqrt(1.0 - zsinhl * zsinhl)
        val gam = 5.8351514 + 0.0019443680 * day
        var zx = 0.39785416 * stem / zsinil
        val zy = zcoshl * ctem + 0.91744867 * zsinhl * stem
        zx = atan2(zx, zy)
        zx = gam + zx - xnodce
        val zcosgl = cos(zx)
        val zsingl = sin(zx)

        var zcosg = zcosgs
        var zsing = zsings
        var zcosi = zcosis
        var zsini = zsinis
        var zcosh = cnodm
        var zsinh = snodm
        var cc = c1ss
        val xnoi = 1.0 / nm

        var s6 = 0.0
        var s7 = 0.0
        var z2 = 0.0
        var z12 = 0.0
        var z22 = 0.0
        var z32 = 0.0
        var sz2 = 0.0
        var sz12 = 0.0
        var sz22 = 0.0
        var sz32 = 0.0

        for (lsflg in 1..2) {
            val a1 = zcosg * zcosh + zsing * zcosi * zsinh
            val a3 = -zsing * zcosh + zcosg * zcosi * zsinh
            val a7 = -zcosg * zsinh + zsing * zcosi * zcosh
            val a8 = zsing * zsini
            val a9 = zsing * zsinh + zcosg * zcosi * zcosh
            val a10 = zcosg * zsini
            val a2 = cosim * a7 + sinim * a8
            val a4 = cosim * a9 + sinim * a10
            val a5 = -sinim * a7 + cosim * a8
            val a6 = -sinim * a9 + cosim * a10

            val x1 = a1 * cosomm + a2 * sinomm
            val x2 = a3 * cosomm + a4 * sinomm
            val x3 = -a1 * sinomm + a2 * cosomm
            val x4 = -a3 * sinomm + a4 * cosomm
            val x5 = a5 * sinomm
            val x6 = a6 * sinomm
            val x7 = a5 * cosomm
            val x8 = a6 * cosomm

            z31 = 12.0 * x1 * x1 - 3.0 * x3 * x3
            z32 = 24.0 * x1 * x2 - 6.0 * x3 * x4
            z33 = 12.0 * x2 * x2 - 3.0 * x4 * x4
            z1 = 3.0 * (a1 * a1 + a2 * a2) + z31 * emsq
            z2 = 6.0 * (a1 * a3 + a2 * a4) + z32 * emsq
            z3 = 3.0 * (a3 * a3 + a4 * a4) + z33 * emsq
            z11 = -6.0 * a1 * a5 + emsq * (-24.0 * x1 * x7 - 6.0 * x3 * x5)
            z12 = -6.0 * (a1 * a6 + a3 * a5) + emsq *
                (-24.0 * (x2 * x7 + x1 * x8) - 6.0 * (x3 * x6 + x4 * x5))
            z13 = -6.0 * a3 * a6 + emsq * (-24.0 * x2 * x8 - 6.0 * x4 * x6)
            z21 = 6.0 * a2 * a5 + emsq * (24.0 * x1 * x5 - 6.0 * x3 * x7)
            z22 = 6.0 * (a4 * a5 + a2 * a6) + emsq *
                (24.0 * (x2 * x5 + x1 * x6) - 6.0 * (x4 * x7 + x3 * x8))
            z23 = 6.0 * a4 * a6 + emsq * (24.0 * x2 * x6 - 6.0 * x4 * x8)
            z1 = z1 + z1 + betasq * z31
            z2 = z2 + z2 + betasq * z32
            z3 = z3 + z3 + betasq * z33
            s3 = cc * xnoi
            s2 = -0.5 * s3 / rtemsq
            s4 = s3 * rtemsq
            s1 = -15.0 * em * s4
            s5 = x1 * x3 + x2 * x4
            s6 = x2 * x3 + x1 * x4
            s7 = x2 * x4 - x1 * x3

            if (lsflg == 1) {
                ss1 = s1
                ss2 = s2
                ss3 = s3
                ss4 = s4
                ss5 = s5
                // ss6 と ss7 は**太陽側（1 周目）の値**。ループの後で取ると月側になってしまう
                ss6 = s6
                ss7 = s7
                sz1 = z1
                sz2 = z2
                sz3 = z3
                sz11 = z11
                sz12 = z12
                sz13 = z13
                sz21 = z21
                sz22 = z22
                sz23 = z23
                sz31 = z31
                sz32 = z32
                sz33 = z33
                zcosg = zcosgl
                zsing = zsingl
                zcosi = zcosil
                zsini = zsinil
                zcosh = zcoshl * cnodm + zsinhl * snodm
                zsinh = snodm * zcoshl - cnodm * zsinhl
                cc = c1l
            }
        }
        zmol = (4.7199672 + 0.22997150 * day - gam) % twopi
        zmos = (6.2565837 + 0.017201977 * day) % twopi

        se2 = 2.0 * ss1 * ss6
        se3 = 2.0 * ss1 * ss7
        si2 = 2.0 * ss2 * sz12
        si3 = 2.0 * ss2 * (sz13 - sz11)
        sl2 = -2.0 * ss3 * sz2
        sl3 = -2.0 * ss3 * (sz3 - sz1)
        sl4 = -2.0 * ss3 * (-21.0 - 9.0 * emsq) * zes
        sgh2 = 2.0 * ss4 * sz32
        sgh3 = 2.0 * ss4 * (sz33 - sz31)
        sgh4 = -18.0 * ss4 * zes
        sh2 = -2.0 * ss2 * sz22
        sh3 = -2.0 * ss2 * (sz23 - sz21)
        ee2 = 2.0 * s1 * s6
        e3 = 2.0 * s1 * s7
        xi2 = 2.0 * s2 * z12
        xi3 = 2.0 * s2 * (z13 - z11)
        xl2 = -2.0 * s3 * z2
        xl3 = -2.0 * s3 * (z3 - z1)
        xl4 = -2.0 * s3 * (-21.0 - 9.0 * emsq) * zel
        xgh2 = 2.0 * s4 * z32
        xgh3 = 2.0 * s4 * (z33 - z31)
        xgh4 = -18.0 * s4 * zel
        xh2 = -2.0 * s2 * z22
        xh3 = -2.0 * s2 * (z23 - z21)
    }

    /** 共鳴の係数を作る（`dsinit`）。`els` は入出力。返り値は dndt */
    fun initResonance(
        xke: Double,
        argpo: Double,
        t: Double,
        tc: Double,
        gsto: Double,
        mo: Double,
        mdot: Double,
        no: Double,
        nodeo: Double,
        nodedot: Double,
        xpidot: Double,
        ecco: Double,
        eccsq: Double,
        els: Elements,
    ) {
        val twopi = 2.0 * PI
        val q22 = 1.7891679e-6
        val q31 = 2.1460748e-6
        val q33 = 2.2123015e-7
        val root22 = 1.7891679e-6
        val root44 = 7.3636953e-9
        val root54 = 2.1765803e-9
        val rptim = 4.37526908801129966e-3
        val root32 = 3.7393792e-7
        val root52 = 1.1428639e-7
        val x2o3 = 2.0 / 3.0
        val znl = 1.5835218e-4
        val zns = 1.19459e-5

        irez = 0
        if (els.nm < 0.0052359877 && els.nm > 0.0034906585) irez = 1
        if (els.nm >= 8.26e-3 && els.nm <= 9.24e-3 && els.em >= 0.5) irez = 2

        val ses = ss1 * zns * ss5
        val sis = ss2 * zns * (sz11 + sz13)
        val sls = -zns * ss3 * (sz1 + sz3 - 14.0 - 6.0 * emsq)
        val sghs = ss4 * zns * (sz31 + sz33 - 6.0)
        var shs = -zns * ss2 * (sz21 + sz23)
        if (els.inclm < 5.2359877e-2 || els.inclm > PI - 5.2359877e-2) shs = 0.0
        if (sinim != 0.0) shs /= sinim
        val sgs = sghs - cosim * shs

        dedt = ses + s1 * znl * s5
        didt = sis + s2 * znl * (z11 + z13)
        dmdt = sls - znl * s3 * (z1 + z3 - 14.0 - 6.0 * emsq)
        val sghl = s4 * znl * (z31 + z33 - 6.0)
        var shll = -znl * s2 * (z21 + z23)
        if (els.inclm < 5.2359877e-2 || els.inclm > PI - 5.2359877e-2) shll = 0.0
        domdt = sgs + sghl
        dnodt = shs
        if (sinim != 0.0) {
            domdt -= cosim / sinim * shll
            dnodt += shll / sinim
        }

        var dndt = 0.0
        val theta = (gsto + tc * rptim) % twopi
        els.em += dedt * t
        els.inclm += didt * t
        els.argpm += domdt * t
        els.nodem += dnodt * t
        els.mm += dmdt * t

        if (irez != 0) {
            val aonv = (els.nm / xke).pow(x2o3)
            if (irez == 2) {
                val cosisq = cosim * cosim
                val emo = els.em
                els.em = ecco
                val emsqo = emsq
                emsq = eccsq
                val em = els.em
                val eoc = em * emsq
                val g201 = -0.306 - (em - 0.64) * 0.440
                val g211: Double
                val g310: Double
                val g322: Double
                val g410: Double
                val g422: Double
                val g520: Double
                if (em <= 0.65) {
                    g211 = 3.616 - 13.2470 * em + 16.2900 * emsq
                    g310 = -19.302 + 117.3900 * em - 228.4190 * emsq + 156.5910 * eoc
                    g322 = -18.9068 + 109.7927 * em - 214.6334 * emsq + 146.5816 * eoc
                    g410 = -41.122 + 242.6940 * em - 471.0940 * emsq + 313.9530 * eoc
                    g422 = -146.407 + 841.8800 * em - 1629.014 * emsq + 1083.4350 * eoc
                    g520 = -532.114 + 3017.977 * em - 5740.032 * emsq + 3708.2760 * eoc
                } else {
                    g211 = -72.099 + 331.819 * em - 508.738 * emsq + 266.724 * eoc
                    g310 = -346.844 + 1582.851 * em - 2415.925 * emsq + 1246.113 * eoc
                    g322 = -342.585 + 1554.908 * em - 2366.899 * emsq + 1215.972 * eoc
                    g410 = -1052.797 + 4758.686 * em - 7193.992 * emsq + 3651.957 * eoc
                    g422 = -3581.690 + 16178.110 * em - 24462.770 * emsq + 12422.520 * eoc
                    g520 = if (em > 0.715) {
                        -5149.66 + 29936.92 * em - 54087.36 * emsq + 31324.56 * eoc
                    } else {
                        1464.74 - 4664.75 * em + 3763.64 * emsq
                    }
                }
                val g533: Double
                val g521: Double
                val g532: Double
                if (em < 0.7) {
                    g533 = -919.22770 + 4988.6100 * em - 9064.7700 * emsq + 5542.21 * eoc
                    g521 = -822.71072 + 4568.6173 * em - 8491.4146 * emsq + 5337.524 * eoc
                    g532 = -853.66600 + 4690.2500 * em - 8624.7700 * emsq + 5341.4 * eoc
                } else {
                    g533 = -37995.780 + 161616.52 * em - 229838.20 * emsq + 109377.94 * eoc
                    g521 = -51752.104 + 218913.95 * em - 309468.16 * emsq + 146349.42 * eoc
                    g532 = -40023.880 + 170470.89 * em - 242699.48 * emsq + 115605.82 * eoc
                }
                val sini2 = sinim * sinim
                val f220 = 0.75 * (1.0 + 2.0 * cosim + cosisq)
                val f221 = 1.5 * sini2
                val f321 = 1.875 * sinim * (1.0 - 2.0 * cosim - 3.0 * cosisq)
                val f322 = -1.875 * sinim * (1.0 + 2.0 * cosim - 3.0 * cosisq)
                val f441 = 35.0 * sini2 * f220
                val f442 = 39.3750 * sini2 * sini2
                val f522 = 9.84375 * sinim * (
                    sini2 * (1.0 - 2.0 * cosim - 5.0 * cosisq) +
                        0.33333333 * (-2.0 + 4.0 * cosim + 6.0 * cosisq)
                    )
                val f523 = sinim * (
                    4.92187512 * sini2 * (-2.0 - 4.0 * cosim + 10.0 * cosisq) +
                        6.56250012 * (1.0 + 2.0 * cosim - 3.0 * cosisq)
                    )
                val f542 = 29.53125 * sinim * (
                    2.0 - 8.0 * cosim + cosisq * (-12.0 + 8.0 * cosim + 10.0 * cosisq)
                    )
                val f543 = 29.53125 * sinim * (
                    -2.0 - 8.0 * cosim + cosisq * (12.0 + 8.0 * cosim - 10.0 * cosisq)
                    )
                val xno2 = els.nm * els.nm
                val ainv2 = aonv * aonv
                var temp1 = 3.0 * xno2 * ainv2
                var temp = temp1 * root22
                d2201 = temp * f220 * g201
                d2211 = temp * f221 * g211
                temp1 *= aonv
                temp = temp1 * root32
                d3210 = temp * f321 * g310
                d3222 = temp * f322 * g322
                temp1 *= aonv
                temp = 2.0 * temp1 * root44
                d4410 = temp * f441 * g410
                d4422 = temp * f442 * g422
                temp1 *= aonv
                temp = temp1 * root52
                d5220 = temp * f522 * g520
                d5232 = temp * f523 * g532
                temp = 2.0 * temp1 * root54
                d5421 = temp * f542 * g521
                d5433 = temp * f543 * g533
                xlamo = (mo + nodeo + nodeo - theta - theta) % twopi
                xfact = mdot + dmdt + 2.0 * (nodedot + dnodt - rptim) - no
                els.em = emo
                emsq = emsqo
            }
            if (irez == 1) {
                val g200 = 1.0 + emsq * (-2.5 + 0.8125 * emsq)
                val g310 = 1.0 + 2.0 * emsq
                val g300 = 1.0 + emsq * (-6.0 + 6.60937 * emsq)
                val f220 = 0.75 * (1.0 + cosim) * (1.0 + cosim)
                val f311 = 0.9375 * sinim * sinim * (1.0 + 3.0 * cosim) - 0.75 * (1.0 + cosim)
                var f330 = 1.0 + cosim
                f330 = 1.875 * f330 * f330 * f330
                del1 = 3.0 * els.nm * els.nm * aonv * aonv
                del2 = 2.0 * del1 * f220 * g200 * q22
                del3 = 3.0 * del1 * f330 * g300 * q33 * aonv
                del1 = del1 * f311 * g310 * q31 * aonv
                xlamo = (mo + nodeo + argpo - theta) % twopi
                xfact = mdot + xpidot - rptim + dmdt + domdt + dnodt - no
            }
            xli = xlamo
            xni = no
            atime = 0.0
            els.nm = no + dndt
        }
    }

    /** 長周期の摂動を軌道要素に足す（`dpper`）。`initializing` は参照実装の `init == 'y'` */
    fun applyPeriodics(t: Double, inclo: Double, initializing: Boolean, els: Perturbed, afspc: Boolean) {
        val twopi = 2.0 * PI
        val zns = 1.19459e-5
        val zes = 0.01675
        val znl = 1.5835218e-4
        val zel = 0.05490

        var zm = zmos + zns * t
        if (initializing) zm = zmos
        var zf = zm + 2.0 * zes * sin(zm)
        var sinzf = sin(zf)
        var f2 = 0.5 * sinzf * sinzf - 0.25
        var f3 = -0.5 * sinzf * cos(zf)
        val ses = se2 * f2 + se3 * f3
        val sis = si2 * f2 + si3 * f3
        val sls = sl2 * f2 + sl3 * f3 + sl4 * sinzf
        val sghs = sgh2 * f2 + sgh3 * f3 + sgh4 * sinzf
        val shs = sh2 * f2 + sh3 * f3

        zm = zmol + znl * t
        if (initializing) zm = zmol
        zf = zm + 2.0 * zel * sin(zm)
        sinzf = sin(zf)
        f2 = 0.5 * sinzf * sinzf - 0.25
        f3 = -0.5 * sinzf * cos(zf)
        val sel = ee2 * f2 + e3 * f3
        val sil = xi2 * f2 + xi3 * f3
        val sll = xl2 * f2 + xl3 * f3 + xl4 * sinzf
        val sghl = xgh2 * f2 + xgh3 * f3 + xgh4 * sinzf
        val shll = xh2 * f2 + xh3 * f3

        var pe = ses + sel
        var pinc = sis + sil
        var pl = sls + sll
        var pgh = sghs + sghl
        var ph = shs + shll

        if (!initializing) {
            pe -= peo
            pinc -= pinco
            pl -= plo
            pgh -= pgho
            ph -= pho
            els.inclp += pinc
            els.ep += pe
            val sinip = sin(els.inclp)
            val cosip = cos(els.inclp)

            if (els.inclp >= 0.2) {
                ph /= sinip
                pgh -= cosip * ph
                els.argpp += pgh
                els.nodep += ph
                els.mp += pl
            } else {
                // 傾斜角が小さいと ph / sinip が暴れるので、別の式に切り替える
                val sinop = sin(els.nodep)
                val cosop = cos(els.nodep)
                var alfdp = sinip * sinop
                var betdp = sinip * cosop
                val dalf = ph * cosop + pinc * cosip * sinop
                val dbet = -ph * sinop + pinc * cosip * cosop
                alfdp += dalf
                betdp += dbet
                els.nodep %= twopi
                if (els.nodep < 0.0 && afspc) els.nodep += twopi
                var xls = els.mp + els.argpp + cosip * els.nodep
                val dls = pl + pgh - pinc * els.nodep * sinip
                xls += dls
                val xnoh = els.nodep
                els.nodep = atan2(alfdp, betdp)
                if (els.nodep < 0.0 && afspc) els.nodep += twopi
                if (abs(xnoh - els.nodep) > PI) {
                    els.nodep = if (els.nodep < xnoh) els.nodep + twopi else els.nodep - twopi
                }
                els.mp += pl
                els.argpp = xls - els.mp - cosip * els.nodep
            }
        }
    }

    /** 共鳴を時間積分して軌道要素を進める（`dspace`）。返り値は dndt */
    fun applyResonance(
        argpo: Double,
        argpdot: Double,
        t: Double,
        tc: Double,
        gsto: Double,
        no: Double,
        els: Elements,
    ): Double {
        val twopi = 2.0 * PI
        val fasx2 = 0.13130908
        val fasx4 = 2.8843198
        val fasx6 = 0.37448087
        val g22 = 5.7686396
        val g32 = 0.95240898
        val g44 = 1.8014998
        val g52 = 1.0508330
        val g54 = 4.4108898
        val rptim = 4.37526908801129966e-3
        val stepp = 720.0
        val stepn = -720.0
        val step2 = 259200.0

        var dndt = 0.0
        val theta = (gsto + tc * rptim) % twopi
        els.em += dedt * t
        els.inclm += didt * t
        els.argpm += domdt * t
        els.nodem += dnodt * t
        els.mm += dmdt * t

        var ft = 0.0
        if (irez != 0) {
            // 前回と続きになっていないなら積分をやり直す
            if (atime == 0.0 || t * atime <= 0.0 || abs(t) < abs(atime)) {
                atime = 0.0
                xni = no
                xli = xlamo
            }
            val delt = if (t > 0.0) stepp else stepn

            var xndt = 0.0
            var xldot = 0.0
            var xnddt = 0.0
            var stepping = true
            while (stepping) {
                if (irez != 2) {
                    xndt = del1 * sin(xli - fasx2) + del2 * sin(2.0 * (xli - fasx4)) +
                        del3 * sin(3.0 * (xli - fasx6))
                    xldot = xni + xfact
                    xnddt = del1 * cos(xli - fasx2) +
                        2.0 * del2 * cos(2.0 * (xli - fasx4)) +
                        3.0 * del3 * cos(3.0 * (xli - fasx6))
                    xnddt *= xldot
                } else {
                    val xomi = argpo + argpdot * atime
                    val x2omi = xomi + xomi
                    val x2li = xli + xli
                    xndt = d2201 * sin(x2omi + xli - g22) + d2211 * sin(xli - g22) +
                        d3210 * sin(xomi + xli - g32) + d3222 * sin(-xomi + xli - g32) +
                        d4410 * sin(x2omi + x2li - g44) + d4422 * sin(x2li - g44) +
                        d5220 * sin(xomi + xli - g52) + d5232 * sin(-xomi + xli - g52) +
                        d5421 * sin(xomi + x2li - g54) + d5433 * sin(-xomi + x2li - g54)
                    xldot = xni + xfact
                    xnddt = d2201 * cos(x2omi + xli - g22) + d2211 * cos(xli - g22) +
                        d3210 * cos(xomi + xli - g32) + d3222 * cos(-xomi + xli - g32) +
                        d5220 * cos(xomi + xli - g52) + d5232 * cos(-xomi + xli - g52) +
                        2.0 * (
                            d4410 * cos(x2omi + x2li - g44) + d4422 * cos(x2li - g44) +
                                d5421 * cos(xomi + x2li - g54) + d5433 * cos(-xomi + x2li - g54)
                            )
                    xnddt *= xldot
                }
                if (abs(t - atime) >= stepp) {
                    xli += xldot * delt + xndt * step2
                    xni += xndt * delt + xnddt * step2
                    atime += delt
                } else {
                    ft = t - atime
                    stepping = false
                }
            }
            els.nm = xni + xndt * ft + xnddt * ft * ft * 0.5
            val xl = xli + xldot * ft + xndt * ft * ft * 0.5
            if (irez != 1) {
                els.mm = xl - 2.0 * els.nodem + 2.0 * theta
                dndt = els.nm - no
            } else {
                els.mm = xl - els.nodem - els.argpm + theta
                dndt = els.nm - no
            }
            els.nm = no + dndt
        }
        return dndt
    }

    /** dsinit / dspace が読み書きする軌道要素。参照実装の参照渡しの代わり */
    internal class Elements(
        var em: Double,
        var argpm: Double,
        var inclm: Double,
        var mm: Double,
        var nm: Double,
        var nodem: Double,
    )

    /** dpper が読み書きする軌道要素 */
    internal class Perturbed(
        var ep: Double,
        var inclp: Double,
        var nodep: Double,
        var argpp: Double,
        var mp: Double,
    )
}
