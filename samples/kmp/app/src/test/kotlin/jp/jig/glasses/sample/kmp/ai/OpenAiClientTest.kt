package jp.jig.glasses.sample.kmp.ai

import jp.jig.glasses.sample.kmp.starmap.ObservedStarFact
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
        visibleStars = listOf(ObservedStarFact("アンタレス", 0.96, 187.0, 40.0, 2.1)),
        headingUncertaintyDeg = 1.2,
        pitchUncertaintyDeg = 0.8,
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
    fun `端末で確定した星とキャリブレーション精度だけを根拠として渡す`() {
        val text = request.userText()
        for (needle in listOf("視野中心に近い順", "アンタレス", "等級1.0", "方位±1.2", "仰角±0.8")) {
            assertTrue("確定観測に $needle が入っていない", needle in text)
        }
        val body = OpenAiClient.buildRequestBody("gpt-4o", request).toString()
        assertTrue("外部知識を補わない指示がない", "距離、大きさ、年齢、神話、由来" in body)
    }

    /**
     * **主役は視野中心にいちばん近い星座で固定する。**
     *
     * 実機で「的外れな星座を答える」ときの経路のひとつが、脇役を並べたせいでモデルが
     * 主役を選び直すこと。指示は**依頼文の末尾**でなければ効かない（AGENTS.md の実測）。
     */
    @Test
    fun `主役を末尾で名前で固定し、脇役は主役ではないと明示する`() {
        val text = request.userText()
        assertEquals("さそり座", request.subject)
        assertTrue("脇役が主役ではないと書いていない", "主役は「さそり座」です" in text)
        assertTrue(
            "末尾で主役を名前で指していない",
            "「さそり座」だけを解説してください。ほかの星座に話を移さないでください。" in text,
        )
        // 添付画像の説明より後ろに来ていないと薄まる
        assertTrue(
            "主役の指示が画像の説明より前にある",
            text.lastIndexOf("だけを解説してください") > text.indexOf("添付は"),
        )
        val body = OpenAiClient.buildRequestBody("gpt-4o", request).toString()
        assertTrue(
            "主役の決め方がシステム側の箇条書きに残っている",
            "最初に挙がっている星座を主役にする" !in body,
        )
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
    fun `reasoning_effort は値があるときだけ載る`() {
        // 空のまま送ると、推論を持たないモデルが 400 を返す
        val without = OpenAiClient.buildRequestBody("gpt-4o", request, "")
        assertTrue("空なのに載っている", !without.has("reasoning_effort"))

        val with = OpenAiClient.buildRequestBody("gpt-5.6-luna", request, "none")
        assertEquals("none", with.getString("reasoning_effort"))
    }

    @Test
    fun `出力枠は推論に食われない広さを取る`() {
        // 400 だったときは実測 5 回中 3 回が finish_reason=length で本文が空になった
        val body = OpenAiClient.buildRequestBody("gpt-5.6-luna", request, "none")
        assertTrue("枠が狭すぎる", body.getInt("max_completion_tokens") >= 1000)
        assertTrue("SSEが有効になっていない", body.getBoolean("stream"))
    }

    @Test
    fun `失敗の種類を取り違えない`() {
        // ここを間違えると、通信できているのに「圏外です」と喋る
        assertEquals(FailureKind.EMPTY, classifyFailure(EmptyReplyException("空")))
        assertEquals(FailureKind.NETWORK, classifyFailure(java.net.UnknownHostException("api.openai.com")))
        assertEquals(FailureKind.NETWORK, classifyFailure(java.net.SocketTimeoutException("timeout")))
        assertEquals(FailureKind.NETWORK, classifyFailure(java.net.SocketException("connection reset")))
        assertEquals(FailureKind.API, classifyFailure(IOException("HTTP 404: model not found")))
        assertEquals(FailureKind.API, classifyFailure(IOException("HTTP 401: API キーが違う")))
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
