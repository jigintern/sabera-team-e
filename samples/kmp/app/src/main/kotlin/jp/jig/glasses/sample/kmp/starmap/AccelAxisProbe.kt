package jp.jig.glasses.sample.kmp.starmap

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * ロールを出すための「上・前・右」を、**観測しているだけで自動的に決める**。
 *
 * SDK は生の加速度の軸を文書化していない。「実機で 1 回測る」と決めていたが、その作業は
 * アプリがやる。ユーザーには何も頼まない（空を見上げ下ろしすれば自然に決まる）。
 *
 * ### 加速度だけでは前方が決まらない（実測でつまずいた）
 *
 * はじめは「静止時の前方成分は `sin(ピッチ)` になる」ことを使い、ピッチに対する傾きから
 * 前方を出していた。**これは首の傾きと分離できない。** 静止時の加速度は
 *
 * ```
 * a = 1000 ( sinθ·前 + cosθ·cosφ·上 − cosθ·sinφ·右 )      θ=ピッチ φ=ロール
 * ```
 *
 * なので、**見上げるときに首を傾ける癖があると、その分が前方の推定に入り込む**。
 * 実機では同じ人・同じ機体で **−0.31 → +0.87** まで振れ、取付のずれとして読むと 15° → 42° に
 * なった。**セッションごとに変わる値は取付ではない。**
 *
 * ### ジャイロで取る（採用）
 *
 * **うなずきは「右の軸まわりの回転」そのもの。** ジャイロは回転軸を直接返すので、
 * 重力と違って首の傾きが混ざらない。
 *
 * - **右** = ピッチが動いている間のジャイロの向き（うなずきの回転軸そのもの）
 * - **上と前** = 静止サンプルから解く。静止時の重力は
 *   `a = sinθ·前 + cosθcosφ·上 − cosθsinφ·右` なので、`Σcosθ·a` と `Σsinθ·a` を貯めれば
 *   **上と前について線形に解ける**（ピッチに幅があれば行列式が立つ）
 * - **ロールの混入は右方向にしか出ない。** 右はジャイロで確定しているので、
 *   解いた上と前から右成分を落とせば**首の傾きの癖が消える**。ここが要点
 * - 前の符号は解から直に出るので、勘で決める場所が無い。
 *   ジャイロの回転の向きが右手系かどうかは、確定した基底と突き合わせて**結果として**分かる
 */
data class AccelAxisEstimate(
    val up: Vec3?,
    val right: Vec3?,
    val stillCount: Int,
    val levelCount: Int,
    val nodCount: Int,
    /** うなずきのジャイロがどれだけ同じ向きに揃っているか（1 なら完全に一致） */
    val nodConcentration: Double,
    val pitchSpreadDeg: Double,
    /**
     * 解いた前方が、右方向にどれだけ乗っていたか[度]。
     * **首の傾きの癖の強さ**そのもので、落とした量を見るために持つ
     */
    val rollContaminationDeg: Double,
    /** ジャイロの回転の向きが [Basis] と同じ（右 = 前 × 上）だったか */
    val gyroRightHanded: Boolean?,
    val resolvedBasis: AccelBasis?,
) {
    /** 画面とログに出す 1 行。**判定できていない理由が分かるように書く** */
    fun describe(): String {
        val axes = resolvedBasis?.let { basis ->
            "上=%s 前=%s".format(axisLabel(basis.up), axisLabel(basis.forward))
        } ?: "判定中"
        return "%s 一致=%.2f 首の傾きの混入=%.0f° %s ピッチ幅=%.0f° 静止=%d件（水平%d件）うなずき=%d件%s".format(
            axes,
            nodConcentration,
            rollContaminationDeg,
            when (gyroRightHanded) {
                true -> "右手系"
                false -> "左手系"
                null -> "系不明"
            },
            pitchSpreadDeg,
            stillCount,
            levelCount,
            nodCount,
            if (resolvedBasis != null) " 確定" else "",
        )
    }

    private companion object {
        /** 人が読むための近似。実際の基底は軸に丸めていない */
        fun axisLabel(v: Vec3): String {
            val components = doubleArrayOf(v.x, v.y, v.z)
            val index = components.indices.maxBy { abs(components[it]) }
            val name = arrayOf("X", "Y", "Z")[index]
            val sign = if (components[index] < 0) "-" else "+"
            val purity = abs(components[index])
            return if (purity > 0.97) "$sign$name" else "$sign$name(%.0f%%)".format(purity * 100)
        }
    }
}

