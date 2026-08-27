package jp.jig.glasses.sample.kmp.alignment

import jp.jig.glasses.sample.kmp.sky.normalizeDeg
import kotlin.math.sqrt

/** 1サンプルを反映したあとの、方位補正の状態。 */
data class CorrectedYaw(
    val yawDeg: Double,
    val driftRateDps: Double,
    val heldDriftDeg: Double,
    val moving: Boolean,
    val stillSecondsTotal: Double,
    val movingSecondsTotal: Double,
    /** 動いている間に足した補正の合計（＝引いたドリフトの総量） */
    val correctionDeg: Double,
)

/**
 * 静止中に流れる `yawDegrees` を捨て、動いている間だけ差分を積む。
 *
 * AndroidやComposeに依存させず、実測で決めた補正をJVMテストで固定できる形にしてある。
 */
class YawDriftCorrector(
    private val movingThresholdDps: Double = MOVING_THRESHOLD_DPS,
    private val minStillSeconds: Double = MIN_STILL_SECONDS,
    private val maxSampleGapSeconds: Double = MAX_SAMPLE_GAP_SECONDS,
) {
    var yawDeg: Double? = null
        private set

    /**
     * 使っているドリフト率。**静止していたサンプルの通算平均**（#132）。
     *
     * 窓ごとに測って平滑化していたころは、実機で **必要な量の 1/3 しか引けていなかった**
     * （2026-08-27。窓 30 秒・重み 0.3 では 4 分ぶん静止しないと追いつかない）。
     * 通算平均なら**測った全部が効く**ので、重みの調整も要らない。
     *
     * [minStillSeconds] たまるまでは 0。**測れていないのに引くと、それ自体がずれになる。**
     */
    val driftRateDps: Double
        get() = if (stillSecondsTotal >= minStillSeconds) stillYawTotalDeg / stillSecondsTotal else 0.0

    /** 静止中に捨てたヨーの合計。**動いた直後の 1 サンプルも含む**（捨てた総量なので） */
    var heldDriftDeg: Double = 0.0
        private set

    private var previousRawYawDeg: Double? = null
    private var previousTimestampMs: Long? = null

    /**
     * ドリフト率の分母と分子。**静止していたサンプルだけを足し続ける**（#132）。
     *
     * ドリフト率は「静止中の変化 ÷ 静止していた時間」なので、
     * **間に首振りが挟まっても足し直せる**（動いている間のぶんを足さなければ同じ値になる）。
     * 実機では 1 分目 −0.333、2 分目 −0.357 °/秒 と**セッション中ほぼ動かない**。
     */
    private var stillSecondsTotal: Double = 0.0
    private var stillYawTotalDeg: Double = 0.0

    /** 直前のサンプルが動いていたか。**動いた直後の 1 サンプルは測定に使わない** */
    private var wasMoving: Boolean = true

    private var movingSecondsTotal: Double = 0.0
    private var correctionDeg: Double = 0.0

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
            val correction = -driftRateDps * elapsedSeconds
            yawDeg = normalizeDeg((yawDeg ?: rawYawDeg) + step + correction)
            movingSecondsTotal += elapsedSeconds
            correctionDeg += correction
        } else {
            heldDriftDeg += step
            yawDeg = yawDeg ?: rawYawDeg
            // **動いていた直後の 1 サンプルは測らない。** その差分にはまだ首振りが残っている
            // （しきい値 2°/秒 を下回るまでの減速ぶん）ので、ドリフト率に混ぜると太る
            if (!wasMoving && elapsedSeconds > 0.0) {
                stillSecondsTotal += elapsedSeconds
                stillYawTotalDeg += step
            }
        }

        wasMoving = moving
        previousRawYawDeg = rawYawDeg
        previousTimestampMs = timestampMs
        return CorrectedYaw(
            yawDeg = requireNotNull(yawDeg),
            driftRateDps = driftRateDps,
            heldDriftDeg = heldDriftDeg,
            moving = moving,
            stillSecondsTotal = stillSecondsTotal,
            movingSecondsTotal = movingSecondsTotal,
            correctionDeg = correctionDeg,
        )
    }

    companion object {
        /**
         * 「動いているか」の境目。
         *
         * ヨーの変化では判定できない（ドリフトそのものを「動いている」と読む）。
         * 静止中のジャイロのノイズは 0.1°/秒、首振りは 10〜100°/秒 なので間は広い。
         */
        const val MOVING_THRESHOLD_DPS = 2.0

        /**
         * ドリフト率を使い始めるまでに要る、静止したサンプルの合計秒数。
         *
         * **測れていないのに引くと、それ自体がずれになる。** 一方で待ちすぎると、
         * そのあいだのドリフトが最初のずれとして残る（静止 1.5 秒／首振り 1 秒で
         * 5 秒待つと 2.3°、2 秒なら 0.8°）。
         */
        const val MIN_STILL_SECONDS = 2.0

        /**
         * 1 サンプルぶんとして数える時間の上限。
         *
         * **実機では 1 分のうち 5 秒（8%）がサンプルとして届いていない**（2026-08-27。
         * 星図の転送が 1 枚 0.3〜0.5 秒 かかる）。0.5 秒で切っていたころは
         * **その穴のあいだのドリフトを引けずに漏らしていた**。
         * ここを広げると、ふつうの取りこぼしは引けるようになる。
         *
         * 上限そのものは残す。`enterNavigationPage()` でヨーの原点が飛ぶことがあり
         * （[71_yaw-drift.md]）、そのときの巨大な差分をドリフトとして引かないため。
         */
        const val MAX_SAMPLE_GAP_SECONDS = 2.0
    }
}
