package jp.jig.glasses.sample.kmp.starmap

/**
 * 画角を実測するための目印。
 *
 * **星図は背景が真っ黒なので、パネルのどこまでが表示範囲なのかが見えない。**
 * 画角を測るには「端がどこか」が見えないと始まらないので、端と中心に線を出す。
 *
 * ## 測り方 1：星で測る（道具が要らない・屋外）
 *
 * **明るい星を左端の線に合わせ、右端の線に来るまで水平に首を振る。**
 * 星は動かないので、**2 回の視線の角距離がそのまま横の画角**になる。
 *
 * - 巻尺も壁も協力者も要らない。**回転のスケールはファームの融合値が正確**なので信用できる
 * - 星は**中央の横線の高さ**に置く（そこが画角の定義に使っている線）
 * - **首は傾けない。** 傾けるとパネルの横方向が空の水平とずれる
 * - 端の線の中心どうしは全幅より 3px 短いので、そのぶんは [fovFromEdgeAlignment] で戻す
 *
 * ## 測り方 2：壁で測る（昼・幅が測れるものがあるとき）
 *
 * 1. 幅の分かるもの（ドア枠・窓・本棚）の**両端に左右の線がぴったり重なる位置**まで動く
 * 2. そこから対象までの距離を測る
 * 3. `画角 = 2 * atan(幅 / 2 / 距離)`（[fovDeg]）
 *
 * 縦も同じ要領で測れる。**縦横で矛盾したら、どちらかの測り方が間違っている**
 * （パネルは 16:10 なので、横 35° なら縦は 22.4° 付近になる）。
 */
object FovPattern {
    const val EDGE_THICKNESS = 3
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

    /**
     * 同じ星を左端・右端の線に合わせた 2 つの視線から、横の画角[度]を出す。
     *
     * 星は動かないので、2 つの視線の角距離は「左端から右端まで」の見開きそのもの。
     * ただし**線の中心どうしは全幅より [EDGE_THICKNESS] − 1 px 内側**なので、
     * 投影（`r = 2 tan(θ/2)`）の上で全幅ぶんへ引き伸ばして返す。
     */
    fun fovFromEdgeAlignment(
        first: Look,
        second: Look,
        width: Int = STAR_MAP_WIDTH,
        edgeThickness: Int = EDGE_THICKNESS,
    ): Double {
        val separation = angleBetweenDeg(
            enu(first.azDeg, first.altDeg),
            enu(second.azDeg, second.altDeg),
        )
        val spanPx = width - (edgeThickness - 1) * 2
        require(spanPx > 0) { "端が太すぎる: $edgeThickness" }
        val stretch = width.toDouble() / spanPx
        return 4.0 * kotlin.math.atan(stretch * kotlin.math.tan(separation * RAD / 4.0)) * DEG
    }
}