class AccelAxisProbe(
    private val minStillSamples: Int = MIN_STILL_SAMPLES,
    private val minLevelSamples: Int = MIN_LEVEL_SAMPLES,
    private val minNodSamples: Int = MIN_NOD_SAMPLES,
    private val minConcentration: Double = MIN_CONCENTRATION,
    private val minPitchSpreadDeg: Double = MIN_PITCH_SPREAD_DEG,
) {
    private var levelSum = Vec3(0.0, 0.0, 0.0)
    private var levelCount = 0
    private var stillCount = 0

    // 上と前を解くための和。cos と sin を別に貯める
    private var cosWeighted = Vec3(0.0, 0.0, 0.0)
    private var sinWeighted = Vec3(0.0, 0.0, 0.0)
    private var sinCosSum = 0.0
    private var cosSquareSum = 0.0
    private var sinSquareSum = 0.0

    // うなずきのジャイロ
    private var nodSum = Vec3(0.0, 0.0, 0.0)
    private var nodMagnitudeSum = 0.0
    private var nodCount = 0

    private var minPitch = Double.MAX_VALUE
    private var maxPitch = -Double.MAX_VALUE
    private var previousPitch: Double? = null
    private var previousAtMs: Long? = null

    fun reset() {
        levelSum = Vec3(0.0, 0.0, 0.0)
        cosWeighted = Vec3(0.0, 0.0, 0.0)
        sinWeighted = Vec3(0.0, 0.0, 0.0)
        nodSum = Vec3(0.0, 0.0, 0.0)
        levelCount = 0
        stillCount = 0
        nodCount = 0
        nodMagnitudeSum = 0.0
        sinCosSum = 0.0
        cosSquareSum = 0.0
        sinSquareSum = 0.0
        minPitch = Double.MAX_VALUE
        maxPitch = -Double.MAX_VALUE
        previousPitch = null
        previousAtMs = null
    }

    /**
     * 1 サンプル反映する。[pitchDeg] は**見上げを正**（観測画面と同じ向き）。
     *
     * 静止しているサンプルは「上」と加速度からの前方に、
     * ピッチが動いているサンプルは「右」に使う。**どちらでもないものは捨てる。**
     */
    fun add(
        accelXMilliG: Double,
        accelYMilliG: Double,
        accelZMilliG: Double,
        gyroXDps: Double,
        gyroYDps: Double,
        gyroZDps: Double,
        pitchDeg: Double,
        atMs: Long,
    ): AccelAxisEstimate {
        val accel = Vec3(accelXMilliG, accelYMilliG, accelZMilliG)
        val gyro = Vec3(gyroXDps, gyroYDps, gyroZDps)
        val magnitude = accel.length()
        val gyroMagnitude = gyro.length()

        val pitchRate = previousPitch?.let { previous ->
            val elapsed = ((atMs - (previousAtMs ?: atMs)) / 1_000.0).takeIf { it > 1e-3 }
            elapsed?.let { (pitchDeg - previous) / it }
        }
        previousPitch = pitchDeg
        previousAtMs = atMs

        val gravityOnly = magnitude in RollEstimator.MIN_MAGNITUDE_MG..RollEstimator.MAX_MAGNITUDE_MG
        if (gravityOnly && gyroMagnitude <= STILL_GYRO_DPS) {
            val unit = accel.normalized()
            stillCount++
            val s = kotlin.math.sin(pitchDeg * RAD)
            val c = kotlin.math.cos(pitchDeg * RAD)
            sinWeighted += unit * s
            cosWeighted += unit * c
            sinCosSum += s * c
            cosSquareSum += c * c
            sinSquareSum += s * s
            if (abs(pitchDeg) <= LEVEL_PITCH_DEG) {
                levelCount++
                levelSum += unit
            }
            minPitch = minOf(minPitch, pitchDeg)
            maxPitch = maxOf(maxPitch, pitchDeg)
        }

        // うなずいている間だけ。首を振りながら（ヨー）だと回転軸が混ざるので落とす
        val up = if (levelCount >= 1) (levelSum / levelCount.toDouble()).normalized() else null
        if (up != null && pitchRate != null && abs(pitchRate) >= MIN_PITCH_RATE_DPS && gyroMagnitude > 1e-6) {
            val horizontal = gyro.dropAlong(up)
            if (horizontal.length() >= NOD_AXIS_RATIO * gyroMagnitude) {
                val direction = horizontal.normalized() * (if (pitchRate >= 0.0) 1.0 else -1.0)
                nodSum += direction
                nodMagnitudeSum += 1.0
                nodCount++
            }
            minPitch = minOf(minPitch, pitchDeg)
            maxPitch = maxOf(maxPitch, pitchDeg)
        }

        return estimate()
    }

    fun estimate(): AccelAxisEstimate {
        val spread = if (stillCount == 0 && nodCount == 0) 0.0 else (maxPitch - minPitch).coerceAtLeast(0.0)
        val up = if (levelCount >= 1) (levelSum / levelCount.toDouble()).normalized() else null
        val concentration = if (nodMagnitudeSum <= 0.0) 0.0 else nodSum.length() / nodMagnitudeSum
        val nodAxis = if (nodCount >= 1 && up != null) nodSum.dropAlong(up).takeIf { it.length() > 1e-9 } else null

        // 静止サンプルから上と前を解く。`a = sinθ·前 + cosθcosφ·上 − cosθsinφ·右` を
        // cos と sin で重み付けした 2 本の式にすると、上と前について線形になる
        val determinant = cosSquareSum * sinSquareSum - sinCosSum * sinCosSum
        var rightHanded: Boolean? = null
        var contamination = 0.0
        var basis: AccelBasis? = null
        if (nodAxis != null && stillCount >= 2 && determinant > DETERMINANT_FLOOR) {
            val right = nodAxis.normalized()
            val solvedUp = (cosWeighted * sinSquareSum - sinWeighted * sinCosSum) / determinant
            val solvedForward = (sinWeighted * cosSquareSum - cosWeighted * sinCosSum) / determinant
            // **ロールの混入は右方向にしか出ない。** 右が確定しているので落とせる
            val cleanUp = solvedUp.dropAlong(right)
            val cleanForward = solvedForward.dropAlong(right)
            if (cleanUp.length() > 1e-6 && cleanForward.length() > 1e-6) {
                contamination = angleBetweenDeg(solvedForward.normalized(), cleanForward.normalized())
                val candidate = AccelBasis(cleanUp, cleanForward)
                // 右 = 前 × 上 と、ジャイロが返した回転の向きが一致したか
                rightHanded = (candidate.right dot right) > 0.0
                basis = candidate
            }
        }

        val resolved = if (
            basis != null &&
            stillCount >= minStillSamples &&
            levelCount >= minLevelSamples &&
            nodCount >= minNodSamples &&
            concentration >= minConcentration &&
            spread >= minPitchSpreadDeg
        ) {
            basis
        } else {
            null
        }

        return AccelAxisEstimate(
            up = up,
            right = nodAxis?.normalized(),
            stillCount = stillCount,
            levelCount = levelCount,
            nodCount = nodCount,
            nodConcentration = concentration,
            pitchSpreadDeg = spread,
            rollContaminationDeg = contamination,
            gyroRightHanded = rightHanded,
            resolvedBasis = resolved,
        )
    }

    companion object {
        /** 静止の判定は [YawDriftCorrector] と同じしきい値にそろえる */
        const val STILL_GYRO_DPS = 2.0
        const val MIN_STILL_SAMPLES = 100
        const val MIN_LEVEL_SAMPLES = 20
        const val MIN_NOD_SAMPLES = 40
        const val MIN_PITCH_SPREAD_DEG = 20.0
        const val LEVEL_PITCH_DEG = 10.0

        /** うなずきと呼べる速さ。手ぶれは 2°/秒 以下、意図した動きは 10°/秒 を超える */
        const val MIN_PITCH_RATE_DPS = 8.0

        /** 回転軸のうち、上向き以外がこの割合を超えていること（ヨーが混ざった動きを落とす） */
        const val NOD_AXIS_RATIO = 0.7

        /** うなずきの回転軸がどれだけ揃っているか。1 なら完全に一致 */
        const val MIN_CONCENTRATION = 0.8

        /** ピッチに幅が無いと上と前が分離できない。行列式でそれを見る */
        const val DETERMINANT_FLOOR = 1.0
    }
}
