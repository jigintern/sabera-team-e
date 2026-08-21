package jp.jig.glasses.sample.kmp.ai

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.EOFException

class OpenAiStreamingTest {
    @Test
    fun `SSEの差分を受信中から渡して全文も返す`() = runBlocking {
        TestHttpServer {
            sse(listOf(delta("最初の文です。"), delta("次の文です。"), done()), requestId = "req-stream")
        }.use { server ->
            val deltas = ArrayList<String>()
            val traces = ArrayList<OpenAiRequestTrace>()

            val result = client(server, traces).explain(request()) { deltas += it }

            assertEquals(listOf("最初の文です。", "次の文です。"), deltas)
            assertEquals("最初の文です。次の文です。", result)
            assertTrue(traces.single().completed)
            assertEquals("req-stream", traces.single().requestId)
        }
    }

    @Test
    fun `本文受信前の一時エラーだけ一回再試行する`() = runBlocking {
        TestHttpServer { call ->
            if (call == 1) {
                TestHttpResponse(503, "busy".toByteArray(), requestId = "req-retry-1")
            } else {
                sse(listOf(delta("再試行できました。"), done()), requestId = "req-retry-2")
            }
        }.use { server ->
            val traces = ArrayList<OpenAiRequestTrace>()

            val result = client(server, traces).explain(request())

            assertEquals("再試行できました。", result)
            assertEquals(2, server.calls.get())
            assertEquals(listOf(false, true), traces.map { it.completed })
            assertEquals(listOf(1, 2), traces.map { it.attempt })
        }
    }

    @Test
    fun `途中まで届いたストリームは再試行せず受信済み文を残す`() = runBlocking {
        TestHttpServer {
            // [DONE]を送らず切断し、実際の電波断と同じEOFにする
            sse(listOf(delta("ここまでは届きました。")), declaredExtraBytes = 100)
        }.use { server ->
            val received = ArrayList<String>()

            val error = runCatching { client(server).explain(request()) { received += it } }.exceptionOrNull()

            assertTrue("EOFではない: $error", error is EOFException)
            assertEquals(1, server.calls.get())
            assertEquals(listOf("ここまでは届きました。"), received)
            assertEquals(FailureKind.NETWORK, classifyFailure(error!!))
        }
    }

    @Test
    fun `トークン上限で本文が空なら通信断と区別する`() = runBlocking {
        TestHttpServer { sse(listOf(finish("length"), done())) }.use { server ->
            val error = runCatching { client(server).explain(request()) }.exceptionOrNull()

            assertTrue("EmptyReplyExceptionではない: $error", error is EmptyReplyException)
            assertTrue("理由が分からない: ${error?.message}", "トークン上限" in error?.message.orEmpty())
            assertEquals(FailureKind.EMPTY, classifyFailure(error!!))
        }
    }

    @Test
    fun `ストリーム中の回答拒否は理由を残す`() = runBlocking {
        TestHttpServer { sse(listOf(refusal("回答できません"), done())) }.use { server ->
            val error = runCatching { client(server).explain(request()) }.exceptionOrNull()

            assertTrue("EmptyReplyExceptionではない: $error", error is EmptyReplyException)
            assertTrue("拒否理由がない: ${error?.message}", "回答できません" in error?.message.orEmpty())
        }
    }

    @Test
    fun `Narratorは完了した文だけを残して余計な通信断案内を喋らない`() = runBlocking {
        TestHttpServer {
            sse(listOf(delta("一文目は届きました。二文目は途中で")), declaredExtraBytes = 100)
        }.use { server ->
            val voice = RecordingVoice()
            val narrator = Narrator(voice, client(server), log = { _, _ -> })

            narrator.narrate(input())

            assertEquals("おとめ座ですね。", voice.spoken[0])
            assertEquals("一文目は届きました。", voice.spoken[1])
            assertEquals("余計な失敗案内を喋っている: ${voice.spoken}", 2, voice.spoken.size)
            assertTrue("不完全な文を喋っている: ${voice.spoken}", voice.spoken.none { "二文目" in it })
            assertTrue(
                "受信済み表示を捨てている",
                "一文目は届きました" in narrator.state.value.text,
            )
            assertTrue("不完全な末尾を表示している", "二文目" !in narrator.state.value.text)
            assertEquals(NarrationPhase.IDLE, narrator.state.value.phase)
        }
    }

    private fun client(server: TestHttpServer, traces: MutableList<OpenAiRequestTrace> = ArrayList()) = OpenAiClient(
        apiKey = "sk-test",
        model = "gpt-4o",
        endpoint = server.endpoint,
        onTrace = { traces += it },
    )

    private fun request() = ExplainRequest(
        constellations = listOf("おとめ座"),
        latDeg = 35.0,
        lonDeg = 136.0,
        azDeg = 180.0,
        altDeg = 45.0,
        localTime = "2026-08-21 21:00 JST",
    )

    private fun input() = NarrationInput(
        calibrated = true,
        altDeg = 45.0,
        azDeg = 180.0,
        constellations = listOf("おとめ座"),
        latDeg = 35.0,
        lonDeg = 136.0,
        localTime = "2026-08-21 21:00 JST",
        pngBase64 = null,
    )

    private fun delta(text: String): String = "data: " + JSONObject()
        .put(
            "choices",
            JSONArray().put(
                JSONObject()
                    .put("delta", JSONObject().put("content", text))
                    .put("finish_reason", JSONObject.NULL),
            ),
        ) + "\n\n"

    private fun done(): String = "data: [DONE]\n\n"

    private fun finish(reason: String): String = "data: " + JSONObject()
        .put(
            "choices",
            JSONArray().put(
                JSONObject()
                    .put("delta", JSONObject())
                    .put("finish_reason", reason),
            ),
        ) + "\n\n"

    private fun refusal(text: String): String = "data: " + JSONObject()
        .put(
            "choices",
            JSONArray().put(
                JSONObject()
                    .put("delta", JSONObject().put("refusal", text))
                    .put("finish_reason", JSONObject.NULL),
            ),
        ) + "\n\n"

    private fun sse(
        events: List<String>,
        requestId: String = "req-stream",
        declaredExtraBytes: Int = 0,
    ): TestHttpResponse {
        val body = events.joinToString("").toByteArray(Charsets.UTF_8)
        return TestHttpResponse(
            status = 200,
            body = body,
            contentType = "text/event-stream",
            requestId = requestId,
            declaredLength = body.size + declaredExtraBytes,
        )
    }

    private class RecordingVoice : Voice {
        val spoken = ArrayList<String>()
        override fun say(text: String) { spoken += text }
        override fun add(text: String) { spoken += text }
        override fun stop() = Unit
    }
}
