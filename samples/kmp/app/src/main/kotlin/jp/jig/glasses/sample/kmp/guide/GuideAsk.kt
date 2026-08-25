package jp.jig.glasses.sample.kmp.guide

/**
 * toB の対話で、**端末が先に判断する**ところ。
 *
 * `openai/AskGuard`（声の質問）と同じ位置づけ。あちらは「聞き取った文を指示として扱わない」
 * ためにあり、こちらは **AI に星座を選ばせない**ためにある（16_guide.md の 4 条件の③）。
 *
 * 打たれた文に「オリオン座」が入っていても、その日その時間に空へ出ていなければ
 * **AI へ渡す前にここで断る**。渡してしまうと、AI が気を利かせて台本に載せ、
 * 再生時に [GuidePlan] が全部飛ばして「何も起きないガイド」になる。
 *
 * **Android に触らない。**
 */
object GuideAsk {

    /** 何往復まで続けるか。**積み上がるほど毎回の送信が太る** */
    const val MAX_TURNS = 10

    /** 1 回に打てる長さ。声の質問（120 字）と揃える */
    const val MAX_INPUT_CHARS = 120

    /**
     * 打たれた文から、端末が知っている対象名を拾う。
     *
     * **長いものから見る。**「みなみのかんむり座」を「かんむり座」で取ると、
     * 別の星座を案内することになる。
     */
    fun namesIn(text: String, known: Collection<String>): List<String> {
        val found = LinkedHashSet<String>()
        var rest = text
        for (name in known.distinct().sortedByDescending { it.length }) {
            if (name.isBlank() || !rest.contains(name)) continue
            found += name
            // 拾った名前は伏せる。**部分文字列で二重に拾わない**
            rest = rest.replace(name, "　")
        }
        return found.toList()
    }

    /**
     * その日その時間に入れられないものを断る文。
     *
     * **断るだけで終わらせない。** ずらせば入るのは端末が計算できる事実なので、
     * いつからなら入るかを添える（行き止まりにすると、そこで人が諦める）。
     */
    fun rejection(name: String, availableFrom: String?): String = when (availableFrom) {
        null -> "その日は一晩じゅう、${name}は高く上がりません。この台本には入れられません。"
        else -> "その時間、${name}はまだ低すぎます。$availableFrom 以降なら入れられます。"
    }

    /** 空打ちと長すぎる入力を弾く。**通信する前に見る** */
    fun sanitizeInput(raw: String): String? =
        raw.trim().replace(WHITESPACE, " ").takeIf { it.isNotEmpty() }?.take(MAX_INPUT_CHARS)

    private val WHITESPACE = Regex("\\s+")
}
