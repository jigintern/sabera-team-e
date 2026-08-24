package jp.jig.glasses.sample.kmp.support

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 声で聞いたことと、返ってきた答え（#38）。
 *
 * **やり取りはどこにも残らなかった。** グラスの字幕は流れて消え、声は一度きり、
 * スマホの解説パネルは次の質問で上書きされる。**同伴者はスマホを覗いたときには
 * もう次の話になっている**し、聞いた本人も「さっき何と言われたか」を確かめられない。
 *
 * 画面ではなくここに置くのは、**観測画面が作り直される**から
 * （「方位を合わせ直す」・瞬断からの復帰）。[NightRecord] と同じ理由で、同じ作りにしてある。
 *
 * **端末には書き出さない。** ひと晩ぶんの覚え書きで、翌日に開いて読み返すものではない。
 */
object AskHistory {

    /**
     * やり取り 1 往復。
     *
     * [answered] は答えが返ったかどうか。**断りや失敗も残す**（「聞き取れませんでした」も
     * 履歴に無いと、質問が届かなかったのか答えが返らなかったのかが分からない）。
     */
    class Exchange(
        val atMillis: Long,
        val question: String,
        val answer: String,
        val answered: Boolean,
    )

    private val _exchanges = MutableStateFlow<List<Exchange>>(emptyList())
    val exchanges: StateFlow<List<Exchange>> = _exchanges

    fun add(atMillis: Long, question: String, answer: String, answered: Boolean) {
        if (answer.isBlank()) return
        _exchanges.value = (_exchanges.value + Exchange(atMillis, question, answer, answered))
            .takeLast(LIMIT)
    }

    fun clear() {
        _exchanges.value = emptyList()
    }

    /** 持っておく件数。ひと晩でこれを超えることはまず無い */
    private const val LIMIT = 30
}
