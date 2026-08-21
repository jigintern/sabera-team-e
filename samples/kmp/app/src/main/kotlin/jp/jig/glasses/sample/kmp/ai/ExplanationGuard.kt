package jp.jig.glasses.sample.kmp.ai

/**
 * モデルが観測データに無い固有名や、距離・神話などの外部知識を補った文を読み上げない。
 * 正しい知識かを端末だけでは検証できないため、疑わしい文は丸ごと落とす。
 */
class ExplanationGuard(
    private val visibleStarNames: Set<String>,
    private val knownStarNames: Set<String>,
) {
    fun rejectionReason(sentence: String): String? {
        val unexpectedStar = knownStarNames.firstOrNull {
            it !in visibleStarNames && sentence.contains(it)
        }
        if (unexpectedStar != null) return "視野外の星名:$unexpectedStar"
        val unsupported = UNSUPPORTED_FACT.find(sentence)?.value
        return unsupported?.let { "未提供の外部知識:$it" }
    }

    private companion object {
        val UNSUPPORTED_FACT = Regex(
            "光年|パーセク|天文単位|キロメートル|距離は|直径|年齢|" +
                "神話|伝説|由来|ギリシャ|ローマ|[春夏秋冬]の星座",
        )
    }
}
