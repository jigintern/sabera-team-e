package jp.jig.glasses.sample.kmp.glass

/**
 * 星図の下敷き（星以外）の濃さ。
 *
 * **つまみは 0〜255 ではなく「段」そのもの。** グラスは緑 8 階調しか出せず、
 * 3bit へ落ちるので**「少し暗く」は効かない**（110 と 144 は別の段だが、
 * 130 と 144 は同じ段になる）。段で持てば、動かしたぶんだけ実機で変わる。
 *
 * **屋内で決めた濃さは屋外の暗闇では必ず明るすぎる**ので、正解は現地でしか出ない。
 * だから既定を置いたうえで、その場で動かせるようにしてある。
 *
 * 星はここに入れない。**等級を明るさと点の大きさで表している**ので、
 * 一律に上げ下げすると等級の差が潰れる（空の濃さ＝[jp.jig.glasses.sample.kmp.sky.SkyDensity]
 * とグラス自体の明るさ＝[GlassBrightness] で調節する）。
 * 方位の文字も入れない。**読めなくなったら目印の役に立たない**。
 */
enum class StarMapLayer(
    val label: String,
    val hint: String,
    /** 既定の段（1〜7） */
    val defaultLevel: Int,
    /** 案内中、対象以外を落とす段。上げてあっても案内の主役より暗くする */
    val dimLevel: Int,
) {
    // **星座絵は 2 段目では実機で薄かった**ので 3 に上げた（2026-08-25）。
    // 輪郭を 1 画素で描くので、星（3〜7）と同じ段でも絵が主役になることはない
    ART("星座絵", "なにに見立てたのか", defaultLevel = 3, dimLevel = 1),
    LINE("星座線", "星のつなぎ方", defaultLevel = 4, dimLevel = 2),
    ASTERISM("結び", "大三角・北斗七星", defaultLevel = 5, dimLevel = 2),
    MILKY_WAY("天の川", "帯の縁", defaultLevel = 2, dimLevel = 2),
    ;

    companion object {
        const val MIN_LEVEL = 1
        const val MAX_LEVEL = 7
    }
}

/**
 * 層ごとの段。**既定と違うものだけ覚える**ので、既定を変えたら黙って付いてくる。
 */
data class StarMapInk(val levels: Map<StarMapLayer, Int> = emptyMap()) {

    fun level(layer: StarMapLayer): Int = levels[layer] ?: layer.defaultLevel

    /**
     * 画素に置く値。**段の真ん中を取る**（`value ushr 5` で狙った段に落ちる）。
     * 端の値を使うと、この先で 1 でも足し引きしたときに段がずれる。
     */
    fun value(layer: StarMapLayer): Int = level(layer) * 32 + 16

    /** 案内中、対象以外を落とした値。**上げてある層はそのぶん下げ幅も残す** */
    fun dimValue(layer: StarMapLayer): Int = minOf(layer.dimLevel, level(layer)) * 32 + 16

    fun with(layer: StarMapLayer, level: Int): StarMapInk = StarMapInk(
        levels + (layer to level.coerceIn(StarMapLayer.MIN_LEVEL, StarMapLayer.MAX_LEVEL)),
    )
}
