package jp.jig.glasses.sample.kmp.starmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 端の線が端に無いと、測った画角がそのまま間違う。 */
class FovPatternTest {

    private val width = STAR_MAP_WIDTH
    private val height = STAR_MAP_HEIGHT
    private val gray = FovPattern.grayscale()

    private fun at(x: Int, y: Int): Int = gray[y * width + x].toInt() and 0xFF

    @Test
    fun `四辺と中心に線が出る`() {
        assertEquals(255, at(0, height / 3))
        assertEquals(255, at(width - 1, height / 3))
        assertEquals(255, at(width / 3, 0))
        assertEquals(255, at(width / 3, height - 1))
        // 中心は端と見間違えないように暗い
        assertEquals(150, at(width / 2, height / 3))
        assertEquals(150, at(width / 3, height / 2))
    }

    @Test
    fun `それ以外は真っ黒で転送が軽い`() {
        assertEquals(0, at(width / 4, height / 4))
        val lit = gray.count { it != 0.toByte() }
        assertTrue("光る画素は全体の 5% 未満: $lit", lit < width * height / 20)
        // 3bit RLE は真っ黒でも 32 画素で 1 バイト使うので、面積 / 32 = 5.4KB が下限。
        // 星図（6〜7KB）と同じ帯なので、転送時間も変わらない
        val map = StarMap(width, height, gray, emptyList())
        val floor = width * height / 32
        assertTrue("下限を割ることはない", map.compressedSizeBytes() >= floor)
        assertTrue("星図と同じ帯に収まる: ${map.compressedSizeBytes()}", map.compressedSizeBytes() < 7_500)
    }

    @Test
    fun `印の間隔から画角が出る`() {
        assertEquals(35.0, FovPattern.fovDeg(markSpacingMeters = 1.261, wallDistanceMeters = 2.0), 0.02)
        assertEquals(22.4, FovPattern.fovDeg(markSpacingMeters = 0.792, wallDistanceMeters = 2.0), 0.05)
    }
}
