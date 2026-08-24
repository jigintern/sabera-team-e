package jp.jig.glasses.sample.kmp.ui

import jp.jig.glasses.sample.kmp.glass.GlassTextPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceInputPromptTest {
    @Test
    fun `グラスの録音表示にタップで送信を含める`() {
        val lines = askPrompt(0.5f).lines()

        assertEquals(listOf("質問をどうぞ。", "■■■■□□□□", "タップで送信"), lines)
        assertTrue(lines.all { it.length <= GlassTextPage.lineChars })
    }
}
