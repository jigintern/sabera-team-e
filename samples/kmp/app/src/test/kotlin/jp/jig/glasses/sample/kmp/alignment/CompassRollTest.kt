package jp.jig.glasses.sample.kmp.alignment

import jp.jig.glasses.sample.kmp.doc.DemoSensors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.math.abs

/**
 * スマホを横に持っても方位と仰角は変わらない（#79・#165）。
 *
 * [Compass] は画面の回転を見ずに**背面（端末 Z 軸）の向き**だけを出すので、
 * 画面法線まわりに回しても（縦↔横）答えは同じになる。
 * **方位合わせを縦に固定する理由をセンサーに置かない。**
 * 以前は「横持ちだとセンサー軸を取り違えて 90° ずれる」と書いていた。
 *
 * `SensorManager` の行列計算は Robolectric でも本物が動くので、実機なしで決着する。
 */
@RunWith(RobolectricTestRunner::class)
class CompassRollTest {

    private val context = RuntimeEnvironment.getApplication()

    // Compass は getDefaultSensor が返したセンサーだけを購読するので、先に足しておく
    private val sensors = DemoSensors(context)
    private val compass = Compass(context).also { it.start() }

    /** 基準。**ここがずれると下の「同じ」は符号の取り違えごと一致してしまう** */
    @Test
    fun `縦持ちでは背面の方角と高さをそのまま返す`() {
        for (az in AZIMUTHS) for (el in ELEVATIONS) {
            val (heading, pitch) = aim(az, el, rollDeg = 0.0)
            assertAngle("方位 az=$az el=$el", az, heading)
            assertEquals("仰角 az=$az el=$el", el, pitch, TOLERANCE_DEG)
        }
    }

    @Test
    fun `横に持っても逆さにしても方位と仰角は縦持ちと同じ`() {
        for (az in AZIMUTHS) for (el in ELEVATIONS) {
            val (upright, uprightPitch) = aim(az, el, rollDeg = 0.0)
            for (roll in listOf(90.0, -90.0, 180.0)) {
                val (heading, pitch) = aim(az, el, roll)
                assertAngle("方位 az=$az el=$el roll=$roll", upright, heading)
                assertEquals("仰角 az=$az el=$el roll=$roll", uprightPitch, pitch, TOLERANCE_DEG)
            }
        }
    }

    private fun aim(az: Double, el: Double, rollDeg: Double): Pair<Double, Double> {
        sensors.aim(az, el, rollDeg)
        val heading = compass.magneticHeadingDeg
        val pitch = compass.pitchDeg
        assertNotNull("回転ベクトルが Compass に届いていない", heading)
        return heading!! to pitch!!
    }

    /** 359° と 1° は 2° しか離れていない */
    private fun assertAngle(message: String, expected: Double, actual: Double) {
        val diff = ((actual - expected) % 360.0 + 540.0) % 360.0 - 180.0
        assertEquals(message, 0.0, abs(diff), TOLERANCE_DEG)
    }

    private companion object {
        val AZIMUTHS = (0 until 360 step 45).map { it.toDouble() }

        // 真上付近は方位そのものが定まらないので入れない
        val ELEVATIONS = listOf(-30.0, 0.0, 30.0, 60.0)
        const val TOLERANCE_DEG = 0.5
    }
}
