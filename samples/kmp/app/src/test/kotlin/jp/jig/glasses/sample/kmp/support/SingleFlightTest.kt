package jp.jig.glasses.sample.kmp.support

import jp.jig.glasses.sample.kmp.voice.CloudVoice
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class SingleFlightTest {
    /**
     * **`coroutineScope { }` で囲んではいけない。**
     *
     * [SingleFlight] は渡したスコープの子として [SupervisorJob] を作る（失敗で親を巻き込まない
     * ため）。**`SupervisorJob` は自分では完了しない**ので、構造化並行性で子の完了を待つと
     * そこで永久に止まる。囲んでいたときは `:app:testDebugUnitTest` が返ってこなくなり、
     * **1 つのテストのせいで JVM テストを 1 件も確認できなかった。**
     *
     * アプリ側は画面のスコープを渡していて、画面を離れるとキャンセルが伝わるので同じことは起きない。
     */
    @Test
    fun `同じキーの先読みは一度しか実行しない`() = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
        try {
            val singleFlight = SingleFlight<String>(scope)
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
        } finally {
            scope.cancel()
        }
    }

    /**
     * **失敗した先読みで、渡したスコープを殺さない。**
     *
     * `async` の失敗は `await` で受け取っても親を巻き込んでキャンセルする。
     * 渡ってくるのが画面のスコープ（[CloudVoice]）なので、素の [Job] のままだと
     * **圏外で TTS が 1 回失敗しただけで 6DoF の購読とログまで道連れで死ぬ**
     * （2026-08-21 に実機で踏んだ。星図が二度と更新されなくなった）。
     */
    @Test
    fun `先読みが失敗しても呼び出し側のスコープは生き残る`() = runBlocking {
        // **素の Job**。画面の rememberCoroutineScope() と同じ条件で確かめる
        val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
        val singleFlight = SingleFlight<String>(scope)
        val alive = AtomicInteger()
        val keepRunning = scope.launch { runCatching { CompletableDeferred<Unit>().await() } }

        val failed = singleFlight.getOrStart("おとめ座") { throw IOException("圏外") }
        assertTrue("失敗が await に返っていない", failed.runCatching { await() }.isFailure)

        // ここが本題。兄弟のコルーチンとスコープが生きたままであること
        assertTrue("スコープが道連れで死んだ", scope.isActive)
        assertTrue("兄弟のコルーチンが道連れで死んだ", keepRunning.isActive)

        // 同じキーをもう一度頼めば、次はやり直せる
        singleFlight.getOrStart("おとめ座") { alive.incrementAndGet() }.await()
        assertEquals(1, alive.get())

        keepRunning.cancel()
        scope.cancel()
    }
}
