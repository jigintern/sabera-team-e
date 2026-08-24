package jp.jig.glasses.sample.kmp.support

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 同じキーの非同期処理を1本だけ走らせ、呼び出し側で結果を共有する。
 *
 * **失敗で呼び出し側のスコープを巻き込まない。** `async` の失敗は `await` で受け取っても
 * 親をキャンセルするので、[SupervisorJob] を必ず挟む。渡ってくるのは画面のスコープなので、
 * 挟まないと**通信が 1 回失敗しただけで画面のコルーチンが全部死ぬ**
 * （2026-08-21 に実機で踏んだ。6DoF の購読とログが止まり、星図が更新されなくなった）。
 * 親は渡してあるので、画面を離れたときのキャンセルは今までどおり伝わる。
 */
internal class SingleFlight<K>(caller: CoroutineScope) {
    private val scope = CoroutineScope(
        caller.coroutineContext + SupervisorJob(caller.coroutineContext[Job]),
    )
    private val gate = Mutex()
    private val running = HashMap<K, Deferred<Unit>>()

    suspend fun getOrStart(key: K, block: suspend () -> Unit): Deferred<Unit> = gate.withLock {
        running[key]?.let { return@withLock it }
        lateinit var created: Deferred<Unit>
        created = scope.async(start = CoroutineStart.LAZY) {
            try {
                block()
            } finally {
                gate.withLock {
                    if (running[key] === created) running.remove(key)
                }
            }
        }
        running[key] = created
        created.start()
        created
    }

    suspend fun current(key: K): Deferred<Unit>? = gate.withLock { running[key] }

    suspend fun cancelAll() = gate.withLock {
        running.values.forEach { it.cancel() }
        running.clear()
    }
}
