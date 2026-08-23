package jp.jig.glasses.sample.kmp.alignment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationMarkerTest {
    @Test
    fun `十字は中心を空けて上下左右に腕を持つ`() {
        val size = CalibrationMarker.SIZE
        val marker = CalibrationMarker.grayscale()
        val center = size / 2

        assertEquals(size * size, marker.size)
        assertEquals(0, marker[center * size + center].toInt())
        assertTrue(marker[center * size + center + size / 4].toInt() != 0)
        assertTrue(marker[(center + size / 4) * size + center].toInt() != 0)
    }
}
