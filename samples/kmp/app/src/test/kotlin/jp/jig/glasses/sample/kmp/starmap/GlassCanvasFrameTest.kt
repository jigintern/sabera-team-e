package jp.jig.glasses.sample.kmp.starmap

import app.jigglass.glass.CommandManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassCanvasFrameTest {
    @Test
    fun `同じ3bit値が32画素ごとに1バイトへ圧縮される`() {
        val map = StarMap(64, 1, ByteArray(64), emptyList())
        assertEquals(2, map.compressedSizeBytes())
        assertEquals(130, map.canvasBufferUsageBytes())
    }

    @Test
    fun `重なるラベルは中心に近い先頭だけを残す`() {
        val map = StarMap(
            528,
            330,
            ByteArray(528 * 330),
            listOf(Label("オリオン座", 264, 165), Label("おうし座", 264, 165)),
        )
        assertEquals(listOf("オリオン座"), map.toCanvasElements().map { it.text })
    }

    @Test
    fun `テキスト要素はSDKの上限内に分割し古いidを消す`() {
        val elements = (0 until 3).map { id ->
            CommandManager.CanvasElement(id, 0, id * 40, 100, 40, "あ".repeat(30))
        }
        val batches = elements.batched(previousCount = 5)

        assertTrue(batches.all { batch -> batch.sumOf { it.byteSize() } <= CANVAS_TEXT_BUDGET_BYTES })
        assertEquals(listOf(3, 4), batches.flatten().filter { it.text.isEmpty() }.map { it.id })
    }

    @Test
    fun `表示対象がなくなったら全テキストスロットを消す`() {
        val batches = emptyList<CommandManager.CanvasElement>().batched(previousCount = CANVAS_TEXT_SLOTS)

        assertEquals((0 until CANVAS_TEXT_SLOTS).toList(), batches.flatten().map { it.id })
        assertTrue(batches.flatten().all { it.text.isEmpty() })
    }
}
