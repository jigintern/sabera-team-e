package jp.jig.glasses.sample.kmp.ui

import jp.jig.glasses.sample.kmp.glass.GlassTextPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceInputPromptTest {
    @Test
    fun `グラスの録音表示にタップで送信を含める`() {
        val lines = askPrompt(0.5f).lines()

        assertEquals(listOf("質問をどうぞ。", "●●●●○○○○ 4/8", "タップで送信"), lines)
        assertTrue(lines.all { it.length <= GlassTextPage.lineChars })
    }

    /**
     * **枠の増減だけでは「自分の声が届いたのか」が分からない**（2026-08-24 実機）。
     * 拾えた時点で 1 行目を書き換える。
     */
    @Test
    fun `声を拾えたら1行目を書き換える`() {
        val lines = askPrompt(0.8f, heard = true).lines()

        assertEquals("聞こえています", lines.first())
        assertTrue(lines.all { it.length <= GlassTextPage.lineChars })
    }

    /** 丸の字形がグラスに無くても、数字が動けば拾えていることは伝わる */
    @Test
    fun `音量は数でも出す`() {
        assertTrue(askPrompt(0f).lines()[1].endsWith(" 0/8"))
        assertTrue(askPrompt(1f).lines()[1].endsWith(" 8/8"))
    }
}
