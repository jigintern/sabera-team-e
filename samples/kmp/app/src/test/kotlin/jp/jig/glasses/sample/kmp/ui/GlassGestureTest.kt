package jp.jig.glasses.sample.kmp.ui

import app.jigglass.glass.GestureType
import jp.jig.glasses.sample.kmp.glass.GlassPage
import org.junit.Assert.assertEquals
import org.junit.Test

class GlassGestureTest {
    @Test
    fun `9状態と3ジェスチャーの割り当てを固定する`() {
        val cases = listOf(
            Case("読み込み中", state(page = GlassPage.LOADING), none()),
            Case(
                "素の星図",
                state(),
                actions(GlassAction.NONE, GlassAction.START_EXPLANATION, GlassAction.START_VOICE),
            ),
            Case(
                "タイムラプス",
                state(timelapsePlaying = true),
                actions(GlassAction.TIMELAPSE_LAND, GlassAction.START_EXPLANATION, GlassAction.START_VOICE),
            ),
            Case(
                "案内中",
                state(guidanceActive = true),
                actions(GlassAction.STOP_GUIDANCE, GlassAction.START_EXPLANATION, GlassAction.START_VOICE),
            ),
            Case(
                "再現中",
                state(simulating = true),
                actions(GlassAction.RETURN_TO_LIVE, GlassAction.START_EXPLANATION, GlassAction.START_VOICE),
            ),
            Case(
                "解説画面",
                state(page = GlassPage.EXPLANATION),
                actions(GlassAction.LEAVE_EXPLANATION, GlassAction.NONE, GlassAction.START_VOICE),
            ),
            Case(
                "録音中",
                state(page = GlassPage.EXPLANATION, recordingVoice = true, asking = true),
                actions(GlassAction.CANCEL_VOICE, GlassAction.NONE, GlassAction.SUBMIT_VOICE),
            ),
            Case(
                "回答待ち",
                state(page = GlassPage.EXPLANATION, asking = true),
                actions(GlassAction.CANCEL_VOICE, GlassAction.NONE, GlassAction.NONE),
            ),
            // ガイドの案内段では guideRunning と guidanceActive が同時に立つ。
            Case(
                "ガイド中",
                state(guideRunning = true, guidanceActive = true),
                actions(GlassAction.STOP_GUIDE, GlassAction.GUIDE_NEXT, GlassAction.START_VOICE),
            ),
        )

        for (case in cases) {
            for ((gesture, expected) in case.expected) {
                assertEquals("${case.name} / $gesture", expected, glassAction(gesture, case.state))
            }
        }
    }

    @Test
    fun `ガイド中の質問では音声画面の操作を優先する`() {
        val recording = state(
            page = GlassPage.EXPLANATION,
            recordingVoice = true,
            asking = true,
            guideRunning = true,
        )
        val waiting = recording.copy(recordingVoice = false)

        assertEquals(GlassAction.NONE, glassAction(GestureType.DOUBLE_TAP, recording))
        assertEquals(GlassAction.NONE, glassAction(GestureType.DOUBLE_TAP, waiting))
        assertEquals(GlassAction.SUBMIT_VOICE, glassAction(GestureType.HOLD, recording))
        assertEquals(GlassAction.NONE, glassAction(GestureType.HOLD, waiting))
    }

    @Test
    fun `ガイドの解説段でもシングルタップはガイド全体を終了する`() {
        val explanation = state(page = GlassPage.EXPLANATION, guideRunning = true)

        assertEquals(
            GlassAction.STOP_GUIDE,
            glassAction(GestureType.SINGLE_TAP, explanation),
        )
    }

    private data class Case(
        val name: String,
        val state: GlassGestureState,
        val expected: Map<GestureType, GlassAction>,
    )

    private fun state(
        page: GlassPage = GlassPage.STAR_MAP,
        recordingVoice: Boolean = false,
        asking: Boolean = false,
        timelapsePlaying: Boolean = false,
        guidanceActive: Boolean = false,
        guideRunning: Boolean = false,
        simulating: Boolean = false,
    ) = GlassGestureState(
        page = page,
        recordingVoice = recordingVoice,
        asking = asking,
        timelapsePlaying = timelapsePlaying,
        guidanceActive = guidanceActive,
        guideRunning = guideRunning,
        simulating = simulating,
    )

    private fun none() = actions(GlassAction.NONE, GlassAction.NONE, GlassAction.NONE)

    private fun actions(
        single: GlassAction,
        double: GlassAction,
        hold: GlassAction,
    ) = linkedMapOf(
        GestureType.SINGLE_TAP to single,
        GestureType.DOUBLE_TAP to double,
        GestureType.HOLD to hold,
    )
}
