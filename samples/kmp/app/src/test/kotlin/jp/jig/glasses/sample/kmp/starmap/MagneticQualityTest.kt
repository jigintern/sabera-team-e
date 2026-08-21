package jp.jig.glasses.sample.kmp.starmap

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「磁気精度は高い」と言われながら 30° ずれる、という一番たちの悪い失敗を弾くための検査。
 * 期待値は日本の値（磁場 46µT・伏角 49°）を使う。
 */
class MagneticQualityTest {

    private val expectedMicroTesla = 46.0
    private val expectedInclinationDeg = 49.0

    private fun quality(strength: Double, inclination: Double) = magneticQuality(
        measuredMicroTesla = strength,
        measuredInclinationDeg = inclination,
        expectedMicroTesla = expectedMicroTesla,
        expectedInclinationDeg = expectedInclinationDeg,
    )

    @Test
    fun `屋外で正しく測れていれば通す`() {
        val result = quality(45.0, 50.5)
        assertFalse(result.distorted)
        assertTrue(result.reason == null)
    }

    @Test
    fun `ケースの磁石は磁場の強さで分かる`() {
        val result = quality(180.0, 51.0)
        assertTrue(result.distorted)
        assertTrue(result.reason!!.contains("磁石"))
        assertTrue(result.strengthRatio > MAX_STRENGTH_RATIO)
    }

    @Test
    fun `打ち消されて弱くなる歪みも弾く`() {
        val result = quality(20.0, 49.0)
        assertTrue(result.distorted)
        assertTrue(result.reason!!.contains("弱すぎ"))
    }

    @Test
    fun `強さが合っていても向きが違えば歪んでいる`() {
        val result = quality(46.0, 30.0)
        assertTrue(result.distorted)
        assertTrue(result.reason!!.contains("向き"))
        assertTrue(result.inclinationDiffDeg > MAX_INCLINATION_DIFF_DEG)
    }

    @Test
    fun `境目のすぐ内側では誤検知しない`() {
        // 屋外で正しく測れているのに止められるほうが体験としては悪い
        assertFalse(quality(expectedMicroTesla * 1.2, expectedInclinationDeg + 5.0).distorted)
        assertFalse(quality(expectedMicroTesla * 0.8, expectedInclinationDeg - 5.0).distorted)
    }

    @Test
    fun `期待値が取れないときは歪んでいないものとして扱う`() {
        val result = magneticQuality(46.0, 49.0, 0.0, 49.0)
        assertFalse(result.distorted)
        assertTrue(result.strengthRatio == 1.0)
    }
}
