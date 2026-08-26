package jp.jig.glasses.sample.kmp.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 観測中の切断の見張り（[ConnectionWatch]）。**瞬断では追い出さない** */
class ConnectionWatchTest {

    @Test
    fun `猶予のうちにつながり直せば切れたことにしない`() {
        val watch = ConnectionWatch(graceMillis = 3_000L)
        assertFalse(watch.sample(connected = false, nowMillis = 1_000L))
        assertFalse(watch.sample(connected = false, nowMillis = 3_500L))
        assertFalse(watch.sample(connected = true, nowMillis = 3_900L))
        // 数え直すので、次に切れてもすぐには切れたことにならない
        assertFalse(watch.sample(connected = false, nowMillis = 6_000L))
    }

    @Test
    fun `つながらないまま猶予を過ぎたら切れたと決める`() {
        val watch = ConnectionWatch(graceMillis = 3_000L)
        assertFalse(watch.sample(connected = false, nowMillis = 1_000L))
        assertTrue(watch.sample(connected = false, nowMillis = 4_000L))
    }

    @Test
    fun `観測をやめたら数え直す`() {
        val watch = ConnectionWatch(graceMillis = 3_000L)
        watch.sample(connected = false, nowMillis = 1_000L)
        watch.reset()
        assertFalse(watch.sample(connected = false, nowMillis = 4_000L))
    }
}
