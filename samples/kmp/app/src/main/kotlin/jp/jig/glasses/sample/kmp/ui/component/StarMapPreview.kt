package jp.jig.glasses.sample.kmp.ui.component

import android.graphics.Bitmap
import jp.jig.glasses.sample.kmp.glass.StarMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * スマホに出すプレビュー 1 枚。
 *
 * [visibleHeight] は、この絵がパネルの何画素ぶんを映しているか（[cropToLit] で切った後の高さ）。
 * **案内の矢印を同じ大きさで重ねるのに要る**（切ったぶんだけ拡大して見えている）。
 */
internal class PreviewFrame(val bitmap: Bitmap, val visibleHeight: Int)

/**
 * スマホに出すプレビュー。**実機の緑 8 階調に寄せる。**
 *
 * 同伴者はここでグラスの中身を見るので、実機と違う色で出すと「そう見えている」と誤解する。
 * 量子化の段数（3bit = 8 段）もグラスに合わせてある。
 *
 * **描かれていない黒は落とす**（[cropToLit]）。夜空はほとんどが黒なので、
 * そのまま出すと画面の大半を黒い板が占める。
 */
internal fun StarMap.toPreviewBitmap(): PreviewFrame {
    val pixels = IntArray(width * height)
    var halfWidth = 0
    var halfHeight = 0
    val centerX = (width - 1) / 2.0
    val centerY = (height - 1) / 2.0
    for (index in pixels.indices) {
        val value = gray[index].toInt() and 0xFF
        val level = Math.round(value / 255.0 * 7.0) / 7.0
        pixels[index] = (0xFF shl 24) or
            ((56 * level).toInt() shl 16) or
            ((255 * level).toInt() shl 8) or
            (116 * level).toInt()
        // **切る幅は中心からの距離で測る。** 端を別々に詰めると絵の中心がずれて、
        // 重ねる矢印と首を振った向きが食い違う
        if (value > LIT_THRESHOLD) {
            halfWidth = max(halfWidth, abs(index % width - centerX).roundToInt())
            halfHeight = max(halfHeight, abs(index / width - centerY).roundToInt())
        }
    }
    val full = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    return cropToLit(full, halfWidth, halfHeight)
}

/**
 * 光っている画素を囲む枠まで切り詰める。**中心は動かさず、縦横の比も変えない。**
 *
 * 比を変えるとパネルと違う形になり、「そう見えている」と誤解させる。
 * 何も光っていないうちは切らない（送る前の 1 枚目で枠が消える）。
 */
private fun cropToLit(full: Bitmap, halfWidth: Int, halfHeight: Int): PreviewFrame {
    if (halfWidth == 0 || halfHeight == 0) return PreviewFrame(full, full.height)
    val ratio = full.width / full.height.toDouble()
    var cropWidth = ((halfWidth + MARGIN_PX) * 2).coerceAtMost(full.width)
    var cropHeight = ((halfHeight + MARGIN_PX) * 2).coerceAtMost(full.height)
    if (cropWidth / cropHeight.toDouble() > ratio) {
        cropHeight = (cropWidth / ratio).roundToInt().coerceAtMost(full.height)
        cropWidth = (cropHeight * ratio).roundToInt().coerceAtMost(full.width)
    } else {
        cropWidth = (cropHeight * ratio).roundToInt().coerceAtMost(full.width)
        cropHeight = (cropWidth / ratio).roundToInt().coerceAtMost(full.height)
    }
    if (cropWidth >= full.width && cropHeight >= full.height) return PreviewFrame(full, full.height)
    val left = (full.width - cropWidth) / 2
    val top = (full.height - cropHeight) / 2
    return PreviewFrame(
        Bitmap.createBitmap(full, left, top, cropWidth, cropHeight),
        cropHeight,
    )
}

/** これ以下は「描かれていない」とみなす（量子化の 1 段目にも届かない値） */
private const val LIT_THRESHOLD = 8

/** 切ったあとに残す余白。**点が枠に触れていると切れて見える** */
private const val MARGIN_PX = 12
