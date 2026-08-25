package jp.jig.glasses.sample.kmp.alignment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** タップの反動を避ける視線ラッチ（[LookLatch]） */
class LookLatchTest {

    @Test
    fun `ラッチ時点より新しい視線は使わない`() {
        val latch = LookLatch(latchMs = 500L, historyMs = 3_000L)
        latch.record(1_000L, 10.0, 1.0)
        latch.record(1_400L, 20.0, 2.0) // 反動で動いたぶん（500ms 以内）
        // 1_500ms 時点では 1_000ms の視線が「500ms 前まで」の最新
        assertEquals(10.0 to 1.0, latch.latched(1_500L))
    }

    @Test
    fun `履歴が足りなければ null で呼び出し側がごまかす`() {
        val latch = LookLatch(latchMs = 500L, historyMs = 3_000L)
        latch.record(1_000L, 10.0, 1.0)
        assertNull(latch.latched(1_200L)) // まだ 500ms 経っていない
    }

    @Test
    fun `古い履歴は落ちる`() {
        val latch = LookLatch(latchMs = 500L, historyMs = 3_000L)
        latch.record(1_000L, 10.0, 1.0)
        latch.record(5_000L, 30.0, 3.0) // 4 秒後 → 1_000ms の行は追い出される
        assertEquals(30.0 to 3.0, latch.latched(6_000L))
    }
}
