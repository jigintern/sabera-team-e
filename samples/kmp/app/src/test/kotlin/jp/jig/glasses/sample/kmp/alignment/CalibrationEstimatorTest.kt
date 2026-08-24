package jp.jig.glasses.sample.kmp.alignment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationEstimatorTest {
    @Test
    fun `0度の境界をまたいでも安定区間の円周平均を取る`() {
        val estimator = CalibrationEstimator()
        var estimate: CalibrationEstimate? = null

        repeat(11) { index ->
            val jitter = if (index % 2 == 0) -0.2 else 0.2
            estimate = estimator.add(
                atMs = index * 100L,
                phoneHeadingDeg = 359.0 + jitter,
                phonePitchDeg = 12.0 + jitter / 2.0,
                glassYawDeg = 9.0 + jitter,
                glassPitchDeg = 10.0,
            )
        }

        val result = checkNotNull(estimate)
        assertTrue(result.stable)
        // オフセットは 方位 ＋ ヨー（ヨーは方位と逆に回る）。359 + 9 = 368 → 8。
        // このテストは両方に同じ揺れを乗せているので、引き算だった頃は揺れが打ち消えて
        // ちょうど -10 になっていた。足し算では消えずに 2 倍で残るため、幅を持たせる
        assertEquals(8.0, result.headingOffsetDeg, 0.05)
        assertEquals(2.0, result.pitchOffsetDeg, 0.1)
        assertEquals(11, result.sampleCount)
    }

    @Test
    fun `端末とグラスが一緒に動いている間は確定しない`() {
        val estimator = CalibrationEstimator()
        var estimate: CalibrationEstimate? = null

        repeat(11) { index ->
            estimate = estimator.add(
                atMs = index * 100L,
                phoneHeadingDeg = index * 3.0,
                phonePitchDeg = 10.0,
                glassYawDeg = 20.0 + index * 3.0,
                glassPitchDeg = 10.0,
            )
        }

        assertFalse(checkNotNull(estimate).stable)
    }

}
