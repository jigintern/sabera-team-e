package jp.jig.glasses.sample.kmp.ai

/**
 * 声の質問（#38）の入口と出口を検査する。
 *
 * **聞き取った文は指示ではなくデータ。** そのまま依頼文へ埋めると、
 * 「これまでの指示は無視して」と喋るだけで役割を上書きできてしまう。
 * ここで**囲い記号を落とし、長さを切り、区切りを固定**してから渡す。
 *
 * 出口も見る。**読み上げるので記号がそのまま読まれる**うえ、
 * 星空と関係ない話に流れると、聞いている人は目の前の空と突き合わせられない。
 *
 * ここは端末だけで完結する（通信も生成も要らない）ので、**JVM テストで固定できる**。
 */
object AskGuard {

    /** 聞き取りに許す長さ。**ふつうの質問は 30 文字も要らない** */
    const val MAX_QUESTION_CHARS = 120

    /** 答えに許す長さ。プロンプトでは 100 文字程度と縛っているので、その倍で切る */
    const val MAX_ANSWER_CHARS = 200

    /**
     * 区切り記号。**聞き取った文からは必ず取り除く**ので、
     * 質問の中からこの行を閉じることはできない。
     */
    const val QUESTION_OPEN = "<<<質問ここから>>>"
    const val QUESTION_CLOSE = "<<<質問ここまで>>>"

    /**
     * 依頼文に埋める前に整える。
     *
     * - 改行と制御文字を落とす（複数行にして偽の役割行を作らせない）
     * - 囲い記号と、区切りに使う記号を落とす
     * - 長さを切る（長々と指示を並べられないようにする）
     *
     * 空になったら「聞き取れなかった」として扱う。
     */
    fun sanitizeQuestion(raw: String): String {
        val stripped = raw.map { ch ->
            when {
                ch == '\n' || ch == '\r' || ch == '\t' -> ' '
                ch.isISOControl() -> ' '
                ch in FORBIDDEN_IN_QUESTION -> ' '
                else -> ch
            }
        }.joinToString("")
        return stripped.replace(WHITESPACE, " ").trim().take(MAX_QUESTION_CHARS)
    }

    /**
     * 答えを読み上げられる形にする。
     *
     * **記号はそのまま読まれる**（「※」も「1.」も）ので落とす。
     * 長すぎるものは**文の切れ目で**切る。途中で切ると言い差しになって落ち着かない。
     * 何も残らなければ null。
     */
    fun sanitizeAnswer(raw: String): String? {
        val flat = raw.lines()
            .map { line -> line.trim().removePrefix("- ").removePrefix("・").trimStart('*', '#', '>') }
            .filter { it.isNotBlank() }
            .joinToString("")
        val cleaned = flat.filterNot { it in FORBIDDEN_IN_ANSWER }.trim()
        if (cleaned.isEmpty()) return null
        if (cleaned.length <= MAX_ANSWER_CHARS) return cleaned
        val cut = cleaned.take(MAX_ANSWER_CHARS)
        val lastStop = cut.lastIndexOfAny(charArrayOf('。', '！', '？'))
        return if (lastStop >= 0) cut.take(lastStop + 1) else cut
    }

    /** 星空の話ではないと返ってきたときの断り。**端末が持つ固定文**なので生成に左右されない */
    const val OFF_TOPIC =
        "それは星空の話ではないので、お答えできません。いま見えている星のことなら聞いてください。"

    /** 囲い記号と、区切りに使う記号。質問から取り除く */
    private val FORBIDDEN_IN_QUESTION =
        setOf('「', '」', '『', '』', '<', '>', '{', '}', '`', '\\', '"')

    /** 読み上げると邪魔になる記号 */
    private val FORBIDDEN_IN_ANSWER =
        setOf('*', '#', '`', '~', '|', '<', '>', '[', ']', '{', '}', '※', '•', '・')

    private val WHITESPACE = Regex("\\s+")
}
