package jp.jig.glasses.sample.kmp.ui.component

import android.graphics.Bitmap
import jp.jig.glasses.sample.kmp.glass.StarMap

/**
 * スマホに出すプレビュー。**実機の緑 8 階調に寄せる。**
 *
 * 同伴者はここでグラスの中身を見るので、実機と違う色で出すと「そう見えている」と誤解する。
 * 量子化の段数（3bit = 8 段）もグラスに合わせてある。
 */
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
