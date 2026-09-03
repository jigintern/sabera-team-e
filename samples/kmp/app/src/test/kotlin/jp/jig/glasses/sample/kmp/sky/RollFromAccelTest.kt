package jp.jig.glasses.sample.kmp.sky

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 加速度から起こす首の傾き（[rollFromAccel]）。
 *
 * **機体の軸は実測値をそのまま使う**（`docs/team-e/70_measurements.md`・
 * 上 = +X・前 = −Y・静止 865 件）。ここを取り違えると**星図が枠ごと回る**ので、
 * 軸の約束を数字で固定しておく。
 *
 * 以前は「X 軸が右」と仮定していて、水平に構えただけで 90° を返していた
 * （返っていたのは実際には 90° − ピッチ）。見上げるほど回転量が変わるため、
 * **目標が画面の中を動いて逃げた**。
 */
class RollFromAccelTest {

    /**
     * 実測した軸で、静止中の加速度を作る。
     *
     * ```
     * a = 1000 ( sinθ·前 + cosθ·cosφ·上 − cosθ·sinφ·右 )
     * ```
     * 上 = +X・前 = −Y・右 = 前 × 上 = +Z。
     */
    private fun accel(pitchDeg: Double, rollDeg: Double): Triple<Int, Int, Int> {
        val t = pitchDeg * RAD
        val f = rollDeg * RAD
        val x = 1000.0 * cos(t) * cos(f)
        val y = -1000.0 * sin(t)
        val z = -1000.0 * cos(t) * sin(f)
        return Triple(x.roundToInt(), y.roundToInt(), z.roundToInt())
    }

    private fun roll(pitchDeg: Double, rollDeg: Double): Double {
        val (x, y, z) = accel(pitchDeg, rollDeg)
        return rollFromAccel(x, y, z)
    }

    @Test
    fun `水平に構えたら傾きは0`() {
        assertEquals(0.0, roll(pitchDeg = 0.0, rollDeg = 0.0), 0.2)
    }

    /** **ピッチが漏れない**のがこの関数の要件。見上げても傾きは 0 のまま */
    @Test
    fun `見上げてもピッチは傾きに漏れない`() {
        for (pitch in listOf(10.0, 20.0, 45.0, 70.0, 80.0)) {
            assertEquals("ピッチ $pitch° で傾きが出た", 0.0, roll(pitch, 0.0), 0.2)
        }
    }

    /** 右耳が下がる向きが正。**左右を区別できること**（前は +20° も −20° も同じ値だった） */
    @Test
    fun `右耳が下がる向きが正で、左右を区別する`() {
        assertEquals(20.0, roll(pitchDeg = 0.0, rollDeg = 20.0), 0.2)
        assertEquals(-20.0, roll(pitchDeg = 0.0, rollDeg = -20.0), 0.2)
    }

    @Test
    fun `見上げながら傾けても傾きだけが出る`() {
        assertEquals(20.0, roll(pitchDeg = 45.0, rollDeg = 20.0), 0.2)
        assertEquals(-30.0, roll(pitchDeg = 60.0, rollDeg = -30.0), 0.2)
    }

    /** 真上ではロールが定義できない。**地平線も画面に無いので 0 を返す** */
    @Test
    fun `真上を向いたら0を返す`() {
        assertEquals(0.0, roll(pitchDeg = 90.0, rollDeg = 20.0), 1e-9)
    }

    /**
     * **傾きを出さない帯は「真上」ではなく、天頂から 11.54°**（#179）。
     *
     * 門は `hypot(aX, aZ) = 1000·|cosθ| < 200` なのでロールに依存せず、
     * 境界は `acos(0.2) = 78.46°`。**この定数を守るテストが 1 本も無かった。**
     * 上の高ピッチ検査はどれもロール 0° で `aZ = 0` になるため、
     * **しきい値を 30mG まで下げても既存の検査は全部通ってしまう**。
     *
     * ここが落ちたら「帯を狭めた」ということ。**狭めてよいかは実測待ち**
     * （天頂付近の加速度ノイズ床と、首振り中に乗る線形加速度）なので、
     * 数字を動かすときは `docs/team-e/70_measurements.md` に測った値を残してから変える。
     */
    @Test
    fun `天頂から11度の帯では傾きを出さない`() {
        // ピッチ 80°・ロール 20° は hypot = 173mG で、いまのしきい値 200 を下回る
        assertEquals(0.0, roll(pitchDeg = 80.0, rollDeg = 20.0), 1e-9)
        // ピッチ 85°・ロール −30° は 87mG
        assertEquals(0.0, roll(pitchDeg = 85.0, rollDeg = -30.0), 1e-9)
    }

    /** 帯のすぐ外ではロールがそのまま出る。**帯を広げていないことの裏側** */
    @Test
    fun `帯のすぐ外では傾きが出る`() {
        // ピッチ 75° は hypot = 259mG で門を通る
        assertEquals(20.0, roll(pitchDeg = 75.0, rollDeg = 20.0), 0.2)
    }

    /** グラスが繋がる前など、加速度が来ていないときに勝手に回さない */
    @Test
    fun `加速度が来ていなければ0を返す`() {
        assertEquals(0.0, rollFromAccel(0, 0, 0), 1e-9)
    }
}
