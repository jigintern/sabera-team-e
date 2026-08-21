package jp.jig.glasses.sample.kmp.starmap

/** 方位合わせの十字。星図とは別の画像 id に置く。 */
object CalibrationMarker {
    const val IMAGE_ID = 1
    const val SIZE = 128
    private const val THICKNESS = 2

    /** 中心を空け、スマホ側のマーカーと重なったことが見える十字を作る。 */
    fun grayscale(size: Int = SIZE): ByteArray {
        require(size > 0) { "size must be positive" }
        val gray = ByteArray(size * size)
        val center = size / 2
        val gap = size / 8
        val arm = size / 2 - 2
        for (thickness in -THICKNESS..THICKNESS) {
            for (distance in gap..arm) {
                for (direction in intArrayOf(distance, -distance)) {
                    val x = center + direction
                    val y = center + thickness
                    if (x in 0 until size && y in 0 until size) gray[y * size + x] = 255.toByte()
                    if (y in 0 until size && x in 0 until size) gray[x * size + y] = 255.toByte()
                }
            }
        }
        return gray
    }
}
