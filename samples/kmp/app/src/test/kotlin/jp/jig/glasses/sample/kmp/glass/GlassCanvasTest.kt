package jp.jig.glasses.sample.kmp.glass

import app.jigglass.glass.CommandManager
import jp.jig.glasses.sample.kmp.sky.GuidanceDirection
import jp.jig.glasses.sample.kmp.sky.GuidanceFrame
import jp.jig.glasses.sample.kmp.sky.GuidanceStage
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
        ).withStatusLabel("シドニー 8/24 20:30")

        val elements = map.toCanvasElements()
        assertEquals("シドニー 8/24 20:30", elements.first().text)
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

    @Test
    fun `画面切り替えの掃除は全スロットを一電文で消す`() {
        val cleared = clearedCanvasText()

        assertEquals((0 until CANVAS_TEXT_SLOTS).toList(), cleared.map { it.id })
        assertTrue(cleared.all { it.text.isEmpty() && it.width == 0 && it.height == 0 })
        assertTrue(cleared.sumOf { it.byteSize() } <= CANVAS_TEXT_BUDGET_BYTES)
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

    /**
     * **190 バイトは画面に置ける合計でもある**（#40）。
     *
     * 8 枠まで数だけで詰めていたときは、日本語の名前 8 個で 200 バイトを超え、
     * [batched] が 2 電文へ割ったところで**先に置いた枠が押し出されて消えた**。
     * 先頭は案内のラベルなので、いちばん消えてはいけない文字が消える。
     */
    @Test
    fun `テキスト枠は数だけでなくバイト数でも打ち切る`() {
        val labels = (0 until CANVAS_TEXT_SLOTS).map { index ->
            Label("みなみのかんむり座", 60 + index, 20 + index * CANVAS_LABEL_HEIGHT, LabelKind.CONSTELLATION)
        }
        val elements = StarMap(528, 330, ByteArray(528 * 330), labels).toCanvasElements()

        assertTrue("枠が多すぎる", elements.size <= CANVAS_TEXT_SLOTS)
        assertTrue(
            "合計 ${elements.sumOf { it.byteSize() }} バイトは画面に置ける量を超える",
            elements.sumOf { it.byteSize() } <= CANVAS_TEXT_BUDGET_BYTES,
        )
        // 優先順位の先頭（案内・衛星）から詰めるので、残るのは先頭側であること
        assertEquals(0, elements.first().id)
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

    /**
     * **札を載せても圧縮後サイズは変わらない。**
     *
     * `drawAndSend()` は 18 万画素の走査を 1 枚につき 1 回に抑えるため、**焼いた直後に数えた値を
     * 札を載せたあとまで回している**。`withStatusLabel` / `withGuidanceLabel` が `gray` を
     * 作り直すようになると、その前提が黙って崩れて上限判定が狂う（#142）。
     */
    @Test
    fun `札を載せても圧縮後サイズは変わらない`() {
        // 真っ黒だと走長が最大で差が出ないので、点を散らして走長を刻む
        val gray = ByteArray(528 * 330)
        for (i in gray.indices step 7) gray[i] = 0xE0.toByte()
        val bare = StarMap(528, 330, gray, listOf(Label("オリオン座", 264, 120)))
        val before = bare.compressedSizeBytes()

        val withStatus = bare.withStatusLabel("シドニー 8/24 20:30")
        assertEquals("再現ラベルで圧縮後サイズが変わった", before, withStatus.compressedSizeBytes())

        val withGuidance = withStatus.withGuidanceLabel(
            GuidanceFrame(
                targetName = "目標座",
                distanceDeg = 32.0,
                stage = GuidanceStage.HORIZONTAL,
                direction = GuidanceDirection.LEFT,
                horizontalErrorDeg = -31.6,
                verticalErrorDeg = 5.0,
                near = false,
            ),
        )
        assertEquals("案内ラベルで圧縮後サイズが変わった", before, withGuidance.compressedSizeBytes())
        assertEquals(
            "バッファ使用量が変わった",
            bare.canvasBufferUsageBytes(before),
            withGuidance.canvasBufferUsageBytes(before),
        )
    }
}
