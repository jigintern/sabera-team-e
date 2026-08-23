package jp.jig.glasses.sample.kmp.alignment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YawDriftCorrectorTest {
    @Test
    fun `静止中のヨードリフトを方位へ足さない`() {
        val corrector = YawDriftCorrector()
        var result = corrector.update(0.0, 0.0, 0.0, 0.1, 0L)
        for (sample in 1..70) {
            result = corrector.update(-0.074 * sample, 0.0, 0.0, 0.1, sample * 100L)
        }

        assertEquals(0.0, result.yawDeg, 1e-9)
        assertEquals(-0.74, result.driftRateDps, 1e-9)
        assertEquals(-5.18, result.heldDriftDeg, 1e-9)
    }

    @Test
    fun `回転中は実測ドリフトを引いて真の回転量を足す`() {
        val corrector = YawDriftCorrector()
        for (sample in 0..60) {
            corrector.update(-0.074 * sample, 0.0, 0.0, 0.1, sample * 100L)
        }

        val result = corrector.update(-0.074 * 60 + 10.0 - 0.074, 20.0, 0.0, 0.0, 6_100L)
        assertTrue(result.moving)
        assertEquals(10.0, result.yawDeg, 1e-9)
    }

    @Test
    fun `角度の折り返しをまたいでも短い側の差分になる`() {
        val corrector = YawDriftCorrector()
        corrector.update(179.0, 10.0, 0.0, 0.0, 0L)
        val result = corrector.update(-176.0, 10.0, 0.0, 0.0, 1_000L)
        assertEquals(-176.0, result.yawDeg, 1e-9)
    }
}
