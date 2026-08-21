package jp.jig.glasses.sample.kmp.starmap

import kotlin.math.sqrt

/** 1サンプルを反映したあとの、方位補正の状態。 */
data class CorrectedYaw(
    val yawDeg: Double,
    val driftRateDps: Double,
    val heldDriftDeg: Double,
    val moving: Boolean,
)

/**
 * 静止中に流れる `yawDegrees` を捨て、動いている間だけ差分を積む。
 *
 * AndroidやComposeに依存させず、実測で決めた補正をJVMテストで固定できる形にしてある。
 */
class YawDriftCorrector(
    private val movingThresholdDps: Double = MOVING_THRESHOLD_DPS,
    private val estimateAfterSeconds: Double = ESTIMATE_AFTER_SECONDS,
    private val estimateGain: Double = ESTIMATE_GAIN,
    private val maxSampleGapSeconds: Double = MAX_SAMPLE_GAP_SECONDS,
) {
    var yawDeg: Double? = null
        private set

    var driftRateDps: Double = 0.0
        private set

    var heldDriftDeg: Double = 0.0
        private set

    private var previousRawYawDeg: Double? = null
    private var previousTimestampMs: Long? = null
    private var stillRawYawDeg: Double? = null
    private var stillSinceMs: Long = 0L

    fun update(
        rawYawDeg: Double,
        gyroXDps: Double,
        gyroYDps: Double,
        gyroZDps: Double,
        timestampMs: Long,
    ): CorrectedYaw {
        val previousRaw = previousRawYawDeg
        val elapsedSeconds = previousTimestampMs
            ?.let { (timestampMs - it) / 1_000.0 }
            ?.takeIf { it > 0.0 }
            ?.coerceAtMost(maxSampleGapSeconds)
            ?: 0.0
        val step = normalizeDeg(rawYawDeg - (previousRaw ?: rawYawDeg))
        val gyroMagnitude = sqrt(
            gyroXDps * gyroXDps + gyroYDps * gyroYDps + gyroZDps * gyroZDps,
        )
        val moving = gyroMagnitude > movingThresholdDps

        if (moving) {
            val correctedStep = step - driftRateDps * elapsedSeconds
            yawDeg = normalizeDeg((yawDeg ?: rawYawDeg) + correctedStep)
            stillRawYawDeg = null
        } else {
            heldDriftDeg += step
            yawDeg = yawDeg ?: rawYawDeg
            updateDriftEstimate(rawYawDeg, timestampMs)
        }

        previousRawYawDeg = rawYawDeg
        previousTimestampMs = timestampMs
        return CorrectedYaw(
            yawDeg = requireNotNull(yawDeg),
            driftRateDps = driftRateDps,
            heldDriftDeg = heldDriftDeg,
            moving = moving,
        )
    }

    private fun updateDriftEstimate(rawYawDeg: Double, timestampMs: Long) {
        val startYaw = stillRawYawDeg
        if (startYaw == null) {
            stillRawYawDeg = rawYawDeg
            stillSinceMs = timestampMs
            return
        }
        val heldSeconds = (timestampMs - stillSinceMs) / 1_000.0
        if (heldSeconds <= estimateAfterSeconds) return

        val measured = normalizeDeg(rawYawDeg - startYaw) / heldSeconds
        driftRateDps = if (driftRateDps == 0.0) {
            measured
        } else {
            driftRateDps * (1.0 - estimateGain) + measured * estimateGain
        }
        stillRawYawDeg = rawYawDeg
        stillSinceMs = timestampMs
    }

    companion object {
        const val MOVING_THRESHOLD_DPS = 2.0
        const val ESTIMATE_AFTER_SECONDS = 5.0
        const val ESTIMATE_GAIN = 0.3
        const val MAX_SAMPLE_GAP_SECONDS = 0.5
    }
}
