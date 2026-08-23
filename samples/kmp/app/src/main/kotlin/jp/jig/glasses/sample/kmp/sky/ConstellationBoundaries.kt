package jp.jig.glasses.sample.kmp.sky

/** Roman (1987) が並べ替えた IAU 星座境界の1行。座標は B1875.0。 */
data class ConstellationBoundary(
    val raLowHours: Double,
    val raUpHours: Double,
    val decLowDeg: Double,
    val abbreviation: String,
)

class ConstellationBoundaryCatalog(
    private val rows: List<ConstellationBoundary>,
    private val japaneseNames: Map<String, String>,
) {
    init {
        require(rows.isNotEmpty()) { "星座境界表が空です" }
    }

    /** B1875.0 の赤経・赤緯が属する IAU 星座を一意に返す。 */
    fun nameAtB1875(raDeg: Double, decDeg: Double): String {
        val raHours = ((raDeg / 15.0) % 24.0 + 24.0) % 24.0
        val row = rows.firstOrNull {
            decDeg >= it.decLowDeg && raHours >= it.raLowHours && raHours < it.raUpHours
        } ?: error("星座境界に該当しません: RA=$raDeg°, Dec=$decDeg°")
        return japaneseNames[row.abbreviation]
            ?: error("星座 ${row.abbreviation} の日本語名がありません")
    }
}
