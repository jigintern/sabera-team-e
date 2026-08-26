package jp.jig.glasses.sample.kmp.ui

import app.jigglass.glass.GestureType
import jp.jig.glasses.sample.kmp.glass.GlassPage

/** ジェスチャーを受けて行う操作。画面ごとの割り当ては [glassAction] だけで決める。 */
internal enum class GlassAction {
    NONE,
    TIMELAPSE_LAND,
    STOP_GUIDANCE,
    RETURN_TO_LIVE,
    CANCEL_VOICE,
    LEAVE_EXPLANATION,
    STOP_GUIDE,
    START_EXPLANATION,
    GUIDE_NEXT,
    START_VOICE,
    SUBMIT_VOICE,
}

internal data class GlassGestureState(
    val page: GlassPage,
    val recordingVoice: Boolean,
    val asking: Boolean,
    val timelapsePlaying: Boolean,
    val guidanceActive: Boolean,
    val guideRunning: Boolean,
    val simulating: Boolean,
)

/** 読み込み中は無効。シングルタップは、いま重なっているものを畳んで星図へ戻す。 */
internal fun glassAction(gesture: GestureType, state: GlassGestureState): GlassAction {
    if (state.page == GlassPage.LOADING) return GlassAction.NONE

    return when (gesture) {
        GestureType.SINGLE_TAP -> when {
            state.timelapsePlaying -> GlassAction.TIMELAPSE_LAND
            state.recordingVoice || state.asking -> GlassAction.CANCEL_VOICE
            // 案内段でも解説段でも、ガイド全体を 1 回で終了する。
            state.guideRunning -> GlassAction.STOP_GUIDE
            state.page == GlassPage.EXPLANATION -> GlassAction.LEAVE_EXPLANATION
            state.guidanceActive -> GlassAction.STOP_GUIDANCE
            state.simulating -> GlassAction.RETURN_TO_LIVE
            else -> GlassAction.NONE
        }

        GestureType.DOUBLE_TAP -> when {
            state.recordingVoice || state.asking -> GlassAction.NONE
            state.guideRunning -> GlassAction.GUIDE_NEXT
            state.page == GlassPage.STAR_MAP -> GlassAction.START_EXPLANATION
            else -> GlassAction.NONE
        }

        GestureType.HOLD -> when {
            state.recordingVoice -> GlassAction.SUBMIT_VOICE
            state.asking -> GlassAction.NONE
            else -> GlassAction.START_VOICE
        }
    }
}
