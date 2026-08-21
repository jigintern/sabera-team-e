package jp.jig.glasses.sample.kmp.glass

/**
 * グラス本体の明るさ設定。
 *
 * SDK 0.6.0 は値域を検証しない。team-f の実機調査で受理されたのは0..4だけだったため、
 * 範囲外の値をファームへ送らない境界をここに集約する。
 * team-e のキャンバス表示での段階差は要確認。
 */
object GlassBrightness {
    const val MIN_LEVEL = 0
    const val MAX_LEVEL = 4
    const val DEFAULT_LEVEL = 2

    val levelRange: IntRange = MIN_LEVEL..MAX_LEVEL

    fun normalize(level: Int): Int = level.coerceIn(levelRange)

    fun label(level: Int): String = when (normalize(level)) {
        0 -> "最も暗い"
        1 -> "暗い"
        2 -> "標準"
        3 -> "明るい"
        else -> "最も明るい"
    }

}

data class StoredGlassBrightness(
    val configured: Boolean,
    val level: Int,
)
