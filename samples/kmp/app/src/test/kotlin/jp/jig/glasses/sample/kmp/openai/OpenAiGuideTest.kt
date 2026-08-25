package jp.jig.glasses.sample.kmp.openai

import jp.jig.glasses.sample.kmp.glass.GlassTextPage
import jp.jig.glasses.sample.kmp.guide.GuideOrigin
import jp.jig.glasses.sample.kmp.guide.GuideTheme
import jp.jig.glasses.sample.kmp.sky.GuidanceTarget
import jp.jig.glasses.sample.kmp.sky.GuidanceTargetKind
import jp.jig.glasses.sample.kmp.sky.Look
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ガイドの文面を書かせる経路（#59 の次）。**返ってきた文は必ず端末が検査する。**
 *
 * ここが素通りすると、読み上げで「※」や「1.」がそのまま読まれ、
 * グラスの解説画面ではめくり切れずに尻切れになる。
 */
class OpenAiGuideTest {

    private val targets = listOf(
        GuidanceTarget("c:1", "さそり座", GuidanceTargetKind.CONSTELLATION, Look(180.0, 50.0)),
    )

    private fun guide(endpoint: String) =
        OpenAiGuide(apiKey = "k", model = "m", endpoint = endpoint)

    private fun chatBody(content: String): ByteArray {
        val escaped = content.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        return """{"choices":[{"message":{"content":"$escaped"}}]}""".toByteArray(Charsets.UTF_8)
    }

    @Test
    fun `返ってきた台本を使う`() {
        val content = """{"steps":[{"name":"さそり座","intro":"ここからが主役です。","body":"毒針を持つさそりの姿です。"}]}"""
        TestHttpServer { TestHttpResponse(200, chatBody(content)) }.use { server ->
            val written = guide(server.endpoint).write(GuideTheme.TONIGHT, targets, 0L, "g")

            assertEquals(GuideOrigin.IMPROMPTU_AI, written?.origin)
            assertEquals("ここからが主役です。", written?.steps?.single()?.intro)
            assertEquals("毒針を持つさそりの姿です。", written?.steps?.single()?.body)
        }
    }

    /** **読み上げると記号はそのまま読まれる。** 落として、残らなければ台本ごと捨てる */
    @Test
    fun `記号と箇条書きを落とす`() {
        val parsed = guide("http://127.0.0.1:1/x").parse(
            """{"steps":[{"name":"さそり座","intro":"- ここから*主役*です。","body":"※毒針を持つ姿です。"}]}""",
        )

        assertEquals("ここから主役です。", parsed?.get("さそり座")?.first)
        assertEquals("毒針を持つ姿です。", parsed?.get("さそり座")?.second)
    }

    /**
     * **長すぎる本文はグラスの解説画面をめくり切れない。**
     *
     * 端末が文の切れ目まで切るので、[GlassTextPage.pagedChars]（16 行ぶん）に必ず収まる。
     * 途中で切ると言い差しになって落ち着かないので、**捨てずに切る**。
     */
    @Test
    fun `長すぎる本文はグラスに入る長さへ切る`() {
        val long = "毒針を持つさそりの姿です。".repeat(40)
        val body = guide("http://127.0.0.1:1/x").parse(
            """{"steps":[{"name":"さそり座","intro":"主役です。","body":"$long"}]}""",
        )?.get("さそり座")?.second

        assertNotNull(body)
        assertTrue(body!!.length <= GlassTextPage.pagedChars)
        // **言い差しにしない。** 文の切れ目で切る
        assertTrue(body.endsWith("。"))
    }

    /** **半分だけ AI の台本にしない**（途中で口調が変わって別人が喋り出したように聞こえる） */
    @Test
    fun `星座が欠けていたら台本ごと捨てる`() {
        val content = """{"steps":[{"name":"いて座","intro":"主役です。","body":"弓の名手です。"}]}"""
        TestHttpServer { TestHttpResponse(200, chatBody(content)) }.use { server ->
            assertNull(guide(server.endpoint).write(GuideTheme.TONIGHT, targets, 0L, "g"))
        }
    }

    /** JSON でない返事・空の返事は使わない（呼ぶ側が同梱へ落ちる） */
    @Test
    fun `壊れた返事は使わない`() {
        val writer = guide("http://127.0.0.1:1/x")
        assertNull(writer.parse("これは JSON ではない"))
        assertNull(writer.parse("""{"steps":[]}"""))
        assertNull(writer.parse("""{"steps":[{"name":"さそり座","intro":"","body":""}]}"""))
        assertNotNull(writer.parse("""{"steps":[{"name":"さそり座","intro":"主役です。","body":"姿です。"}]}"""))
    }
}
