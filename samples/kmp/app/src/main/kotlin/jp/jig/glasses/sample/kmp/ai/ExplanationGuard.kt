package jp.jig.glasses.sample.kmp.ai

/**
 * モデルが観測データに無い固有名や、距離・神話などの外部知識を補った文を読み上げない。
 * 正しい知識かを端末だけでは検証できないため、疑わしい文は丸ごと落とす。
 */
class ExplanationGuard(
    private val visibleStarNames: Set<String>,
    private val knownStarNames: Set<String>,
    /** 視野内にある月・惑星。ここに無い天体の名前が出たら、その文は落とす */
    private val visibleBodyNames: Set<String> = emptySet(),
) {
    fun rejectionReason(sentence: String): String? {
        val unexpectedStar = knownStarNames.firstOrNull {
            it !in visibleStarNames && sentence.contains(it)
        }
        if (unexpectedStar != null) return "視野外の星名:$unexpectedStar"
        val unexpectedBody = BODY_PATTERNS.entries.firstOrNull { (name, pattern) ->
            name !in visibleBodyNames && pattern.containsMatchIn(sentence)
        }?.key
        if (unexpectedBody != null) return "視野外の天体:$unexpectedBody"
        val unsupported = UNSUPPORTED_FACT.find(sentence)?.value
        return unsupported?.let { "未提供の外部知識:$it" }
    }

    private companion object {
        val UNSUPPORTED_FACT = Regex(
            "光年|パーセク|天文単位|キロメートル|距離は|直径|年齢|" +
                "神話|伝説|由来|ギリシャ|ローマ|[春夏秋冬]の星座",
        )

        /**
         * 月・惑星は星表に無いので、星名と同じ照合ではすり抜ける。
         *
         * **「月」だけは日付や「今月」に当たるので前を見る。** 「満月」「三日月」は月の話なので
         * 落として正しい（視野に月が無いのに月の様子を語っているため）。
         */
        val BODY_PATTERNS = linkedMapOf(
            "月" to Regex("(?<![0-9０-９今来先毎ヶかカ箇])月"),
            "金星" to Regex("金星"),
            "火星" to Regex("火星"),
            "木星" to Regex("木星"),
            "土星" to Regex("土星"),
        )
    }
}
