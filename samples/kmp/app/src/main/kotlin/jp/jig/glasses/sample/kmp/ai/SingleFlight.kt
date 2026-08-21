package jp.jig.glasses.sample.kmp.ai

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 同じキーの非同期処理を1本だけ走らせ、呼び出し側で結果を共有する。 */
internal class SingleFlight<K>(private val scope: CoroutineScope) {
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
