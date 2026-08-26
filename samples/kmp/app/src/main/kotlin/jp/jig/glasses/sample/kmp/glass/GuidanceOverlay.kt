package jp.jig.glasses.sample.kmp.glass

import jp.jig.glasses.sample.kmp.sky.GuidanceFrame
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/** 星図を送り直さず約10Hzで差し替える、小さな天体案内表示。 */
data class GuidanceOverlay(val width: Int, val height: Int, val gray: ByteArray) {
    val compressedBytes: Int
        get() = StarMap(width, height, gray, emptyList()).compressedSizeBytes()
    val bufferUsageBytes: Int
        get() = width * height * 2 + compressedBytes
}

/**
 * 案内の小画像を 1 枚焼く。
 *
 * **面で塗り、最上段の階調だけを使う。** 屋外の空に重ねるので、
 * 細線と中間の階調は消える（[GuidanceIndicatorGeometry.Arrow]）。
 */
fun guidanceOverlay(frame: GuidanceFrame): GuidanceOverlay {
    val box = guidanceIndicatorBox(frame)
    val gray = ByteArray(box.width * box.height)
    val span = box.span.toDouble()
    val centerX = box.width / 2.0
    val centerY = box.height / 2.0
    fun x(point: GuidancePoint) = centerX + point.x * span
    fun y(point: GuidancePoint) = centerY + point.y * span
    when (val geometry = guidanceIndicatorGeometry(frame)) {
        is GuidanceIndicatorGeometry.Arrival -> {
            val stroke = (span * geometry.strokeHalfWidth).roundToInt().coerceAtLeast(1)
            circle(gray, box, centerX, centerY, span * geometry.innerRadius, INK_LIT, stroke)
            circle(gray, box, centerX, centerY, span * geometry.outerRadius, INK_LIT, stroke)
            sparkle(gray, box, centerX, centerY, span * geometry.starArm, stroke)
        }
        is GuidanceIndicatorGeometry.Arrow -> {
            // 軸は「太い線」ではなく塗る帯。半径付きの線で丸い端が付くぶん、
            // 頭の三角と繋がって 1 つの形に見える
            line(
                gray, box,
                x(geometry.tail), y(geometry.tail),
                x(geometry.tip), y(geometry.tip),
                INK_LIT,
                radius = (span * geometry.shaftHalfWidth).roundToInt().coerceAtLeast(1),
            )
            val base = geometry.headBase
            triangle(
                gray, box,
                x(geometry.tip), y(geometry.tip),
                x(base[0]), y(base[0]),
                x(base[1]), y(base[1]),
                INK_LIT,
            )
        }
    }
    return GuidanceOverlay(box.width, box.height, gray)
}


private fun sparkle(
    gray: ByteArray,
    box: GuidanceIndicatorBox,
    cx: Double,
    cy: Double,
    arm: Double,
    stroke: Int,
) {
    line(gray, box, cx - arm * 0.45, cy, cx + arm * 0.45, cy, INK_LIT, stroke)
    line(gray, box, cx, cy - arm, cx, cy + arm, INK_LIT, stroke)
}

/** 頂点 3 点を塗る。辺の符号が揃う画素だけを埋めるので、頂点の並び順は問わない */
private fun triangle(
    gray: ByteArray,
    box: GuidanceIndicatorBox,
    ax: Double,
    ay: Double,
    bx: Double,
    by: Double,
    cx: Double,
    cy: Double,
    value: Int,
) {
    fun edge(x0: Double, y0: Double, x1: Double, y1: Double, px: Double, py: Double) =
        (x1 - x0) * (py - y0) - (y1 - y0) * (px - x0)

    val minX = minOf(ax, bx, cx).toInt().coerceAtLeast(0)
    val maxX = maxOf(ax, bx, cx).roundToInt().coerceAtMost(box.width - 1)
    val minY = minOf(ay, by, cy).toInt().coerceAtLeast(0)
    val maxY = maxOf(ay, by, cy).roundToInt().coerceAtMost(box.height - 1)
    for (py in minY..maxY) {
        for (px in minX..maxX) {
            val fx = px + 0.5
            val fy = py + 0.5
            val w0 = edge(ax, ay, bx, by, fx, fy)
            val w1 = edge(bx, by, cx, cy, fx, fy)
            val w2 = edge(cx, cy, ax, ay, fx, fy)
            val inside = (w0 >= 0.0 && w1 >= 0.0 && w2 >= 0.0) || (w0 <= 0.0 && w1 <= 0.0 && w2 <= 0.0)
            if (inside) gray[py * box.width + px] = value.toByte()
        }
    }
}

private fun circle(
    gray: ByteArray,
    box: GuidanceIndicatorBox,
    cx: Double,
    cy: Double,
    radius: Double,
    value: Int,
    stroke: Int,
) {
    var previousX = cx + radius
    var previousY = cy
    for (step in 1..CIRCLE_STEPS) {
        val angle = step * Math.PI * 2.0 / CIRCLE_STEPS
        val x = cx + cos(angle) * radius
        val y = cy + sin(angle) * radius
        line(gray, box, previousX, previousY, x, y, value, stroke)
        previousX = x
        previousY = y
    }
}

private const val CIRCLE_STEPS = 48

private fun line(
    gray: ByteArray,
    box: GuidanceIndicatorBox,
    x0: Double,
    y0: Double,
    x1: Double,
    y1: Double,
    value: Int,
    radius: Int,
) {
    val steps = max(abs(x1 - x0), abs(y1 - y0)).roundToInt().coerceAtLeast(1)
    for (step in 0..steps) {
        val fraction = step.toDouble() / steps
        val x = (x0 + (x1 - x0) * fraction).roundToInt()
        val y = (y0 + (y1 - y0) * fraction).roundToInt()
        for (oy in -radius..radius) {
            for (ox in -radius..radius) {
                if (ox * ox + oy * oy > radius * radius) continue
                val px = x + ox
                val py = y + oy
                if (px !in 0 until box.width || py !in 0 until box.height) continue
                gray[py * box.width + px] = value.toByte()
            }
        }
    }
}

const val GUIDANCE_OVERLAY_IMAGE_ID = 1
