package jp.jig.glasses.sample.kmp.ai

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * 実機で「AI が的外れな解説を喋る」ときに、送っていないのか読めていないのかを分けるためのテスト。
 * HTTP は叩かない（本文の組み立てと応答のパースだけを見る）。
 */
class OpenAiClientTest {

    private val request = ExplainRequest(
        constellations = listOf("さそり座", "てんびん座"),
        latDeg = 35.9432,
        lonDeg = 136.1846,
        azDeg = 187.0,
        altDeg = 42.0,
        localTime = "2026-08-20 21:34 JST",
        pngBase64 = "iVBORw0KGgo=",
    )

    @Test
    fun `星座名と観測条件がすべて本文に入る`() {
        val text = OpenAiClient.buildRequestBody("gpt-4o", request).toString()
        // どれか 1 つでも落ちると、AI は空を推測で語ることになる
        for (needle in listOf("さそり座", "てんびん座", "35.943", "136.185", "187", "42", "2026-08-20 21:34 JST")) {
            assertTrue("本文に $needle が入っていない", needle in text)
        }
    }

    @Test
    fun `画像はdataURLとして添付される`() {
        val body = OpenAiClient.buildRequestBody("gpt-4o", request)
        val content = body.getJSONArray("messages")
            .getJSONObject(1)
            .getJSONArray("content")
        val image = (0 until content.length())
            .map { content.getJSONObject(it) }
            .single { it.getString("type") == "image_url" }
        assertEquals(
            "data:image/png;base64,iVBORw0KGgo=",
            image.getJSONObject("image_url").getString("url"),
        )
    }

    @Test
    fun `画像が無くても本文は組み立つ`() {
        // 星図をまだ送っていないうちにタップされたときの経路
        val body = OpenAiClient.buildRequestBody("gpt-4o", request.copy(pngBase64 = null))
        val content = body.getJSONArray("messages").getJSONObject(1).getJSONArray("content")
        assertEquals(1, content.length())
        assertEquals("text", content.getJSONObject(0).getString("type"))
    }

    @Test
    fun `モデル名はそのまま載る`() {
        assertEquals("gpt-4o", OpenAiClient.buildRequestBody("gpt-4o", request).getString("model"))
    }

    @Test
    fun `応答から本文を取り出せる`() {
        val json = JSONObject()
            .put(
                "choices",
                org.json.JSONArray().put(
                    JSONObject().put(
                        "message",
                        JSONObject().put("content", "  さそり座は南の低い空に見えます。  "),
                    ),
                ),
            )
            .toString()
        assertEquals("さそり座は南の低い空に見えます。", OpenAiClient.parseReply(json))
    }

    @Test(expected = IOException::class)
    fun `空の応答は例外にする`() {
        // 黙って空文字を喋らせると「無反応」と区別が付かない
        OpenAiClient.parseReply("""{"choices":[{"message":{"content":"  "}}]}""")
    }

    @Test(expected = IOException::class)
    fun `choicesが無ければ例外にする`() {
        OpenAiClient.parseReply("""{"error":{"message":"nope"}}""")
    }

    @Test
    fun `エラー応答は理由まで読める`() {
        val message = OpenAiClient.errorMessage(
            401,
            """{"error":{"message":"Incorrect API key provided"}}""",
        )
        assertEquals("HTTP 401: Incorrect API key provided", message)
        // 本文が JSON でないこともある（プロキシに落とされたときなど）
        assertEquals("HTTP 401: API キーが違う", OpenAiClient.errorMessage(401, "<html>"))
        assertEquals("HTTP 503", OpenAiClient.errorMessage(503, ""))
    }

    @Test
    fun `キーが無ければ設定済みと見なさない`() {
        assertTrue(OpenAiClient("", "gpt-4o").configured.not())
        assertTrue(OpenAiClient("sk-x", "gpt-4o").configured)
    }
}
