package jp.jig.glasses.sample.kmp.openai

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * 読み上げの注文が崩れていないことを押さえる。
 * **話し方の指示が抜けると棒読みに戻る**（issue #20 が戻ってくる）ので、そこだけは検算する。
 */
class OpenAiSpeechTest {

    @Test
    fun `話し方の指示を付けて、届いたぶんから鳴らせる形式で頼む`() {
        val body = OpenAiSpeech.buildRequestBody("gpt-4o-mini-tts", "alloy", "オリオン座ですね。")

        assertEquals("gpt-4o-mini-tts", body.getString("model"))
        assertEquals("alloy", body.getString("voice"))
        assertEquals("オリオン座ですね。", body.getString("input"))
        assertEquals("pcm", body.getString("response_format"))
        assertTrue(body.getString("instructions").contains("演じずに"))
    }

    @Test
    fun `tts-1 には話し方の指示を付けない`() {
        // 旧モデルは instructions を解さない。付けて投げると 400 で落ちる
        val body = OpenAiSpeech.buildRequestBody("tts-1", "alloy", "こんばんは。")

        assertFalse(body.has("instructions"))
    }

    @Test
    fun `キーが違う失敗は諦める、混み合いは諦めない`() {
        assertTrue(OpenAiSpeech.SpeechException(401, "").permanent)
        assertTrue(OpenAiSpeech.SpeechException(404, "").permanent)
        assertFalse(OpenAiSpeech.SpeechException(429, "").permanent)
        assertFalse(OpenAiSpeech.SpeechException(500, "").permanent)
    }

    @Test
    fun `キーが無ければ使えないと分かる`() {
        assertFalse(OpenAiSpeech("", "alloy", "gpt-4o-mini-tts").configured)
        assertTrue(OpenAiSpeech("sk-test", "alloy", "gpt-4o-mini-tts").configured)
    }

    @Test
    fun `音声も受信前の一時エラーだけ一回再試行する`() = runBlocking {
        TestHttpServer { call ->
            if (call == 1) {
                TestHttpResponse(503, "busy".toByteArray())
            } else {
                val pcm = byteArrayOf(1, 2, 3, 4)
                TestHttpResponse(200, pcm, contentType = "application/octet-stream", requestId = "req-speech")
            }
        }.use { server ->
            val received = ArrayList<Byte>()
            val traces = ArrayList<OpenAiRequestTrace>()
            val speech = OpenAiSpeech(
                apiKey = "sk-test",
                voice = "alloy",
                model = "gpt-4o-mini-tts",
                endpoint = server.endpoint,
                onTrace = { traces += it },
            )

            speech.stream("こんばんは。") { buffer, length ->
                repeat(length) { received += buffer[it] }
            }

            assertEquals(listOf<Byte>(1, 2, 3, 4), received)
            assertEquals(2, server.calls.get())
            assertEquals(listOf(false, true), traces.map { it.completed })
            assertEquals("req-speech", traces.last().requestId)
        }
    }

    @Test
    fun `途中まで届いた音声は再試行して先頭を重ねない`() = runBlocking {
        val pcm = byteArrayOf(1, 2, 3, 4)
        TestHttpServer {
            TestHttpResponse(
                status = 200,
                body = pcm,
                contentType = "application/octet-stream",
                declaredLength = 100,
            )
        }.use { server ->
            var received = 0
            val speech = OpenAiSpeech(
                apiKey = "sk-test",
                voice = "alloy",
                model = "gpt-4o-mini-tts",
                endpoint = server.endpoint,
            )

            val error = runCatching {
                speech.stream("短い文です。") { _, length -> received += length }
            }.exceptionOrNull()

            // **切れ方は OS とタイミングで変わる**（EOF になることも Connection reset になることもある）。
            // 製品側は例外の種類ではなく「1 バイトでも届いたか」で再試行を決めているので、
            // ここも「切れた」ことだけを見る（種類を見ると落ちる日がある）
            assertTrue("通信の切断ではない: $error", error is IOException)
            assertEquals(4, received)
            assertEquals(1, server.calls.get())
        }
    }
}
