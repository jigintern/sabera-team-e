package jp.jig.glasses.sample.kmp.glass

import jp.jig.glasses.sample.kmp.sky.SkyDarkness

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

    /**
     * 空の暗さに合わせた段。**夜の屋外で設定パネルを開かせない**ためにある。
     *
     * まわりが明るいうちはパネルも明るくないと読めないが、暗くなってからも同じ明るさだと
     * まぶしいだけになる。BGM を太陽高度で切り替えているのと同じ値を使う（時計だと
     * 同じ 19 時が夏と冬で違う空になる）。
     *
     * **夜を [DEFAULT_LEVEL] のままにしてあるのは、実機で読めることが確かめてある段だから。**
     * 目を暗さに慣れさせるならもう 1 段下げたいが、星図が読めなくなっては本末転倒なので、
     * 下げるかどうかは実機で見てから決める（`docs/team-e/field-check.md`）。
     */
    fun forDarkness(darkness: SkyDarkness): Int = when (darkness) {
        SkyDarkness.DAY -> MAX_LEVEL
        SkyDarkness.CIVIL -> MAX_LEVEL - 1
        SkyDarkness.NIGHT -> DEFAULT_LEVEL
    }
}

data class StoredGlassBrightness(
    val configured: Boolean,
    val level: Int,
)
