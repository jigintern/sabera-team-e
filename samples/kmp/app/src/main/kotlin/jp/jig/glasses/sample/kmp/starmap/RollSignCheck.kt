package jp.jig.glasses.sample.kmp.starmap

import kotlin.math.abs

/**
 * ロールの符号が合っているかを、**人の記憶に頼らず**確かめる。
 *
 * ロールは重力から出している（`atan2(−a·右, a·上)`）。一方**首を右に傾けるのは
 * 前方軸まわりの正の回転**なので、ジャイロの前方成分がその角速度をそのまま返す。
 * **出処の違う 2 つが一致するかを見れば、符号も倍率も検証できる。**
 *
 * - 傾き ≈ **+1** なら合っている（重力から出したロールの変化 ＝ ジャイロの前方成分）
 * - 傾き ≈ **−1** なら反転している。`右 = 前 × 上` の向きか、`atan2` の符号を疑う
 * - ジャイロの回転の向きが左手系の端末では符号が逆に出るので、
 *   [AccelAxisProbe] が判定した手系で先に揃える（そこは独立に決まっている）
 *
 * うなずき（前ではなく右の軸まわり）や首振り（上の軸まわり）は使わない。
 * **ロールの動きだけを拾う。**
 */
enum class RollSignVerdict {
    /** まだ判定できていない。首を左右に傾ける動きが要る */
    UNKNOWN,

    /** 重力から出したロールとジャイロが一致した。符号は正しい */
    CORRECT,

    /** 符号が反転している。この状態で追従を入れるとずれが 2 倍になる */
    INVERTED,
}

data class RollSignEstimate(
    val slope: Double,
    val sampleCount: Int,
    val verdict: RollSignVerdict,
) {
    fun describe(): String = when (verdict) {
        RollSignVerdict.UNKNOWN ->
            "ロールの符号: 判定中（首を左右に傾けると決まります）%s".format(
                if (sampleCount == 0) "" else " 傾き=%.2f %d件".format(slope, sampleCount),
            )
        RollSignVerdict.CORRECT -> "ロールの符号: 正しい（傾き=%.2f・%d件）".format(slope, sampleCount)
        RollSignVerdict.INVERTED -> "ロールの符号: **反転している**（傾き=%.2f・%d件）".format(slope, sampleCount)
    }
}

class RollSignCheck(
    private val basis: AccelBasis,
    /** [AccelAxisProbe] が判定したジャイロの手系。ここを揃えないと端末の規約で符号が化ける */
    private val gyroRightHanded: Boolean,
    private val minSamples: Int = MIN_SAMPLES,
    private val minSlope: Double = MIN_SLOPE,
) {
    private var previousRoll: Double? = null
    private var previousAtMs: Long? = null
    private var crossSum = 0.0
    private var squareSum = 0.0
    private var count = 0

    fun reset() {
        previousRoll = null
        previousAtMs = null
        crossSum = 0.0
        squareSum = 0.0
        count = 0
    }

    fun add(
        accelXMilliG: Double,
        accelYMilliG: Double,
        accelZMilliG: Double,
        gyroXDps: Double,
        gyroYDps: Double,
        gyroZDps: Double,
        atMs: Long,
    ): RollSignEstimate {
        val accel = Vec3(accelXMilliG, accelYMilliG, accelZMilliG)
        val gyro = Vec3(gyroXDps, gyroYDps, gyroZDps)
        val magnitude = accel.length()
        // 傾ける動きには余分な加速度が乗るので、静止判定より広く取る
        if (magnitude < MIN_MAGNITUDE_MG || magnitude > MAX_MAGNITUDE_MG) return estimate()

        val up = accel dot basis.up
        val lateral = -(accel dot basis.right)
        if (kotlin.math.hypot(lateral, up) < RollEstimator.MIN_PLANAR_MG) return estimate()
        // 平滑化前の値を使う。平滑化した値だと角速度が鈍って倍率が見えない
        val roll = kotlin.math.atan2(lateral, up) * DEG

        val previous = previousRoll
        val previousAt = previousAtMs
        previousRoll = roll
        previousAtMs = atMs
        if (previous == null || previousAt == null) return estimate()
        val elapsed = (atMs - previousAt) / 1_000.0
        if (elapsed < MIN_ELAPSED_SECONDS || elapsed > MAX_ELAPSED_SECONDS) return estimate()

        val measuredRate = normalizeDeg(roll - previous) / elapsed
        val forwardRate = (gyro dot basis.forward) * if (gyroRightHanded) 1.0 else -1.0
        val gyroMagnitude = gyro.length()
        // ロールの動きだけ。うなずき（右軸）や首振り（上軸）が混ざったものは捨てる
        if (abs(forwardRate) < MIN_RATE_DPS || abs(forwardRate) < ROLL_AXIS_RATIO * gyroMagnitude) {
            return estimate()
        }

        crossSum += measuredRate * forwardRate
        squareSum += forwardRate * forwardRate
        count++
        return estimate()
    }

    fun estimate(): RollSignEstimate {
        val slope = if (squareSum <= 0.0) 0.0 else crossSum / squareSum
        val verdict = when {
            count < minSamples || abs(slope) < minSlope -> RollSignVerdict.UNKNOWN
            slope > 0.0 -> RollSignVerdict.CORRECT
            else -> RollSignVerdict.INVERTED
        }
        return RollSignEstimate(slope = slope, sampleCount = count, verdict = verdict)
    }

    companion object {
        const val MIN_SAMPLES = 20

        /** 意図して傾けたときの速さ。手ぶれは 2°/秒 以下 */
        const val MIN_RATE_DPS = 5.0

        /** 回転軸のうち前方成分がこの割合を超えていること */
        const val ROLL_AXIS_RATIO = 0.6

        /** 倍率が鈍っていても符号は読めるので、しきい値は緩くてよい */
        const val MIN_SLOPE = 0.3

        const val MIN_MAGNITUDE_MG = 600.0
        const val MAX_MAGNITUDE_MG = 1500.0
        const val MIN_ELAPSED_SECONDS = 0.05
        const val MAX_ELAPSED_SECONDS = 0.5
    }
}
