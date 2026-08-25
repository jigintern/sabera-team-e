package jp.jig.glasses.sample.kmp.glass

import app.jigglass.glass.CommandManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassCanvasTest {
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
    fun `シミュレーション条件を先頭の枠へ常時置く`() {
        val map = StarMap(
            528,
            330,
            ByteArray(528 * 330),
            listOf(Label("オリオン座", 264, 120)),
        ).withStatusLabel("シミュレーション シドニー 8/24 20:30")

        val elements = map.toCanvasElements()
        assertEquals("シミュレーション シドニー 8/24 20:30", elements.first().text)
        assertEquals(0, elements.first().id)
        assertEquals(270, map.labels.first().y)
        assertEquals(listOf("オリオン座"), map.constellationNames())
    }

    @Test
    fun `テキスト要素はSDKの上限内に分割し古いidを消す`() {
        val elements = (0 until 3).map { id ->
            CommandManager.CanvasElement(id, 0, id * 40, 100, 40, "あ".repeat(30))
        }
        val previous = (0 until 5).map { id ->
            CommandManager.CanvasElement(id, 0, id * 40, 100, 40, "い".repeat(30))
        }
        val batches = elements.batched(previous)

        assertTrue(batches.all { batch -> batch.sumOf { it.byteSize() } <= CANVAS_TEXT_BUDGET_BYTES })
        assertEquals(listOf(3, 4), batches.flatten().filter { it.text.isEmpty() }.map { it.id })
    }

    @Test
    fun `表示対象がなくなったら全テキストスロットを消す`() {
        val previous = (0 until CANVAS_TEXT_SLOTS).map { id ->
            CommandManager.CanvasElement(id, 0, id * 40, 100, 40, "みずがめ座")
        }
        val batches = emptyList<CommandManager.CanvasElement>().batched(previous)

        assertEquals((0 until CANVAS_TEXT_SLOTS).toList(), batches.flatten().map { it.id })
        assertTrue(batches.flatten().all { it.text.isEmpty() })
    }

    /**
     * **短い名前へ変わったスロットは、置く前に消す。**
     *
     * ファームは新しい矩形しか描き直さないので、消さずに置くと前の名前の末尾が残る
     * （実機で「ケンタウルス座」→「おとめ座」に変わったあと、右上に「る」が残った）。
     */
    @Test
    fun `短い名前に変わったスロットは先に消してから置く`() {
        val previous = listOf(CommandManager.CanvasElement(0, 300, 20, 200, 40, "ケンタウルス座"))
        val next = listOf(CommandManager.CanvasElement(0, 300, 20, 120, 40, "おとめ座"))

        val batches = next.batched(previous)

        // 消す電文が、置く電文より先に来ていること
        assertEquals(2, batches.size)
        assertEquals(listOf(0), batches[0].map { it.id })
        assertTrue("先に消していない", batches[0].single().text.isEmpty())
        assertEquals(listOf("おとめ座"), batches[1].map { it.text })
    }

    /** 前の矩形を覆うなら消さない。毎フレーム消すと衛星の印が 1.5 秒ごとにちらつく */
    @Test
    fun `前の矩形を覆う場合は消さずに上書きする`() {
        val previous = listOf(CommandManager.CanvasElement(0, 300, 20, 120, 40, "おとめ座"))
        val next = listOf(CommandManager.CanvasElement(0, 290, 20, 200, 40, "ケンタウルス座"))

        val batches = next.batched(previous)

        assertEquals(1, batches.size)
        assertEquals(listOf("ケンタウルス座"), batches.single().map { it.text })
    }
}
