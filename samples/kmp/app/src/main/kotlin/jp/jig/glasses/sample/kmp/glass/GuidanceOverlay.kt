package jp.jig.glasses.sample.kmp.glass

import jp.jig.glasses.sample.kmp.sky.GuidanceFrame
import jp.jig.glasses.sample.kmp.sky.RAD
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** 星図を送り直さず約10Hzで差し替える、小さな案内矢印。 */
data class GuidanceOverlay(val gray: ByteArray) {
    val width: Int get() = GUIDANCE_OVERLAY_SIZE
    val height: Int get() = GUIDANCE_OVERLAY_SIZE
    val compressedBytes: Int
        get() = StarMap(width, height, gray, emptyList()).compressedSizeBytes()
}

fun guidanceOverlay(frame: GuidanceFrame): GuidanceOverlay {
    val size = GUIDANCE_OVERLAY_SIZE
    val gray = ByteArray(size * size)
    val center = size / 2.0
    if (frame.arrived) {
        circle(gray, size, center, center, size * 0.24, 255)
        circle(gray, size, center, center, size * 0.34, 210)
        cross(gray, size, center, center, size * 0.10, 255)
        return GuidanceOverlay(gray)
    }

    val angle = frame.arrowClockwiseDeg * RAD
    // 10°以内では短くし、「もう少し」を形でも伝える。
    val half = size * if (frame.near) 0.20 else 0.31
    val dx = sin(angle)
    val dy = -cos(angle)
    val tailX = center - dx * half
    val tailY = center - dy * half
    val tipX = center + dx * half
    val tipY = center + dy * half
    line(gray, size, tailX, tailY, tipX, tipY, 255, radius = 2)

    val head = size * 0.19
    for (offset in listOf(-145.0, 145.0)) {
        val a = angle + offset * RAD
        line(
            gray, size, tipX, tipY,
            tipX + sin(a) * head,
            tipY - cos(a) * head,
            255,
            radius = 2,
        )
    }
    return GuidanceOverlay(gray)
}

private fun cross(gray: ByteArray, size: Int, cx: Double, cy: Double, arm: Double, value: Int) {
    line(gray, size, cx - arm, cy, cx + arm, cy, value, 1)
    line(gray, size, cx, cy - arm, cx, cy + arm, value, 1)
}

private fun circle(gray: ByteArray, size: Int, cx: Double, cy: Double, radius: Double, value: Int) {
    var previousX = cx + radius
    var previousY = cy
    for (step in 1..32) {
        val angle = step * Math.PI * 2.0 / 32.0
        val x = cx + cos(angle) * radius
        val y = cy + sin(angle) * radius
        line(gray, size, previousX, previousY, x, y, value, 1)
        previousX = x
        previousY = y
    }
}

private fun line(
    gray: ByteArray,
    size: Int,
    x0: Double,
    y0: Double,
    x1: Double,
    y1: Double,
    value: Int,
    radius: Int,
) {
    val steps = maxOf(kotlin.math.abs(x1 - x0), kotlin.math.abs(y1 - y0)).roundToInt().coerceAtLeast(1)
    for (step in 0..steps) {
        val fraction = step.toDouble() / steps
        val x = (x0 + (x1 - x0) * fraction).roundToInt()
        val y = (y0 + (y1 - y0) * fraction).roundToInt()
        for (oy in -radius..radius) {
            for (ox in -radius..radius) {
                if (ox * ox + oy * oy > radius * radius) continue
                val px = x + ox
                val py = y + oy
                if (px !in 0 until size || py !in 0 until size) continue
                gray[py * size + px] = value.toByte()
            }
        }
    }
}

const val GUIDANCE_OVERLAY_IMAGE_ID = 1
const val GUIDANCE_OVERLAY_SIZE = 64
