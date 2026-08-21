package jp.jig.glasses.sample.kmp.starmap

/**
 * 画角を実測するための目印。
 *
 * **星図は背景が真っ黒なので、パネルのどこまでが表示範囲なのかが見えない。**
 * 画角を測るには「端がどこか」が見えないと始まらないので、端と中心に線を出す。
 *
 * 測り方（[docs/team-e/alignment-accuracy.md] の C）:
 * 1. 壁から **2.00m** 離れてまっすぐ壁を向く（壁と視線を直角にする）
 * 2. この目印を出し、**左右の明るい線が壁のどこに見えるか**を誰かに印してもらう
 * 3. 印の間隔 D[m] から `画角 = 2 * atan(D / 2 / 2.00)`。
 *    **D = 1.26m なら 35.0°**、1.44m なら 39.2°、1.08m なら 30.5°
 * 4. 出た値を設定パネルのスライダーに入れる（端末ごとに覚える）
 *
 * 縦も同じ要領で測れる。**縦横で矛盾したら、どちらかの測り方が間違っている**
 * （パネルは 16:10 なので、横 35° なら縦は 22.4° 付近になる）。
 */
object FovPattern {
    private const val EDGE_THICKNESS = 3
    private const val CENTER_VALUE = 150
    private const val EDGE_VALUE = 255

    /** 端（明るい線）と中心（暗い線）だけの画像。真っ黒な背景なので転送は軽い */
    fun grayscale(width: Int = STAR_MAP_WIDTH, height: Int = STAR_MAP_HEIGHT): ByteArray {
        require(width > EDGE_THICKNESS * 2 && height > EDGE_THICKNESS * 2) { "too small: ${width}x$height" }
        val gray = ByteArray(width * height)

        fun column(x: Int, value: Int) {
            if (x !in 0 until width) return
            for (y in 0 until height) gray[y * width + x] = value.toByte()
        }

        fun row(y: Int, value: Int) {
            if (y !in 0 until height) return
            for (x in 0 until width) gray[y * width + x] = value.toByte()
        }

        for (i in 0 until EDGE_THICKNESS) {
            column(i, EDGE_VALUE)
            column(width - 1 - i, EDGE_VALUE)
            row(i, EDGE_VALUE)
            row(height - 1 - i, EDGE_VALUE)
        }
        // 中心は暗くする。端と見間違えると測った値が倍になる
        column(width / 2, CENTER_VALUE)
        row(height / 2, CENTER_VALUE)
        return gray
    }

    /** 壁までの距離と印の間隔から画角[度]を出す。UI に出す換算表もこれで作る */
    fun fovDeg(markSpacingMeters: Double, wallDistanceMeters: Double): Double =
        2.0 * Math.toDegrees(kotlin.math.atan(markSpacingMeters / 2.0 / wallDistanceMeters))
}
