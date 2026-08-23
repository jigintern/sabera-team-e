package jp.jig.glasses.sample.kmp.glass

import app.jigglass.glass.CommandManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassTextPageTest {

    @Test
    fun `1枚がパネルに収まる`() {
        val page = GlassTextPage.explanation("おとめ座 南南西 45°", "あ".repeat(GlassTextPage.headerBodyChars))

        assertEquals("見出し 1 行＋本文", GlassTextPage.ROWS, page.elements.size)
        assertEquals("入り切らない文字は無い", 0, page.dropped)
        for (element in page.elements) {
            assertTrue("id は 0..7", element.id in 0 until CANVAS_TEXT_SLOTS)
            assertTrue("右端を超えた ${element.x + element.width}", element.x + element.width <= PANEL_WIDTH)
            assertTrue("下端を超えた ${element.y + element.height}", element.y + element.height <= PANEL_HEIGHT)
        }
    }

    /**
     * **1 枚は必ず 1 電文で送り切れる。**
     *
     * 8 行ぶん置いたときは、実機で**最初の 1〜2 行が消えて途中から始まった**。
     * 190 バイトが「1 電文あたり」なのか「画面に置ける合計」なのかは分からないままなので、
     * **どちらの読み方でも壊れない量**に収める。
     */
    @Test
    fun `1枚は1電文に収まる`() {
        val page = GlassTextPage.explanation("おとめ座 南南西 45°", "あ".repeat(GlassTextPage.headerBodyChars))

        val batches = page.elements.updatesFrom(emptyList())
        assertEquals("1 電文で送り切れていない", 1, batches.size)
        val bytes = page.elements.sumOf { it.byteSize() }
        assertTrue("画面に置く合計が $bytes バイト", bytes <= CANVAS_TEXT_BUDGET_BYTES)
    }

    /**
     * **日本語で埋まった 1 枚も 1 電文に収まる。**
     *
     * 1 行の文字数を画素だけで決めていたときは 19 文字になり、日本語の 3 行が
     * **207 バイトで 2 電文に割れた**。実機では後から送ったぶんに先の行が押し出されて、
     * **解説が途中の行から始まったり末尾が出なかったりする**（190 バイトは画面の合計でもある）。
     */
    @Test
    fun `日本語で埋め尽くしても1電文に収まる`() {
        val page = GlassTextPage.explanation(
            "あ".repeat(GlassTextPage.lineChars),
            "い".repeat(GlassTextPage.headerBodyChars),
        )

        val bytes = page.elements.sumOf { it.byteSize() }
        assertTrue("画面に置く合計が $bytes バイト", bytes <= CANVAS_TEXT_BUDGET_BYTES)
        assertEquals("1 電文で送り切れていない", 1, page.elements.updatesFrom(emptyList()).size)
    }

    /** いちばん長い星座名（みなみのかんむり座）＋方角でも見出しが切れない */
    @Test
    fun `長い星座名の見出しでも切れない`() {
        val header = "みなみのかんむり座 南南西 30°"

        val page = GlassTextPage.explanation(header, "い".repeat(GlassTextPage.headerBodyChars))

        assertEquals("見出しが切れた", header, page.elements.first().text)
        assertEquals("1 電文で送り切れていない", 1, page.elements.updatesFrom(emptyList()).size)
    }

    /** 入り切らない本文は捨てずに流す */
    @Test
    fun `長い解説は流して全部出す`() {
        val body = "これは長い解説の文です。".repeat(8)
        val pages = GlassTextPage.pages("おとめ座 南南西 45°", body)

        assertTrue("流れていない", pages.size > 1)
        assertEquals("捨てている", 0, pages.last().dropped)
        // 1 枚目は見出し＋本文 2 行。2 枚目からは下へ入った 1 行だけが新しい
        val shown = buildString {
            append(pages.first().elements.drop(1).joinToString("") { it.text })
            for (page in pages.drop(1)) append(page.elements.last().text)
        }
        assertEquals("文字が抜けた", body.replace("　", ""), shown)
        for (page in pages) {
            assertEquals("1 電文で送り切れていない", 1, page.elements.updatesFrom(emptyList()).size)
            assertTrue(
                "1 枚が ${page.elements.sumOf { it.byteSize() }} バイト",
                page.elements.sumOf { it.byteSize() } <= CANVAS_TEXT_BUDGET_BYTES,
            )
        }
    }

    /**
     * **見出しは 1 枚目だけ。** 出したままだと本文が 2 行しか置けず、
     * 一度に目に入る量が少なすぎる（行を増やしても入る文字は増えないので、
     * 増やせるのは見出しを畳んだぶんだけ）。
     */
    @Test
    fun `2枚目からは見出しを畳んで本文3行になる`() {
        val header = "おとめ座 南南西 45°"

        val pages = GlassTextPage.pages(header, "これは長い解説の文です。".repeat(8))

        assertEquals("1 枚目に見出しが無い", header, pages.first().elements.first().text)
        assertEquals("1 枚目の本文が 2 行でない", GlassTextPage.ROWS, pages.first().elements.size)
        for (page in pages.drop(1)) {
            assertEquals("本文が 3 行になっていない", GlassTextPage.BODY_ROWS, page.elements.size)
            assertTrue("見出しが残っている", page.elements.none { it.text == header })
        }
    }

    /**
     * **1 行ずつ上へ流す。**
     *
     * 2 行まとめて入れ替えると 2 行とも新しくなり、どこから読めばよいか分からなくなる。
     * 下の行がそのまま上へ来れば、読みかけの行を目で追い続けられる。
     */
    @Test
    fun `次の1枚では下の行が上へ上がる`() {
        val pages = GlassTextPage.pages("おとめ座", "これは長い解説の文です。".repeat(8))

        for ((previous, next) in pages.zipWithNext()) {
            val before = previous.elements.map { it.text }
            val after = next.elements.map { it.text }
            assertEquals("下の行が上へ来ていない", before.takeLast(2), after.dropLast(1))
        }
    }

    /** めくる速さの根拠。**新しく出た文字数**が枚ごとに分かる */
    @Test
    fun `新しく出た文字数は1枚目だけ2行ぶん`() {
        val pages = GlassTextPage.pages("おとめ座", "これは長い解説の文です。".repeat(8))

        val first = pages.first()
        assertEquals(
            "1 枚目は見出しを除く 2 行が新しい",
            first.elements.drop(1).sumOf { it.text.length },
            first.revealed,
        )
        for (page in pages.drop(1)) {
            assertEquals(
                "2 枚目からは下の 1 行だけが新しい",
                page.elements.last().text.length,
                page.revealed,
            )
        }
    }

    /**
     * **文字が届いても前の行は動かない。**
     *
     * ファームは新しい矩形しか描き直さないので、行が組み変わると前の行の末尾が残る
     * （実機で踏んだ「る」問題）。読む側から見ても、読んでいる最中に行が動くと追えない。
     */
    @Test
    fun `あとから文字が届いても前の行の位置と内容は変わらない`() {
        val full = "おとめ座は春の空にひろがる、いちばん大きな星座です。" +
            "いちばん明るいスピカは、青白く光る一等星です。"
        var previous = emptyList<CommandManager.CanvasElement>()
        for (length in 1..full.length) {
            val elements = GlassTextPage.explanation("おとめ座", full.take(length)).elements
            for (old in previous) {
                val now = elements.firstOrNull { it.id == old.id } ?: continue
                assertEquals("行が動いた id=${old.id}", old.y, now.y)
                assertEquals("行がずれた id=${old.id}", old.x, now.x)
                assertTrue(
                    "行が縮んだ id=${old.id} 「${old.text}」→「${now.text}」",
                    now.text.startsWith(old.text),
                )
            }
            previous = elements
        }
    }

    @Test
    fun `折り返しは1行の文字数を超えない`() {
        val lines = GlassTextPage.wrap("あ".repeat(100))

        assertTrue("行が長すぎる ${lines.map { it.length }}", lines.all { it.length <= GlassTextPage.lineChars })
        assertEquals("最初の行が埋まっていない", GlassTextPage.lineChars, lines.first().length)
        assertEquals("文字が消えている", 100, lines.sumOf { it.length })
    }

    /** 行頭に句点が来ると読みにくい。前の行へ吸わせる（前の行は伸びるだけなので消え残らない） */
    @Test
    fun `句点は行頭に置かない`() {
        val lines = GlassTextPage.wrap("あ".repeat(GlassTextPage.lineChars) + "。つづき")

        assertTrue("句点が行頭に来た", lines[0].endsWith("。"))
        assertEquals("つづき", lines[1])
    }

    @Test
    fun `1枚に入り切らない文字は捨てて数える`() {
        val body = "い".repeat(GlassTextPage.headerBodyChars + 40)
        val page = GlassTextPage.explanation("おとめ座", body)

        assertEquals(GlassTextPage.ROWS, page.elements.size)
        assertEquals(40, page.dropped)
    }

    /**
     * **見出しが無くても本文は繰り上がらない。**
     *
     * タップした直後は星座名がまだ決まっていないことがある。繰り上げると、
     * 名前が決まった瞬間に本文が 1 行ぶん下がって全部書き直しになる。
     */
    @Test
    fun `見出しが無くても本文は同じ行から始まる`() {
        val withHeader = GlassTextPage.explanation("おとめ座", "みなみのかんむり座のあたりです。")
        val without = GlassTextPage.explanation("", "みなみのかんむり座のあたりです。")

        assertEquals(1, without.elements.size)
        assertEquals("本文が繰り上がった", 1, without.elements.first().id)
        assertEquals(
            "見出しの有無で本文の位置が変わった",
            withHeader.elements.first { it.id == 1 }.y,
            without.elements.first().y,
        )
    }

    /** 変わっていない行を送り直さない。1 文届くたびに 8 行送ると、そのぶん BLE を埋める */
    @Test
    fun `変わった行だけを送る`() {
        val before = GlassTextPage.explanation("おとめ座", "はるのそらに").elements
        val after = GlassTextPage.explanation("おとめ座", "はるのそらにひろがる").elements

        val sent = after.updatesFrom(before).flatten()

        assertEquals("送るのは伸びた行だけ", 1, sent.size)
        assertEquals("はるのそらにひろがる", sent.single().text)
        assertNotEquals("見出しまで送り直している", 0, sent.single().id)
    }

    /** 短い行に差し替えるときは、前の矩形を先に消す。消さないと末尾が残る（「る」問題） */
    @Test
    fun `短くなる行は先に消す`() {
        val before = GlassTextPage.explanation("おとめ座", "ながいほんぶんがはいっている").elements
        val after = GlassTextPage.explanation("おとめ座", "みじかい").elements

        val batches = after.updatesFrom(before)

        val cleared = batches.first()
        assertTrue("消す電文が先に来ていない", cleared.all { it.text.isEmpty() })
        assertTrue("短くなった行を消していない", cleared.any { it.id == 1 })
    }
}
