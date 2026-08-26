package jp.jig.glasses.sample.kmp.ui

/**
 * 観測中の切断の見張り。**瞬断で観測画面を追い出さない。**
 *
 * BLE は屋外でよく途切れるので、切れた瞬間に方位合わせからやり直しにすると使い物にならない。
 * 一定時間つながらないままだったときだけ「切れた」と決める。
 *
 * 時刻を引数で受けるので JVM テストで固定できる。
 */
internal class ConnectionWatch(private val graceMillis: Long = CONNECTION_LOST_GRACE_MS) {

    private var disconnectedAt: Long? = null

    /** つながっているかを 1 回見る。戻り値が true なら「切れた」と決めてよい */
    fun sample(connected: Boolean, nowMillis: Long): Boolean {
        if (connected) {
            disconnectedAt = null
            return false
        }
        val startedAt = disconnectedAt ?: nowMillis.also { disconnectedAt = it }
        return nowMillis - startedAt >= graceMillis
    }

    /** 観測をやめたとき・つなぎ直したときに呼ぶ */
    fun reset() {
        disconnectedAt = null
    }
}
