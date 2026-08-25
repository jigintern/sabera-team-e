package jp.jig.glasses.sample.kmp.sky

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.sin
import org.junit.Test

/**
 * 長期歳差の検算。
 *
 * **係数表を写し間違えても静かに間違った空が出るだけ**なので、係数そのものではなく
 * 係数に依存しない性質を見る。ここが通れば、桁を落とすような写し間違いは残らない。
 *
 * - J2000 では何も動かない
 * - ±3 世紀では IAU 1976 の式と一致する（切り替えの継ぎ目が飛ばない）
 * - 天の北極が黄道の極のまわりを円錐で回る（半頂角 ≒ 黄道傾斜角）
 * - 往復すると元へ戻る
 */
class LongTermPrecessionTest {

    private val yearDays = 365.25

    /** 天の北極。長期歳差でいちばん大きく動くので、性質の検査はここを見るのがいちばん効く */
    private val poleRa = 0.0
    private val poleDec = 90.0

    @Test
    fun `J2000 では動かない`() {
        val moved = longTermPrecess(101.2872, -16.7161, 0.0)
        assertEquals(101.2872, moved[0], 1e-6)
        assertEquals(-16.7161, moved[1], 1e-6)
    }

    @Test
    fun `3世紀までは IAU 1976 の式と一致する`() {
        // 切り替えの境目で星図が飛ばないことの検査。2 つのモデルは無関係に作られているので、
        // ここが数秒角で合うなら、係数表を写し間違えていない
        for (years in listOf(-300.0, -100.0, 100.0, 300.0)) {
            val d = years * yearDays
            for ((ra, dec) in listOf(101.2872 to -16.7161, 279.23 to 38.78, 0.0 to 89.26)) {
                val iau = precessIau1976(ra, dec, d)
                val longTerm = longTermPrecess(ra, dec, d)
                val sep = separationDeg(iau[0], iau[1], longTerm[0], longTerm[1])
                assertTrue("${years}年で ${sep * 3600}秒角 離れた", sep * 3600.0 < 20.0)
            }
        }
    }

    @Test
    fun `天の北極は黄道の極のまわりを円錐で回る`() {
        // 半頂角が黄道傾斜角（約 23.4°）の円錐を、約 25,800 年で 1 周する。
        // 1 万年ぶん回ると J2000 の極から 40〜48° 離れる
        val d = -10_000.0 * yearDays
        val moved = longTermPrecess(poleRa, poleDec, d)
        val sep = separationDeg(poleRa, poleDec, moved[0], moved[1])

        val turnDeg = 360.0 * 10_000.0 / 25_772.0
        val expected = 2.0 * Math.toDegrees(
            asin(sin(Math.toRadians(23.44)) * sin(Math.toRadians(turnDeg) / 2.0)),
        )
        // 黄道傾斜角そのものが 22.0〜24.5° で揺れるので、幅を持たせて見る
        assertTrue("極が $sep° 動いた（期待 $expected° 付近）", abs(sep - expected) < 3.0)
    }

    @Test
    fun `1万年前の天の北極は北極星から大きく離れる`() {
        // 「1 万年前の空」の見どころ。ここが数度しか動かないなら、長期歳差が効いていない
        val d = -10_000.0 * yearDays
        val moved = longTermPrecess(poleRa, poleDec, d)
        val sep = separationDeg(poleRa, poleDec, moved[0], moved[1])
        assertTrue("$sep° しか動いていない", sep > 30.0)
    }

    @Test
    fun `往復すると元へ戻る`() {
        for (years in listOf(-10_000.0, -3_000.0, 5_000.0)) {
            val d = years * yearDays
            val moved = longTermPrecess(101.2872, -16.7161, d)
            val back = longTermInversePrecess(moved[0], moved[1], d)
            val sep = separationDeg(101.2872, -16.7161, back[0], back[1])
            assertTrue("${years}年の往復で ${sep * 3600}秒角 ずれた", sep * 3600.0 < 0.1)
        }
    }

    @Test
    fun `切り替えの境目で飛ばない`() {
        // precess が 3 世紀を境に式を替えるので、そのすぐ内と外で不連続にならないことを見る
        val inside = precess(101.2872, -16.7161, LONG_TERM_PRECESSION_DAYS - 1.0)
        val outside = precess(101.2872, -16.7161, LONG_TERM_PRECESSION_DAYS + 1.0)
        val sep = separationDeg(inside[0], inside[1], outside[0], outside[1])
        assertTrue("境目で ${sep * 3600}秒角 飛んだ", sep * 3600.0 < 20.0)
    }

    @Test
    fun `黄道の極と赤道の極は黄道傾斜角ぶん離れている`() {
        // 2 つの極の離角がその時代の黄道傾斜角そのもの。22.0〜24.5° の外へ出たら
        // どちらかの表を写し間違えている
        for (centuries in listOf(-100.0, -50.0, 0.0, 50.0, 100.0)) {
            val obliquity = Math.toDegrees(
                Math.acos(
                    (eclipticPole(centuries).normalized() dot equatorPole(centuries).normalized())
                        .coerceIn(-1.0, 1.0),
                ),
            )
            assertTrue("${centuries}世紀で黄道傾斜角が $obliquity°", obliquity in 21.5..24.9)
        }
    }

    /** 切り替え前の式そのもの。[precess] は境目の外で長期歳差へ渡してしまうので、ここに写しを置く */
    private fun precessIau1976(raDeg: Double, decDeg: Double, d: Double): DoubleArray {
        val t = d / 36525.0
        val t2 = t * t
        val t3 = t2 * t
        val zeta = (2306.2181 * t + 0.30188 * t2 + 0.017998 * t3) / 3600.0 * RAD
        val z = (2306.2181 * t + 1.09468 * t2 + 0.018203 * t3) / 3600.0 * RAD
        val theta = (2004.3109 * t - 0.42665 * t2 - 0.041833 * t3) / 3600.0 * RAD
        val a = raDeg * RAD
        val dec = decDeg * RAD
        val cosDec = Math.cos(dec)
        val aPlus = a + zeta
        val bigA = cosDec * Math.sin(aPlus)
        val bigB = Math.cos(theta) * cosDec * Math.cos(aPlus) - Math.sin(theta) * Math.sin(dec)
        val bigC = Math.sin(theta) * cosDec * Math.cos(aPlus) + Math.cos(theta) * Math.sin(dec)
        val ra = (((Math.atan2(bigA, bigB) + z) * DEG % 360.0) + 360.0) % 360.0
        return doubleArrayOf(ra, Math.asin(bigC.coerceIn(-1.0, 1.0)) * DEG)
    }

    private fun separationDeg(ra1: Double, dec1: Double, ra2: Double, dec2: Double): Double {
        val a = equatorialUnitVector(ra1, dec1)
        val b = equatorialUnitVector(ra2, dec2)
        return Math.toDegrees(Math.acos((a dot b).coerceIn(-1.0, 1.0)))
    }
}
