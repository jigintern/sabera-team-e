package jp.jig.glasses.sample.kmp.ui

import jp.jig.glasses.sample.kmp.sky.GuidanceDirection
import jp.jig.glasses.sample.kmp.sky.GuidanceFrame
import jp.jig.glasses.sample.kmp.sky.GuidanceStage
import jp.jig.glasses.sample.kmp.ui.component.guidanceInstruction
import org.junit.Assert.assertEquals
import org.junit.Test

class GuidanceInstructionTest {
    @Test
    fun `左右のあとに上下を案内する文になる`() {
        assertEquals(
            "まず左を向いてください",
            guidanceInstruction(frame(GuidanceStage.HORIZONTAL, GuidanceDirection.LEFT)),
        )
        assertEquals(
            "まず右を向いてください",
            guidanceInstruction(frame(GuidanceStage.HORIZONTAL, GuidanceDirection.RIGHT)),
        )
        assertEquals(
            "次に上を向いてください",
            guidanceInstruction(frame(GuidanceStage.VERTICAL, GuidanceDirection.UP)),
        )
        assertEquals(
            "次に下を向いてください",
            guidanceInstruction(frame(GuidanceStage.VERTICAL, GuidanceDirection.DOWN)),
        )
        assertEquals("このあたりです", guidanceInstruction(frame(GuidanceStage.ARRIVED, null)))
    }

    private fun frame(stage: GuidanceStage, direction: GuidanceDirection?) = GuidanceFrame(
        targetName = "ベガ",
        distanceDeg = 20.0,
        stage = stage,
        direction = direction,
        horizontalErrorDeg = 0.0,
        verticalErrorDeg = 0.0,
        near = false,
    )
}
