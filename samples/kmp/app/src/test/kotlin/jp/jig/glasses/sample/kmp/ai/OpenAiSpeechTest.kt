package jp.jig.glasses.sample.kmp.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 読み上げの注文が崩れていないことを押さえる。
 * **話し方の指示が抜けると棒読みに戻る**（issue #20 が戻ってくる）ので、そこだけは検算する。
 */
class OpenAiSpeechTest {

    @Test
    fun `話し方の指示を付けて、届いたぶんから鳴らせる形式で頼む`() {
        val body = OpenAiSpeech.buildRequestBody("gpt-4o-mini-tts", "sage", "オリオン座ですね。")

        assertEquals("gpt-4o-mini-tts", body.getString("model"))
        assertEquals("sage", body.getString("voice"))
        assertEquals("オリオン座ですね。", body.getString("input"))
        assertEquals("pcm", body.getString("response_format"))
        assertTrue(body.getString("instructions").contains("プラネタリウム"))
    }

    @Test
    fun `tts-1 には話し方の指示を付けない`() {
        // 旧モデルは instructions を解さない。付けて投げると 400 で落ちる
        val body = OpenAiSpeech.buildRequestBody("tts-1", "sage", "こんばんは。")

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
        assertFalse(OpenAiSpeech("", "sage", "gpt-4o-mini-tts").configured)
        assertTrue(OpenAiSpeech("sk-test", "sage", "gpt-4o-mini-tts").configured)
    }
}
