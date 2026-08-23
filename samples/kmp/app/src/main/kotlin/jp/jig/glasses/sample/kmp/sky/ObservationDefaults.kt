package jp.jig.glasses.sample.kmp.sky

/** 観測画面と方位合わせで共有する、実機確認済みの既定値。 */
object ObservationDefaults {
    /** 測位できないときだけ使う観測地。鯖江 */
    val site = Site(latDeg = 35.9432, lonDeg = 136.1846)

    /** 屋内では測位が返らないことがあるため、待ち続けない。 */
    const val LOCATION_TIMEOUT_MS = 8_000L

    /** 実機に合わせた星図の横画角。パネルそのものの表示画角は未確認。 */
    const val STAR_MAP_FOV_DEG = 35.0

    /** 肉眼と緑8階調の表示で使う恒星の限界等級。 */
    const val LIMIT_MAGNITUDE = 5.0
}
