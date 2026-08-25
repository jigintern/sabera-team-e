package jp.jig.glasses.sample.kmp.glass

import jp.jig.glasses.sample.kmp.sky.GuidanceDirection
import jp.jig.glasses.sample.kmp.sky.GuidanceFrame
import jp.jig.glasses.sample.kmp.sky.GuidanceStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GuidanceOverlayTest {
    @Test
    fun `案内表示は星図と足してもバッファに収まる`() {
        val arrow = guidanceOverlay(frame(GuidanceDirection.RIGHT))
        val arrived = guidanceOverlay(arrivedFrame())
        // 星図（528×330）と足して 380,000 バイトに収まらないと、星図ごと送れなくなる
        val starMap = STAR_MAP_WIDTH * STAR_MAP_HEIGHT * 2

        assertTrue(arrow.gray.any { (it.toInt() and 0xff) > 0 })
        assertTrue(starMap + arrow.bufferUsageBytes < CANVAS_IMAGE_BUFFER_BYTES)
        assertTrue(starMap + arrived.bufferUsageBytes < CANVAS_IMAGE_BUFFER_BYTES)
    }

    @Test
    fun `矢印の枠は軸に沿って細長く、到着の輪は正方形になる`() {
        val right = guidanceOverlay(frame(GuidanceDirection.RIGHT))
        val up = guidanceOverlay(frame(GuidanceDirection.UP))
        val arrived = guidanceOverlay(arrivedFrame())

        assertEquals(GUIDANCE_ARROW_LONG_PX, right.width)
        assertEquals(GUIDANCE_ARROW_SHORT_PX, right.height)
        assertEquals(GUIDANCE_ARROW_SHORT_PX, up.width)
        assertEquals(GUIDANCE_ARROW_LONG_PX, up.height)
        assertEquals(GUIDANCE_ARRIVAL_SIZE_PX, arrived.width)
        assertEquals(GUIDANCE_ARRIVAL_SIZE_PX, arrived.height)
    }

    @Test
    fun `到着時は方向表示とは異なる二重リングになる`() {
        val direction = guidanceOverlay(frame(GuidanceDirection.UP, distanceDeg = 8.0))
        val arrived = guidanceOverlay(arrivedFrame())

        assertTrue(!direction.gray.contentEquals(arrived.gray))
    }

    @Test
    fun `上下左右は共通形状から軸に沿った方向表示になる`() {
        val right = guidanceIndicatorGeometry(frame(GuidanceDirection.RIGHT)) as GuidanceIndicatorGeometry.Arrow
        val left = guidanceIndicatorGeometry(frame(GuidanceDirection.LEFT)) as GuidanceIndicatorGeometry.Arrow
        val up = guidanceIndicatorGeometry(frame(GuidanceDirection.UP)) as GuidanceIndicatorGeometry.Arrow
        val down = guidanceIndicatorGeometry(frame(GuidanceDirection.DOWN)) as GuidanceIndicatorGeometry.Arrow

        assertTrue(right.tip.x > right.tail.x)
        assertTrue(left.tip.x < left.tail.x)
        assertTrue(up.tip.y < up.tail.y)
        assertTrue(down.tip.y > down.tail.y)
        assertEquals(0.0, right.tip.y, 0.001)
        assertEquals(0.0, up.tip.x, 0.001)
        assertEquals("頭の三角は底辺2点", 2, right.headBase.size)
        // 頭は先端より後ろへ開く。ここが逆だと矢印が目標と反対を指す
        assertTrue(right.headBase.all { it.x < right.tip.x })
        assertTrue(up.headBase.all { it.y > up.tip.y })
    }

    @Test
    fun `矢印は細線ではなく面で塗る`() {
        val overlay = guidanceOverlay(frame(GuidanceDirection.RIGHT))
        val lit = overlay.gray.count { (it.toInt() and 0xff) > 0 }
        // 軸（120px × 太さ13px）と頭で 1,000px を超える。旧シェブロン＋点は 200px 未満だった
        assertTrue("塗った面積が足りない: $lit", lit > 1_000)
        // 中間の階調は屋外で消えるので、最上段しか使わない
        assertTrue(overlay.gray.all { (it.toInt() and 0xff).let { v -> v == 0 || v == 255 } })
    }

    @Test
    fun `焼いた画像でも矢印が指定した側へ出る`() {
        val right = litCentroid(guidanceOverlay(frame(GuidanceDirection.RIGHT)))
        val left = litCentroid(guidanceOverlay(frame(GuidanceDirection.LEFT)))
        val up = litCentroid(guidanceOverlay(frame(GuidanceDirection.UP)))
        val down = litCentroid(guidanceOverlay(frame(GuidanceDirection.DOWN)))

        // 頭の三角が塗られているぶん、点灯の重心は必ず向いている側へ寄る
        assertTrue(right.first > GUIDANCE_ARROW_LONG_PX / 2.0)
        assertTrue(left.first < GUIDANCE_ARROW_LONG_PX / 2.0)
        assertTrue(up.second < GUIDANCE_ARROW_LONG_PX / 2.0)
        assertTrue(down.second > GUIDANCE_ARROW_LONG_PX / 2.0)
    }

    @Test
    fun `十度以内では同じ方向の矢印が短くなる`() {
        val far = guidanceIndicatorGeometry(
            frame(GuidanceDirection.RIGHT, distanceDeg = 20.0),
        ) as GuidanceIndicatorGeometry.Arrow
        val near = guidanceIndicatorGeometry(
            frame(GuidanceDirection.RIGHT, distanceDeg = 8.0),
        ) as GuidanceIndicatorGeometry.Arrow

        assertTrue(near.tip.x - near.tail.x < far.tip.x - far.tail.x)
        // **近いほど細くはしない。** 近づくほど見えにくくなっては案内にならない
        assertEquals(far.shaftHalfWidth, near.shaftHalfWidth, 0.0)
    }

    @Test
    fun `方向の無い到着前のフレームでも落ちない`() {
        val broken = arrivedFrame().copy(stage = GuidanceStage.VERTICAL, direction = null)

        assertTrue(guidanceIndicatorGeometry(broken) is GuidanceIndicatorGeometry.Arrival)
        assertTrue(guidanceOverlay(broken).gray.any { (it.toInt() and 0xff) > 0 })
    }

    private fun frame(direction: GuidanceDirection, distanceDeg: Double = 30.0): GuidanceFrame {
        val stage = when (direction) {
            GuidanceDirection.LEFT, GuidanceDirection.RIGHT -> GuidanceStage.HORIZONTAL
            GuidanceDirection.UP, GuidanceDirection.DOWN -> GuidanceStage.VERTICAL
        }
        return GuidanceFrame(
            targetName = "ベガ",
            distanceDeg = distanceDeg,
            stage = stage,
            direction = direction,
            horizontalErrorDeg = when (direction) {
                GuidanceDirection.LEFT -> -distanceDeg
                GuidanceDirection.RIGHT -> distanceDeg
                else -> 0.0
            },
            verticalErrorDeg = when (direction) {
                GuidanceDirection.UP -> distanceDeg
                GuidanceDirection.DOWN -> -distanceDeg
                else -> 0.0
            },
            near = distanceDeg <= 10.0,
        )
    }

    private fun arrivedFrame() = GuidanceFrame(
        targetName = "ベガ",
        distanceDeg = 3.0,
        stage = GuidanceStage.ARRIVED,
        direction = null,
        horizontalErrorDeg = 0.0,
        verticalErrorDeg = 0.0,
        near = true,
    )

    private fun litCentroid(overlay: GuidanceOverlay): Pair<Double, Double> {
        var sumX = 0.0
        var sumY = 0.0
        var count = 0
        overlay.gray.forEachIndexed { index, value ->
            if ((value.toInt() and 0xff) == 0) return@forEachIndexed
            sumX += index % overlay.width
            sumY += index / overlay.width
            count++
        }
        return sumX / count to sumY / count
    }
}
