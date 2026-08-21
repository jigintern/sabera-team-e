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
    fun `星を左端と右端に合わせた2点から画角が出る`() {
        // 画角 35° の投影で、端の線の位置に星が来る視線をそれぞれ作る
        for (trueFov in listOf(30.0, 35.0, 42.0)) {
            val scale = projectionScale(width, trueFov)
            val spanPx = (width - (FovPattern.EDGE_THICKNESS - 1) * 2) / 2.0
            // 中心から端の線までの角度（r = 2 tan(θ/2) の逆）
            val edgeDeg = 2.0 * Math.toDegrees(kotlin.math.atan(spanPx / scale / 2.0))
            // 水平に振るので、同じ高度で方位だけ違う 2 つの視線
            val first = Look(100.0, 0.0)
            val second = Look(100.0 + edgeDeg * 2.0, 0.0)
            assertEquals(trueFov, FovPattern.fovFromEdgeAlignment(first, second), 0.05)
        }
    }

    @Test
    fun `高いところの星でも同じ画角になる`() {
        val trueFov = 35.0
        val scale = projectionScale(width, trueFov)
        val spanPx = (width - (FovPattern.EDGE_THICKNESS - 1) * 2) / 2.0
        val edgeDeg = 2.0 * Math.toDegrees(kotlin.math.atan(spanPx / scale / 2.0))
        // 高度 40° で水平に振ると、方位の変化は角距離より大きくなる（cos で割る）
        val altitude = 40.0
        val deltaAz = 2.0 * Math.toDegrees(
            kotlin.math.asin(
                kotlin.math.sin(edgeDeg * RAD) / kotlin.math.cos(altitude * RAD),
            ),
        )
        val recovered = FovPattern.fovFromEdgeAlignment(
            Look(200.0, altitude),
            Look(200.0 + deltaAz, altitude),
        )
        assertEquals("方位の差ではなく角距離で計算している", trueFov, recovered, 0.4)
    }

    @Test
    fun `印の間隔から画角が出る`() {
        assertEquals(35.0, FovPattern.fovDeg(markSpacingMeters = 1.261, wallDistanceMeters = 2.0), 0.02)
        assertEquals(22.4, FovPattern.fovDeg(markSpacingMeters = 0.792, wallDistanceMeters = 2.0), 0.05)
    }
}
