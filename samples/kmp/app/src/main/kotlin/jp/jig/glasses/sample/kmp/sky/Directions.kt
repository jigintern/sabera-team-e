package jp.jig.glasses.sample.kmp.sky

private val CARDINAL_POINTS_16 = listOf(
    "北", "北北東", "北東", "東北東", "東", "東南東", "南東", "南南東",
    "南", "南南西", "南西", "西南西", "西", "西北西", "北西", "北北西",
)

private val CARDINAL_POINTS_8 = listOf("北", "北東", "東", "南東", "南", "南西", "西", "北西")

/** 方位角を16方位の日本語にする。負値や360°以上も受け付ける。 */
fun cardinalDirection16(azDeg: Double): String = cardinalDirection(azDeg, CARDINAL_POINTS_16)

/** 小さなコンパス表示向けの8方位。 */
fun cardinalDirection8(azDeg: Double): String = cardinalDirection(azDeg, CARDINAL_POINTS_8)

private fun cardinalDirection(azDeg: Double, points: List<String>): String {
    val normalized = ((azDeg % 360.0) + 360.0) % 360.0
    val sector = 360.0 / points.size
    return points[((normalized + sector / 2.0) / sector).toInt() % points.size]
}
