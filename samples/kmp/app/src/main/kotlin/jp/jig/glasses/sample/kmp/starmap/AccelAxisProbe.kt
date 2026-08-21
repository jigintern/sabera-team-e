package jp.jig.glasses.sample.kmp.starmap

import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 加速度の軸割り当てを、**観測しているだけで自動的に決める**。
 *
 * ロールを出すには「どの軸が上か・どの軸が前か」が要るが、SDK は生の加速度の軸を文書化していない。
 * 「実機でグラスを水平に置いて 1 回測る」と決めていたが、**その作業は人手でやらなくてよい**。
 *
 * - **上の軸** = 水平に近い姿勢（ピッチがほぼ 0）で重力がいちばん乗っている軸
 * - **前の軸** = 見上げ／見下ろし（ピッチ）に対する**傾きが 1 に近い**軸。
 *   静止時の加速度は世界の上向きそのものなので、前方成分はちょうど `sin θ` になる。
 *   **相関では決められない**（相関は振幅を見ないので、首をいつも同じ側に少し傾けているだけで
 *   左右軸の相関も 1 に近づく）。**傾き＝1 かどうかで見るのが正しい**
 * - **左右の軸** = 残り。符号は外積で決まるので推定しない（右 = 前 × 上）
 *
 * **軸に丸めない。** 実機で測ると視線方向は 1 軸に乗っておらず（−Y と −Z が 0.94 : 0.26 ＝
 * 取付が視線から 15.5° ずれている）、丸めると見上げたときにロールが最大 14° ずれる。
 * 傾きをベクトルとして持ち、上に直交させたものを視線方向として使う（[AccelBasis]）。
 *
 * 空を見ていれば見上げ／見下ろしは自然に起きるので、**ユーザーには何も頼まない**。
 * 判定できたかどうかは [AccelAxisEstimate.resolved] で分かり、観測ログに残る。
 */
data class AccelAxisEstimate(
    val upIndex: Int?,
    val upSign: Int,
    val forwardIndex: Int?,
    val forwardSign: Int,
    /** 前方候補の、sin(ピッチ) に対する傾き（±1 に近いほど確か） */
    val forwardSlope: Double,
    /** 左右候補の傾き。**0 でなくてよい**（取付のずれがここに出る） */
    val lateralSlope: Double,
    /** 上に直交する向きの傾きの大きさ。**これが 1 なら姿勢の前提と合っている** */
    val pitchResponse: Double,
    /** 取付が視線からどれだけ回っているか[度] */
    val mountingOffsetDeg: Double,
    /** 前方候補の相関。傾きの信頼度を見るために持つ */
    val forwardCorrelation: Double,
    val pitchSpreadDeg: Double,
    val sampleCount: Int,
    val levelCount: Int,
    /** 条件を満たしたときだけ非 null。表示とログのための、軸に丸めた形 */
    val resolved: AccelAxes?,
    /** 条件を満たしたときだけ非 null。**ロール推定へ渡すのはこちら**（軸に丸めていない） */
    val resolvedBasis: AccelBasis?,
) {
    /** 画面とログに出す 1 行。**判定できていない理由が分かるように書く** */
    fun describe(): String {
        val axesText = if (upIndex == null || forwardIndex == null) {
            "判定中"
        } else {
            "上=%s%s 前=%s%s".format(
                if (upSign < 0) "-" else "+",
                AXIS_NAMES[upIndex],
                if (forwardSign < 0) "-" else "+",
                AXIS_NAMES[forwardIndex],
            )
        }
        return "%s 傾き=%.2f/%.2f 応答=%.2f 取付ずれ=%.1f° 相関=%.2f ピッチ幅=%.0f° 静止=%d件（水平%d件）%s".format(
            axesText,
            forwardSlope,
            lateralSlope,
            pitchResponse,
            mountingOffsetDeg,
            forwardCorrelation,
            pitchSpreadDeg,
            sampleCount,
            levelCount,
            if (resolved != null) " 確定" else "",
        )
    }

    private companion object {
        val AXIS_NAMES = arrayOf("X", "Y", "Z")
    }
}

