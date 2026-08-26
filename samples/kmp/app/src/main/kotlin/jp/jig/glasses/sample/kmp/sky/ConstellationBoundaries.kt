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

    /**
     * B1875.0 の赤経・赤緯が属する IAU 星座を一意に返す。**該当が無ければ null。**
     *
     * **同梱の表は天球を隙間なく覆っている**ので、いまのアプリからここが null になることは無い
     * （`ConstellationBoundaryCatalogTest` が全 decLow 境界で覆いを検査する）。
     * それでも例外にしないのは、**星図を描いている最中に落ちる価値が無い**ため。
     * 呼び出し側の `StarMapRenderer.constellationAt` は境界表を同梱していない場合の
     * null をすでに畳んでいるので、そこへ合流させる。
     */
    fun nameAtB1875(raDeg: Double, decDeg: Double): String? {
        val raHours = ((raDeg / 15.0) % 24.0 + 24.0) % 24.0
        val row = rows.firstOrNull {
            decDeg >= it.decLowDeg && raHours >= it.raLowHours && raHours < it.raUpHours
        } ?: return null
        return japaneseNames[row.abbreviation]
    }
}
