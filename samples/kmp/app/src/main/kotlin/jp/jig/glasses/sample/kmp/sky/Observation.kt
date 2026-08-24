package jp.jig.glasses.sample.kmp.sky

// 観測の「どこから・どこを向いて・何が見えたか」。
// **ここは Android に触らない。** 星と月惑星の位置はこれだけで決まるので、
// JVM テストで数字を固定できる（Ephemeris の 13 件はこの型だけで書けている）。

/** 観測地と時刻。歳差と緯度の行列はここが変わるまで作り直さなくてよい */
data class Site(val latDeg: Double, val lonDeg: Double)

/** グラスの視線。方位角はキャリブレーション済みの絶対値 */
data class Look(val azDeg: Double, val altDeg: Double)

/** AIへ渡せる、端末の星表と座標計算だけから得た観測事実。 */
data class ObservedStarFact(
    val nameJa: String,
    val magnitude: Double,
    val azDeg: Double,
    val altDeg: Double,
    val distanceFromCenterDeg: Double,
)
