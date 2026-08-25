package jp.jig.glasses.sample.kmp.sky

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 時刻のつまみ（#45）。
 *
 * **連続再生はやめた。** 2 秒ごとに全画面を焼き直すことになり、1 枚 279〜390ms の
 * 転送のたびにパネルが消えるので原理的に点滅する。つまみなら離したときの 1 回だけ。
 */
class TimeScrubTest {

    /**
     * **動かしたのにつまみが元へ戻る**のを防ぐ検査。
     *
     * 置いた位置を別に持って離すたびに 0 へ戻していたときは、空だけ変わって
     * つまみが中央へ跳ね返っていた。**時刻から引き直せば置いた場所に留まる。**
     */
    @Test
    fun `つまみを離した位置に留まる`() {
        val anchor = 1_767_268_800_000L
        for (offset in listOf(-12f, -3.5f, 0f, 2f, 11.75f)) {
            val moved = scrubTargetMillis(anchor, offset)
            val back = scrubOffsetHours(anchor, moved, 12f)
            assertEquals("$offset 時間ずらしたのに $back に戻った", offset, back, 0.001f)
        }
    }

    @Test
    fun `時間送りでつまみが動く`() {
        // 送っている間つまみが止まったままだと、進んでいるのかが見えない
        val anchor = 1_767_268_800_000L
        val after10min = anchor + 10 * 60_000L
        assertTrue(scrubOffsetHours(anchor, after10min, 12f) > 0f)
        assertEquals(1f / 6f, scrubOffsetHours(anchor, after10min, 12f), 0.001f)
    }

    /**
     * つまんでいる間に出す読み。
     *
     * **指がスライダーに乗るので、どこへ着くのかが数字で見えないと離すまで分からない。**
     */
    @Test
    fun `ずれを読める形にする`() {
        assertEquals("指定した時刻", scrubOffsetLabel(0f))
        assertEquals("＋3時間", scrubOffsetLabel(3f))
        assertEquals("＋3時間30分", scrubOffsetLabel(3.5f))
        assertEquals("−45分", scrubOffsetLabel(-0.75f))
        assertEquals("−12時間", scrubOffsetLabel(-12f))
    }

    @Test
    fun `15分刻みなら読みが半端にならない`() {
        // 星は 4 分で 1° しか動かないので、これより細かく選ばせても見分けられない。
        // 刻みに乗せておけば「＋2時間37分」のような読みが出ない
        val step = 0.25f
        for (i in -48..48) {
            val label = scrubOffsetLabel(i * step)
            assertTrue("$label が 15 分刻みでない", label.endsWith("時間") ||
                label == "指定した時刻" ||
                label.endsWith("15分") || label.endsWith("30分") || label.endsWith("45分"))
        }
    }

    @Test
    fun `つまみは範囲の外へ出ない`() {
        val anchor = 1_767_268_800_000L
        assertEquals(12f, scrubOffsetHours(anchor, anchor + 40 * 3_600_000L, 12f), 0.001f)
        assertEquals(-12f, scrubOffsetHours(anchor, anchor - 40 * 3_600_000L, 12f), 0.001f)
    }
}