class AccelAxisProbe(
    private val stillGyroDps: Double = STILL_GYRO_DPS,
    private val minSamples: Int = MIN_SAMPLES,
    private val minLevelSamples: Int = MIN_LEVEL_SAMPLES,
    private val minPitchSpreadDeg: Double = MIN_PITCH_SPREAD_DEG,
    private val minPitchResponse: Double = MIN_PITCH_RESPONSE,
    private val maxPitchResponse: Double = MAX_PITCH_RESPONSE,
) {
    private val levelSum = DoubleArray(3)
    private var levelCount = 0
    private var count = 0
    private var sinSum = 0.0
    private var sinSquareSum = 0.0
    private val axisSum = DoubleArray(3)
    private val axisSquareSum = DoubleArray(3)
    private val product = DoubleArray(3)
    private var minPitch = Double.MAX_VALUE
    private var maxPitch = -Double.MAX_VALUE

    fun reset() {
        levelSum.fill(0.0)
        axisSum.fill(0.0)
        axisSquareSum.fill(0.0)
        product.fill(0.0)
        levelCount = 0
        count = 0
        sinSum = 0.0
        sinSquareSum = 0.0
        minPitch = Double.MAX_VALUE
        maxPitch = -Double.MAX_VALUE
    }

    /**
     * 静止した 1 サンプルを反映する。[pitchDeg] は**見上げを正**（観測画面と同じ向き）。
     * 動いている間は重力以外の加速度が乗るので捨てる。
     */
    fun add(
        accelXMilliG: Double,
        accelYMilliG: Double,
        accelZMilliG: Double,
        pitchDeg: Double,
        gyroMagnitudeDps: Double,
    ): AccelAxisEstimate {
        val magnitude = sqrt(
            accelXMilliG * accelXMilliG + accelYMilliG * accelYMilliG + accelZMilliG * accelZMilliG,
        )
        val usable = gyroMagnitudeDps <= stillGyroDps &&
            magnitude in RollEstimator.MIN_MAGNITUDE_MG..RollEstimator.MAX_MAGNITUDE_MG
        if (usable) {
            val unit = doubleArrayOf(
                accelXMilliG / magnitude,
                accelYMilliG / magnitude,
                accelZMilliG / magnitude,
            )
            val s = sin(pitchDeg * RAD)
            count++
            sinSum += s
            sinSquareSum += s * s
            for (i in 0..2) {
                axisSum[i] += unit[i]
                axisSquareSum[i] += unit[i] * unit[i]
                product[i] += unit[i] * s
            }
            if (abs(pitchDeg) <= LEVEL_PITCH_DEG) {
                levelCount++
                for (i in 0..2) levelSum[i] += unit[i]
            }
            minPitch = minOf(minPitch, pitchDeg)
            maxPitch = maxOf(maxPitch, pitchDeg)
        }
        return estimate()
    }

    fun estimate(): AccelAxisEstimate {
        val spread = if (count == 0) 0.0 else maxPitch - minPitch
        val up = if (levelCount >= 1) {
            (0..2).maxByOrNull { abs(levelSum[it] / levelCount) }
        } else {
            null
        }
        val slopes = DoubleArray(3) { slope(it) }
        val forwardCandidates = (0..2).filter { it != up }
        val forward = forwardCandidates.maxByOrNull { abs(slopes[it]) }
        val lateral = forwardCandidates.firstOrNull { it != forward }

        val forwardSlope = forward?.let { slopes[it] } ?: 0.0
        val lateralSlope = lateral?.let { slopes[it] } ?: 0.0
        val forwardCorrelation = forward?.let { correlation(it) } ?: 0.0
        val upSign = up?.let { if (levelSum[it] >= 0.0) 1 else -1 } ?: 1
        val forwardSign = if (forwardSlope >= 0.0) 1 else -1

        // 傾きをベクトルとして扱い、上成分を落としたものが視線方向。
        // **軸に丸めない**（実機では前方が 2 軸に 0.94 : 0.26 で混ざっていた）
        val basis = if (up == null) {
            null
        } else {
            val upVector = Vec3(
                if (up == 0) levelSum[0] else 0.0,
                if (up == 1) levelSum[1] else 0.0,
                if (up == 2) levelSum[2] else 0.0,
            ).normalized()
            val slopeVector = Vec3(slopes[0], slopes[1], slopes[2])
            val orthogonal = slopeVector.dropAlong(upVector)
            if (orthogonal.length() < 1e-6) null else AccelBasis(upVector, orthogonal)
        }
        val pitchResponse = Vec3(slopes[0], slopes[1], slopes[2])
            .let { s -> if (up == null) 0.0 else s.dropAlongKeepingLength(basisUp(up, levelSum)).length() }
        val mountingOffset = basis?.mountingOffsetDeg() ?: 0.0

        val resolved = if (
            up != null && forward != null && basis != null &&
            count >= minSamples &&
            levelCount >= minLevelSamples &&
            spread >= minPitchSpreadDeg &&
            pitchResponse >= minPitchResponse &&
            pitchResponse <= maxPitchResponse &&
            abs(levelSum[up] / levelCount) >= MIN_LEVEL_COMPONENT
        ) {
            AccelAxes(
                upIndex = up,
                upSign = upSign,
                forwardIndex = forward,
                forwardSign = forwardSign,
            )
        } else {
            null
        }

        return AccelAxisEstimate(
            upIndex = up,
            upSign = upSign,
            forwardIndex = forward,
            forwardSign = forwardSign,
            forwardSlope = forwardSlope,
            lateralSlope = lateralSlope,
            pitchResponse = pitchResponse,
            mountingOffsetDeg = mountingOffset,
            forwardCorrelation = forwardCorrelation,
            pitchSpreadDeg = if (spread < 0.0) 0.0 else spread,
            sampleCount = count,
            levelCount = levelCount,
            resolved = resolved,
            resolvedBasis = if (resolved != null) basis else null,
        )
    }

    /**
     * sin(ピッチ) に対する各軸の傾き。**前方軸だけがちょうど ±1 になる**。
     * 左右軸は「首をいつも同じ側に傾けている」ぶんしか動かないので 0.1 未満に留まる。
     */
    private fun slope(axis: Int): Double {
        if (count < 2) return 0.0
        val n = count.toDouble()
        val covariance = product[axis] / n - (axisSum[axis] / n) * (sinSum / n)
        val sinVariance = sinSquareSum / n - (sinSum / n) * (sinSum / n)
        if (sinVariance <= 1e-9) return 0.0
        return covariance / sinVariance
    }

    /** 傾きの確かさ。ばらつきがすべて説明できていれば 1 */
    private fun correlation(axis: Int): Double {
        if (count < 2) return 0.0
        val n = count.toDouble()
        val covariance = product[axis] / n - (axisSum[axis] / n) * (sinSum / n)
        val axisVariance = axisSquareSum[axis] / n - (axisSum[axis] / n) * (axisSum[axis] / n)
        val sinVariance = sinSquareSum / n - (sinSum / n) * (sinSum / n)
        if (axisVariance <= 1e-12 || sinVariance <= 1e-12) return 0.0
        return (covariance / sqrt(axisVariance * sinVariance)).coerceIn(-1.0, 1.0)
    }

    private fun basisUp(up: Int, level: DoubleArray): Vec3 = Vec3(
        if (up == 0) level[0] else 0.0,
        if (up == 1) level[1] else 0.0,
        if (up == 2) level[2] else 0.0,
    ).normalized()

    companion object {
        /** 静止の判定は [YawDriftCorrector] と同じしきい値にそろえる */
        const val STILL_GYRO_DPS = 2.0
        const val MIN_SAMPLES = 200
        const val MIN_LEVEL_SAMPLES = 20
        const val MIN_PITCH_SPREAD_DEG = 20.0

        /**
         * 上に直交する向きの傾きは、**取付がどう回っていても大きさ 1 になる**
         * （重力の前方成分はちょうど sin(ピッチ)）。1 から外れるなら姿勢の前提が違う。
         */
        const val MIN_PITCH_RESPONSE = 0.85
        const val MAX_PITCH_RESPONSE = 1.15
        const val LEVEL_PITCH_DEG = 10.0

        /** 水平のとき、上の軸に重力の 9 割以上が乗っていないと軸が直交していない疑い */
        const val MIN_LEVEL_COMPONENT = 0.9
    }
}
