package jp.jig.glasses.sample.kmp.starmap

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/** 観測画面へ渡す、方位と仰角を同時に補正する結果。 */
data class CalibrationResult(
    val headingOffsetDeg: Double,
    val pitchOffsetDeg: Double,
    val calibratedAt: Long,
    val headingStdDeg: Double,
    val pitchStdDeg: Double,
    val sampleCount: Int,
)

data class CalibrationEstimate(
    val headingOffsetDeg: Double,
    val pitchOffsetDeg: Double,
    val headingStdDeg: Double,
    val pitchStdDeg: Double,
    val phoneMotionStdDeg: Double,
    val glassMotionStdDeg: Double,
    val sampleCount: Int,
    val spanMs: Long,
    val stable: Boolean,
)

/**
 * ボタンを押した瞬間の1サンプルではなく、直近の静止区間から方位・仰角オフセットを求める。
 * 方位は0/360度をまたぐので通常平均ではなく円周平均を使う。
 */
class CalibrationEstimator(
    private val windowMs: Long = WINDOW_MS,
    private val minimumSpanMs: Long = MINIMUM_SPAN_MS,
    private val minimumSamples: Int = MINIMUM_SAMPLES,
    private val maximumHeadingStdDeg: Double = MAX_HEADING_STD_DEG,
    private val maximumPitchStdDeg: Double = MAX_PITCH_STD_DEG,
    private val maximumMotionStdDeg: Double = MAX_MOTION_STD_DEG,
) {
    private data class Sample(
        val atMs: Long,
        val phoneHeadingDeg: Double,
        val phonePitchDeg: Double,
        val glassYawDeg: Double,
        val glassPitchDeg: Double,
    )

    private val samples = ArrayDeque<Sample>()

    fun reset() = samples.clear()

    fun add(
        atMs: Long,
        phoneHeadingDeg: Double,
        phonePitchDeg: Double,
        glassYawDeg: Double,
        glassPitchDeg: Double,
    ): CalibrationEstimate {
        samples.addLast(Sample(atMs, phoneHeadingDeg, phonePitchDeg, glassYawDeg, glassPitchDeg))
        while (samples.isNotEmpty() && atMs - samples.first().atMs > windowMs) samples.removeFirst()

        val headingOffsets = samples.map { normalizeDeg(it.phoneHeadingDeg - it.glassYawDeg) }
        val pitchOffsets = samples.map { it.phonePitchDeg - it.glassPitchDeg }
        val headingMean = circularMeanDeg(headingOffsets)
        val pitchMean = pitchOffsets.average()
        val headingStd = circularStdDeg(headingOffsets)
        val pitchStd = standardDeviation(pitchOffsets)
        val phoneMotion = circularStdDeg(samples.map { it.phoneHeadingDeg })
        val glassMotion = circularStdDeg(samples.map { it.glassYawDeg })
        val pitchMotion = maxOf(
            standardDeviation(samples.map { it.phonePitchDeg }),
            standardDeviation(samples.map { it.glassPitchDeg }),
        )
        val span = (samples.lastOrNull()?.atMs ?: atMs) - (samples.firstOrNull()?.atMs ?: atMs)
        val stable = samples.size >= minimumSamples &&
            span >= minimumSpanMs &&
            headingStd <= maximumHeadingStdDeg &&
            pitchStd <= maximumPitchStdDeg &&
            phoneMotion <= maximumMotionStdDeg &&
            glassMotion <= maximumMotionStdDeg &&
            pitchMotion <= maximumPitchStdDeg

        return CalibrationEstimate(
            headingOffsetDeg = headingMean,
            pitchOffsetDeg = pitchMean,
            headingStdDeg = headingStd,
            pitchStdDeg = pitchStd,
            phoneMotionStdDeg = phoneMotion,
            glassMotionStdDeg = glassMotion,
            sampleCount = samples.size,
            spanMs = span,
            stable = stable,
        )
    }

    companion object {
        const val WINDOW_MS = 1_200L
        const val MINIMUM_SPAN_MS = 800L
        const val MINIMUM_SAMPLES = 6
        const val MAX_HEADING_STD_DEG = 2.0
        const val MAX_PITCH_STD_DEG = 1.5
        const val MAX_MOTION_STD_DEG = 2.0

        internal fun circularMeanDeg(values: List<Double>): Double {
            if (values.isEmpty()) return 0.0
            val x = values.sumOf { cos(it * PI / 180.0) }
            val y = values.sumOf { sin(it * PI / 180.0) }
            return normalizeDeg(Math.toDegrees(atan2(y, x)))
        }

        internal fun circularStdDeg(values: List<Double>): Double {
            if (values.size < 2) return 0.0
            val x = values.sumOf { cos(it * PI / 180.0) }
            val y = values.sumOf { sin(it * PI / 180.0) }
            val r = (sqrt(x * x + y * y) / values.size).coerceIn(1e-12, 1.0)
            return Math.toDegrees(sqrt(-2.0 * ln(r)))
        }

        internal fun standardDeviation(values: List<Double>): Double {
            if (values.size < 2) return 0.0
            val mean = values.average()
            return sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
        }
    }
}
