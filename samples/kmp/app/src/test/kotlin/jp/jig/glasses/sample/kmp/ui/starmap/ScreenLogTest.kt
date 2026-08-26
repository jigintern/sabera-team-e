package jp.jig.glasses.sample.kmp.ui.starmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 観測画面のログ（[ScreenLog]）。40 行のリングとファイル併記 */
class ScreenLogTest {

    private fun screenLog(appended: MutableList<String> = mutableListOf()) =
        ScreenLog(append = { appended += it }, logcat = { _, _ -> }) to appended

    @Test
    fun `新しい行が先頭に来て40行で追い出す`() {
        val (log, _) = screenLog()
        repeat(45) { log.log("行 $it") }
        assertEquals(LOG_LINES, log.lines.size)
        assertEquals("行 44", log.lines.first().text)
        assertEquals("行 5", log.lines.last().text)
    }

    @Test
    fun `失敗はファイル側に印を付けて残す`() {
        val (log, appended) = screenLog()
        log.log("描けた")
        log.log("送れない", failed = true)
        assertEquals(listOf("描けた", "失敗  送れない"), appended)
        assertTrue(log.lines.first().failed)
    }
}
