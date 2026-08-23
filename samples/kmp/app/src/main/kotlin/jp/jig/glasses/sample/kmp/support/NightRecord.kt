package jp.jig.glasses.sample.kmp.support

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 今夜どの星座を解説したか。**読み終わった解説文はどこにも残らなかった。**
 *
 * グラスの字幕は数秒でめくれて消え、スマホの解説パネルは次の解説で上書きされる。
 * 声も一度きりなので、**聞き逃した人はもう読めない**（同伴者は空を見ていて、
 * スマホを覗いたときには次の星座に変わっている）。観測ログ（[SessionLog]）は
 * 実機の数字を追うためのもので、解説文そのものは入っていない。
 *
 * 画面ではなくここに置くのは、**観測画面が作り直される**から
 * （「方位を合わせ直す」・瞬断からの復帰）。画面に持たせると、そのたびに今夜が消える。
 *
 * 端末に書き出さない。**「今夜」で意味があるもの**で、翌日に開いて読み返すものではない。
 */
object NightRecord {

    /** 解説 1 回ぶん */
    class Seen(val nameJa: String, val atMillis: Long, val text: String)

    private val _seen = MutableStateFlow<List<Seen>>(emptyList())
    val seen: StateFlow<List<Seen>> = _seen

    /**
     * 解説し終わったものを積む。
     *
     * **同じ星座を続けて積まない。** 首はそのままで、聞き直したりタップし直したりで
     * 同じ星座が何度も呼ばれるので、そのまま並べると一覧が 1 つの星座で埋まる。
     */
    fun add(nameJa: String, atMillis: Long, text: String) {
        if (nameJa.isBlank() || text.isBlank()) return
        val current = _seen.value
        val last = current.lastOrNull()
        if (last != null && last.nameJa == nameJa && atMillis - last.atMillis < SAME_AGAIN_MS) return
        _seen.value = (current + Seen(nameJa, atMillis, text)).takeLast(LIMIT)
    }

    fun clear() {
        _seen.value = emptyList()
    }

    /** 同じ星座を積み直すまでの間 */
    private const val SAME_AGAIN_MS = 10 * 60_000L

    /** 持っておく件数。ひと晩の観測でこれを超えることはまず無い */
    private const val LIMIT = 40
}
