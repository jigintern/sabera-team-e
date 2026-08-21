package jp.jig.glasses.sample.kmp.ai

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class SingleFlightTest {
    @Test
    fun `同じキーの先読みは一度しか実行しない`() = runBlocking {
        coroutineScope {
            val singleFlight = SingleFlight<String>(this)
            val release = CompletableDeferred<Unit>()
            val calls = AtomicInteger()

            val first = singleFlight.getOrStart("おとめ座") {
                calls.incrementAndGet()
                release.await()
            }
            val second = singleFlight.getOrStart("おとめ座") { calls.incrementAndGet() }

            assertSame(first, second)
            release.complete(Unit)
            first.await()
            assertEquals(1, calls.get())
        }
    }
}
