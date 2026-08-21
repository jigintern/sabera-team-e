package jp.jig.glasses.sample.kmp.ui

import android.graphics.Bitmap
import android.util.Base64
import jp.jig.glasses.sample.kmp.starmap.StarMap
import java.io.ByteArrayOutputStream

/** AIへ送るため、グラスと同じ3bitへ量子化したPNGを作る。 */
internal fun StarMap.toPngBase64(): String {
    val pixels = IntArray(width * height)
    for (index in pixels.indices) {
        val value = gray[index].toInt() and 0xFF
        val quantized = Math.round(value / 255.0 * 7.0).toInt() * 255 / 7
        pixels[index] = (0xFF shl 24) or (quantized shl 16) or (quantized shl 8) or quantized
    }
    val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    val output = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
    bitmap.recycle()
    return Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
}

/** 実機の緑8階調に寄せたスマホ用プレビューを作る。 */
internal fun StarMap.toPreviewBitmap(): Bitmap {
    val pixels = IntArray(width * height)
    for (index in pixels.indices) {
        val value = gray[index].toInt() and 0xFF
        val level = Math.round(value / 255.0 * 7.0) / 7.0
        pixels[index] = (0xFF shl 24) or
            ((56 * level).toInt() shl 16) or
            ((255 * level).toInt() shl 8) or
            (116 * level).toInt()
    }
    return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
}
