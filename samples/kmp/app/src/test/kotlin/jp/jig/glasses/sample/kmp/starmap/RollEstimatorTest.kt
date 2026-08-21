package jp.jig.glasses.sample.kmp.starmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * ロールは**実機で軸を確かめるまで既定オフ**だが、計算と符号は机の上で固定しておく。
 * ここが反転していると、首を傾けたときにずれが 2 倍になる（補正が逆に効く）。
 */
class RollEstimatorTest {

    /**
     * ここでは軸割り当てを固定して**推定の式だけ**を確かめる（既定値は実機で測り直されるので、
     * それに引きずられると式のテストにならない）。上 = −X・前 = +Z なら左右は −Y になる。
     */
    private val axes = AccelAxes(upIndex = 0, upSign = -1, forwardIndex = 2)

    /** 傾き [deg] のときの加速度[mg]。上の軸割り当てに合わせて作る */
    private fun accel(rollDeg: Double, magnitude: Double = 1000.0): DoubleArray {
        val up = magnitude * cos(rollDeg * RAD)
        val lateral = magnitude * sin(rollDeg * RAD)
        return doubleArrayOf(-up, lateral, 0.0)
    }

    private fun RollEstimator.feed(a: DoubleArray) = update(a[0], a[1], a[2])

    @Test
    fun `水平なら0度`() {
        val estimator = RollEstimator(axes)
        assertEquals(0.0, estimator.feed(accel(0.0))!!, 1e-9)
    }

    @Test
    fun `傾けた角度がそのまま出る`() {
        assertEquals(15.0, RollEstimator(axes).feed(accel(15.0))!!, 1e-9)
        assertEquals(-25.0, RollEstimator(axes).feed(accel(-25.0))!!, 1e-9)
    }

    @Test
    fun `平滑化しながら追いつく`() {
        val estimator = RollEstimator(axes)
        estimator.feed(accel(0.0))
        repeat(20) { estimator.feed(accel(20.0)) }
        assertEquals(20.0, estimator.rollDeg!!, 0.2)
    }

    @Test
    fun `首を振っている間のサンプルは捨てる`() {
        val estimator = RollEstimator(axes)
        estimator.feed(accel(10.0))
        // 重力以外の加速度が乗って 2g になったサンプル
        assertEquals(10.0, estimator.feed(accel(40.0, magnitude = 2000.0))!!, 1e-9)
        assertEquals(10.0, estimator.feed(accel(40.0, magnitude = 300.0))!!, 1e-9)
    }

    @Test
    fun `真上を向くとロールは測れない`() {
        val estimator = RollEstimator(axes)
        estimator.feed(accel(5.0))
        // 重力が前方軸へ寄った状態。上下・左右の 2 軸からは角度が決まらない
        assertEquals(5.0, estimator.update(0.0, 0.0, 1000.0)!!, 1e-9)
    }

    @Test
    fun `まだ測れていなければ null`() {
        val estimator = RollEstimator(axes)
        assertNull(estimator.update(0.0, 0.0, 1000.0))
        estimator.feed(accel(3.0))
        estimator.reset()
        assertNull(estimator.rollDeg)
    }

    @Test
    fun `軸の割り当ては残り1軸を左右とみなす`() {
        assertEquals(1, AccelAxes(upIndex = 0, upSign = -1, forwardIndex = 2).lateralIndex)
        assertEquals(2, AccelAxes(upIndex = 0, upSign = -1, forwardIndex = 1).lateralIndex)
        assertEquals(0, AccelAxes(upIndex = 1, upSign = 1, forwardIndex = 2).lateralIndex)
    }

    @Test
    fun `ロールを入れると絵が首の傾きと逆に回る`() {
        val look = Look(120.0, 30.0)
        val scale = projectionScale(STAR_MAP_WIDTH, ObservationDefaults.STAR_MAP_FOV_DEG)
        // 視線の 10° 上（地平線基準）にある点
        val above = enu(look.azDeg, look.altDeg + 10.0)

        val level = project(above, Basis(look.azDeg, look.altDeg), scale, STAR_MAP_WIDTH, STAR_MAP_HEIGHT)!!
        assertEquals("追従なしなら真上に出る", STAR_MAP_WIDTH / 2.0, level[0], 1e-6)
        assertTrue(level[1] < STAR_MAP_HEIGHT / 2.0)

        val rolled = project(above, Basis(look.azDeg, look.altDeg, 20.0), scale, STAR_MAP_WIDTH, STAR_MAP_HEIGHT)!!
        val dx = rolled[0] - STAR_MAP_WIDTH / 2.0
        val dy = STAR_MAP_HEIGHT / 2.0 - rolled[1]
        assertTrue("首を右に傾けたら絵は左へ回る", dx < 0.0)
        assertEquals("回った角度は 20°", tan(20.0 * RAD), abs(dx / dy), 1e-6)
        // 中心からの距離は変わらない（視線まわりの回転なので）
        assertEquals(
            abs(level[1] - STAR_MAP_HEIGHT / 2.0),
            kotlin.math.hypot(dx, dy),
            1e-6,
        )
    }
}
