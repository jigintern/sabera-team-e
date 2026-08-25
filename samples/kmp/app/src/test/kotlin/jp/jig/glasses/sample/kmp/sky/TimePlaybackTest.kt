package jp.jig.glasses.sample.kmp.sky

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 時間送りの向き（#45）。**巻き戻しは符号だけが違う** */
class TimePlaybackTest {

    @Test
    fun `巻き戻しは時間が減る向きへ進む`() {
        val playing = TimePlaybackState().startPlayback(0L, forward = false)
        val tick = playing.tick(PLAYBACK_STEP_MS, settled = true)
        assertTrue("戻る向きなのに ${tick.advanceMillis}", tick.advanceMillis < 0)
        assertEquals(-SIMULATED_STEP_MS, tick.advanceMillis)
    }

    @Test
    fun `早送りは時間が増える向きへ進む`() {
        val playing = TimePlaybackState().startPlayback(0L, forward = true)
        assertEquals(SIMULATED_STEP_MS, playing.tick(PLAYBACK_STEP_MS, settled = true).advanceMillis)
    }

    @Test
    fun `同じ向きへ押し直しても30秒の上限が延びない`() {
        // 延ばせると、押し続けるだけでいつまでも動く。止まらない演出は空を見る邪魔になる
        val started = TimePlaybackState().startPlayback(0L, forward = true)
        val pressedAgain = started.startPlayback(20_000L, forward = true)
        assertEquals(started.startedElapsedMillis, pressedAgain.startedElapsedMillis)
        assertFalse(pressedAgain.tick(PLAYBACK_LIMIT_MS, settled = true).playback.playing)
    }

    @Test
    fun `逆を押したら向きが変わって動き続ける`() {
        // 逆を押したのに止まらないのは操作として通じない
        val forward = TimePlaybackState().startPlayback(0L, forward = true)
        val reversed = forward.startPlayback(5_000L, forward = false)
        assertFalse(reversed.forward)
        assertTrue(reversed.playing)
        assertEquals(5_000L, reversed.startedElapsedMillis)
    }

    @Test
    fun `止めたら向きに関わらず動かない`() {
        val stopped = TimePlaybackState().startPlayback(0L, forward = false).stopPlayback()
        assertEquals(0L, stopped.tick(PLAYBACK_STEP_MS, settled = true).advanceMillis)
    }
}
