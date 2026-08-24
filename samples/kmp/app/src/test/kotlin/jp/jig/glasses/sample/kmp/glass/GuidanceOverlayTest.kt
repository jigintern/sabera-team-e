package jp.jig.glasses.sample.kmp.glass

import jp.jig.glasses.sample.kmp.sky.GuidanceFrame
import org.junit.Assert.assertTrue
import org.junit.Test

class GuidanceOverlayTest {
    @Test
    fun `矢印は小画像のバッファ内に収まる`() {
        val overlay = guidanceOverlay(GuidanceFrame("オリオン座", 30.0, 90.0, near = false, arrived = false))

        assertTrue(overlay.gray.any { (it.toInt() and 0xff) > 0 })
        assertTrue(overlay.width * overlay.height * 2 + overlay.compressedBytes < 10_000)
    }

    @Test
    fun `到着時は矢印とは異なる二重リングになる`() {
        val arrow = guidanceOverlay(GuidanceFrame("ベガ", 8.0, 0.0, near = true, arrived = false))
        val arrived = guidanceOverlay(GuidanceFrame("ベガ", 3.0, 0.0, near = true, arrived = true))

        assertTrue(!arrow.gray.contentEquals(arrived.gray))
        assertTrue(arrived.gray.count { (it.toInt() and 0xff) > 0 } > arrow.gray.count { (it.toInt() and 0xff) > 0 })
    }
}
