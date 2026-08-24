package jp.jig.glasses.sample.kmp.narration

/**
 * SSEの細かい文字列を、読み上げ可能な文へまとめる。
 *
 * トークン単位でTTSへ投げると発話が細切れになるため、句点まで待つ。ただし全文生成は待たない。
 */
internal class SentenceAccumulator {
    private val pending = StringBuilder()

    fun append(delta: String): List<String> {
        if (delta.isEmpty()) return emptyList()
        pending.append(delta)
        val sentences = ArrayList<String>()
        var start = 0
        for (index in pending.indices) {
            if (pending[index] !in END_MARKS) continue
            val sentence = pending.substring(start, index + 1).trim()
            if (sentence.isNotEmpty()) sentences += sentence
            start = index + 1
        }
        if (start > 0) pending.delete(0, start)
        return sentences
    }

    /** 正常終了時だけ、句点なしで残った末尾も読み上げる。通信断時は不完全な文なので捨てる。 */
    fun flush(): String = pending.toString().trim().also { pending.clear() }

    fun discard() {
        pending.clear()
    }

    private companion object {
        val END_MARKS = charArrayOf('。', '！', '？', '!', '?')
    }
}
