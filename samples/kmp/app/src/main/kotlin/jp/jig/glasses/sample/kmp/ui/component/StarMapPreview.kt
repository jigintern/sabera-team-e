package jp.jig.glasses.sample.kmp.ui.component

import android.graphics.Bitmap
import jp.jig.glasses.sample.kmp.glass.PANEL_HEIGHT
import jp.jig.glasses.sample.kmp.glass.PANEL_WIDTH
import jp.jig.glasses.sample.kmp.glass.StarMap
import jp.jig.glasses.sample.kmp.glass.toCanvasElements
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * スマホに出すプレビュー 1 枚。
 *
 * [visibleHeight] は、この絵がパネルの何画素ぶんを映しているか（[cropToLit] で切った後の高さ）。
 * **案内の矢印を同じ大きさで重ねるのに要る**（切ったぶんだけ拡大して見えている）。
 */
internal class PreviewFrame(
    val bitmap: Bitmap,
    val visibleHeight: Int,
    /** 絵の上に重ねる名前。**グラスに出したものと同じ**（[toCanvasElements] の結果から作る） */
    val labels: List<PreviewLabel> = emptyList(),
)

/**
 * プレビューに重ねる名前 1 つ。位置は**絵の中の割合**（0..1）で持つ。
 *
 * 画素で持つと、切ったぶんや画面の大きさが変わるたびに合わなくなる。
 */
internal class PreviewLabel(val text: String, val fx: Float, val fy: Float)

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
    val crop = cropToLit(full, halfWidth, halfHeight)
    return PreviewFrame(crop.bitmap, crop.visibleHeight, previewLabels(crop))
}

/**
 * 絵に重ねる名前を、**グラスへ送るのと同じ並び**から作る。
 *
 * **同伴者が見るのはこの画面。** 名前を出さないと、点の集まりが何なのか分からない
 * （グラス側はテキスト枠で重ねているので、画像には焼かれていない）。
 * 別に選び直すと、グラスと違う名前が並ぶ（#37 と同じ壊れ方）。
 */
private fun StarMap.previewLabels(crop: PreviewFrame): List<PreviewLabel> {
    // 名前の位置はパネルの座標。絵は中央に置いてあるので、そのぶんを引いて絵の中へ戻す
    val offsetX = (PANEL_WIDTH - width) / 2
    val offsetY = (PANEL_HEIGHT - height) / 2
    val left = (width - crop.bitmap.width) / 2
    val top = (height - crop.bitmap.height) / 2
    return toCanvasElements().map { element ->
        val centerX = element.x + element.width / 2 - offsetX - left
        val centerY = element.y + element.height / 2 - offsetY - top
        PreviewLabel(
            text = element.text,
            fx = (centerX / crop.bitmap.width.toFloat()).coerceIn(0f, 1f),
            fy = (centerY / crop.bitmap.height.toFloat()).coerceIn(0f, 1f),
        )
    }
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
