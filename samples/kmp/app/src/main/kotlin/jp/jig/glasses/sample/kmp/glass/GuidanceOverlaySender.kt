package jp.jig.glasses.sample.kmp.glass

import app.jigglass.glass.CommandManager
import jp.jig.glasses.sample.kmp.sky.GuidanceFrame
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 案内の小画像に固有な座標・転送待ち・後片付けを画面から隠す。 */
class GuidanceOverlaySender(
    private val commandManager: CommandManager,
    private val sendGate: Mutex,
    private val packetMs: Long,
) {
    /**
     * 前に出した枠の大きさ。
     *
     * **枠が変わるときは先に消す。** 左右の矢印（120×56）と上下の矢印（56×120）と
     * 到着の輪（80×80）は大きさも位置も違うので、同じ id へ重ねると
     * **前の枠のはみ出したぶんが残る**（テキストで踏んだ「消え残る」と同じ理屈）。
     */
    private var shownBox: GuidanceIndicatorBox? = null

    /** 他の画像が転送中なら古いフレームを待たせず捨てる。 */
    suspend fun send(frame: GuidanceFrame): Boolean {
        if (!sendGate.tryLock()) return false
        try {
            val overlay = guidanceOverlay(frame)
            val box = GuidanceIndicatorBox(overlay.width, overlay.height)
            if (shownBox != null && shownBox != box) removeWhileLocked()
            commandManager.sendCanvasImage(
                id = GUIDANCE_OVERLAY_IMAGE_ID,
                x = (PANEL_WIDTH - overlay.width) / 2,
                y = (PANEL_HEIGHT - overlay.height) / 2,
                width = overlay.width,
                height = overlay.height,
                grayscale = overlay.gray,
            )
            shownBox = box
            val packets = (overlay.compressedBytes + CANVAS_PACKET_BYTES - 1) / CANVAS_PACKET_BYTES
            delay(packets * packetMs)
            return true
        } finally {
            sendGate.unlock()
        }
    }

    /** 画面を抜けるキャンセル中でも、前の案内表示を残さない。 */
    suspend fun remove() {
        sendGate.withLock {
            removeWhileLocked()
        }
    }

    /** 星図などと同じロック内でまとめて消すときに使う。 */
    suspend fun removeWhileLocked() {
        withContext(NonCancellable) {
            commandManager.removeCanvasImage(GUIDANCE_OVERLAY_IMAGE_ID)
            shownBox = null
        }
    }
}
