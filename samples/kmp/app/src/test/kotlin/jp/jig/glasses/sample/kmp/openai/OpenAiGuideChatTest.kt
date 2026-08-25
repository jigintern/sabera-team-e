package jp.jig.glasses.sample.kmp.openai

import jp.jig.glasses.sample.kmp.sky.GuidanceTarget
import jp.jig.glasses.sample.kmp.sky.GuidanceTargetKind
import jp.jig.glasses.sample.kmp.sky.Look
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 対話で台本を作らせる経路（toB）。
 *
 * **ここが素通りすると、空に出ていない星座が台本に載る。**
 * 載ると再生時に `GuidePlan` が全部飛ばして「何も起きないガイド」になり、
 * しかも**作った時点では気づけない**（客の前で分かる）。
 */
class OpenAiGuideChatTest {

    private val candidates = listOf(
        GuidanceTarget("c:1", "さそり座", GuidanceTargetKind.CONSTELLATION, Look(180.0, 50.0)),
        GuidanceTarget("c:2", "いて座", GuidanceTargetKind.CONSTELLATION, Look(200.0, 40.0)),
        GuidanceTarget("a:1", "夏の大三角", GuidanceTargetKind.ASTERISM, Look(90.0, 60.0)),
    )

    private val allowed = candidates.associate { it.nameJa to it.kind }

    private fun chat(endpoint: String) =
        OpenAiGuideChat(apiKey = "k", model = "m", endpoint = endpoint)

    private fun chatBody(content: String): ByteArray {
        val escaped = content.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        return """{"choices":[{"message":{"content":"$escaped"}}]}""".toByteArray(Charsets.UTF_8)
    }

    @Test
    fun `返ってきた台本を使う`() {
        val content = """
            {"reply":"夏の空でまとめました。","steps":[
              {"name":"夏の大三角","intro":"まずは目印から。","body":"3 つの明るい星をつないだ形です。"},
              {"name":"さそり座","intro":"南へ向きます。","body":"毒針を持つさそりの姿です。"}
            ]}
        """.trimIndent()
        TestHttpServer { TestHttpResponse(200, chatBody(content)) }.use { server ->
            val reply = chat(server.endpoint).reply("夏の星座で", candidates, emptyList())

            assertEquals("夏の空でまとめました。", reply?.reply)
            assertEquals(listOf("夏の大三角", "さそり座"), reply?.steps?.map { it.targetName })
            // 種別は**端末が持っている候補から引く**（AI の申告を信じない）
            assertEquals(GuidanceTargetKind.ASTERISM, reply?.steps?.first()?.kind)
            assertEquals(0, reply?.dropped)
        }
    }

    /**
     * **4 条件の③がここで効く。** 候補は端末がその日その時間の空から作っていて、
     * AI はその中からしか選べない。
     */
    @Test
    fun `候補に無い星座は捨てる`() {
        val content = """
            {"reply":"入れました。","steps":[
              {"name":"さそり座","intro":"","body":"毒針を持つさそりの姿です。"},
              {"name":"オリオン座","intro":"","body":"冬の狩人です。"}
            ]}
        """.trimIndent()
        val reply = chat("http://unused").parse(content, allowed)

        assertEquals(listOf("さそり座"), reply?.steps?.map { it.targetName })
        // **黙って減らさない。** 何が起きたかは画面に出す
        assertEquals(1, reply?.dropped)
    }

    /** 読み上げると「※」も「1.」もそのまま読まれる（[AskGuard] の検査を通す） */
    @Test
    fun `記号と箇条書きを落とす`() {
        val content = """
            {"reply":"はい。","steps":[
              {"name":"さそり座","intro":"※ここから","body":"1. 毒針を持つさそりの姿です。"}
            ]}
        """.trimIndent()
        val step = chat("http://unused").parse(content, allowed)?.steps?.single()

        assertNotNull(step)
        assertTrue(step!!.body.none { it == '※' || it == '*' })
        assertTrue(step.intro.none { it == '※' })
    }

    /** 台本を変えずに答えただけのときは、いまの段をそのまま残す */
    @Test
    fun `台本を変えない返事は steps を持たない`() {
        val reply = chat("http://unused").parse("""{"reply":"どのくらいの長さにしますか。"}""", allowed)

        assertEquals("どのくらいの長さにしますか。", reply?.reply)
        assertNull(reply?.steps)
    }

    /** 何も残らないなら、台本に触れさせない */
    @Test
    fun `候補が全部落ちたら台本を差し替えない`() {
        val content = """{"reply":"入れました。","steps":[{"name":"オリオン座","intro":"","body":"冬の狩人です。"}]}"""
        val reply = chat("http://unused").parse(content, allowed)

        assertNull(reply?.steps)
        assertEquals(1, reply?.dropped)
    }

    @Test
    fun `壊れた返事は使わない`() {
        assertNull(chat("http://unused").parse("これは JSON ではない", allowed))
        assertNull(chat("http://unused").parse("""{"steps":[]}""", allowed))
    }

    /** 候補が 1 つも無いなら通信しない（**空振りの往復で回数を減らさない**） */
    @Test
    fun `候補が空なら通信しない`() {
        assertNull(chat("http://unused").reply("何か入れて", emptyList(), emptyList()))
    }

    @Test
    fun `通信に失敗したら例外で返す`() {
        TestHttpServer { TestHttpResponse(500, "{}".toByteArray()) }.use { server ->
            val error = runCatching { chat(server.endpoint).reply("夏の星座で", candidates, emptyList()) }
            assertTrue(error.isFailure)
        }
    }
}
