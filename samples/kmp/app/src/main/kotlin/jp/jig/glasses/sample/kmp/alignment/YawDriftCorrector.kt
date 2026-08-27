package jp.jig.glasses.sample.kmp.alignment

import jp.jig.glasses.sample.kmp.sky.normalizeDeg
import kotlin.math.sqrt

/** 1サンプルを反映したあとの、方位補正の状態。 */
data class CorrectedYaw(
    val yawDeg: Double,
    val driftRateDps: Double,
    val heldDriftDeg: Double,
    val moving: Boolean,
    /**
     * 静止していた合計時間で割った、**セッション通算のドリフト率**。
     *
     * [driftRateDps] が窓ごとの推定で振れるのに対し、こちらは**session 全体の平均**。
     * どちらが実機の実態に近いかを**ログで突き合わせるために出す**（#132）。
     */
    val longRunRateDps: Double,
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
    private val firstEstimateAfterSeconds: Double = FIRST_ESTIMATE_AFTER_SECONDS,
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

    /**
     * セッション通算のドリフト率（静止中の変化 ÷ 静止していた合計時間）。
     *
     * **窓ごとの [driftRateDps] は実機で −0.014〜−0.396 °/秒 まで振れた**（#132）。
     * ドリフトはハードとファームの性質なので、**長く測るほど確かになる**。
     * いまは補正には使わず、**どちらが実態に近いかを実機で見分けるために出している**。
     */
    val longRunRateDps: Double
        get() = if (stillSecondsTotal > 0.0) stillYawTotalDeg / stillSecondsTotal else 0.0

    private var previousRawYawDeg: Double? = null
    private var previousTimestampMs: Long? = null

    /**
     * 静止していたサンプルの合計時間と、そのあいだにヨーが流れた合計。
     *
     * **動いている間は足さないが、捨てもしない**（#132）。ドリフト率は
     * 「静止中の変化 ÷ 静止していた時間」なので、途中で首を振っても足し直せる。
     */
    private var stillSeconds: Double = 0.0
    private var stillYawDeg: Double = 0.0

    /** 直前のサンプルが動いていたか。**動いた直後の 1 サンプルは測定に使わない** */
    private var wasMoving: Boolean = true

    /**
     * セッション通算の内訳。**窓ごとの推定が実機で振れる**ので、
     * 突き合わせる相手をログへ出せるようにしてある（#132）。
     */
    private var stillSecondsTotal: Double = 0.0
    private var stillYawTotalDeg: Double = 0.0
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
                // **通算も窓と同じサンプルだけで数える。** [heldDriftDeg] は動いた直後の
                // 1 サンプルも含む（捨てた総量なので）ため、分母と揃わない
                stillSecondsTotal += elapsedSeconds
                stillYawTotalDeg += step
                updateDriftEstimate(step, elapsedSeconds)
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
            longRunRateDps = longRunRateDps,
            stillSecondsTotal = stillSecondsTotal,
            movingSecondsTotal = movingSecondsTotal,
            correctionDeg = correctionDeg,
        )
    }

    /**
     * ドリフト率を測り直す。**静止しているサンプルだけを足し続ける**（#132）。
     *
     * 「連続 [estimateAfterSeconds] 秒の静止」を待っていたころは、
     * **それより短い間隔で首を動かし続けると一度も測れなかった**。測れないあいだ
     * [driftRateDps] は 0 のままなので、動いている間のドリフトが丸ごと方位に入る
     * （**実測 0.74°/秒 × 動いていた時間**）。動作 25% で 3 分に 33°、
     * 方位が増える向きにずれるので、**星図の S が左へ流れていく**。
     *
     * ドリフト率は「静止中の変化 ÷ 静止していた時間」なので、
     * **間に首振りが挟まっても足し直せる**（動いている間のぶんを足さなければ同じ値になる）。
     *
     * **1 回目だけ [firstEstimateAfterSeconds] で出す。** 率が 0 のあいだは補正が
     * まったく効かないので、**待つほどそのぶんが最初のずれとして残る**
     * （静止 1.5 秒／首振り 1 秒で 5 秒待つと 2.3°、2 秒なら 0.8°）。
     * 2 回目からは [estimateAfterSeconds] の長い窓へ戻す。**恒久的に窓を短くするほうは採らない**
     * — ヨーのノイズがそのまま率に乗り、±0.5° のノイズで 2 秒窓のままにすると
     * 率が 1.4 倍に太る（3 分で 19°）。
     */
    private fun updateDriftEstimate(stillStepDeg: Double, elapsedSeconds: Double) {
        if (elapsedSeconds <= 0.0) return
        stillSeconds += elapsedSeconds
        stillYawDeg += stillStepDeg
        // 1 回目は早く出し、そのあとは長い窓でならす
        val need = if (driftRateDps == 0.0) firstEstimateAfterSeconds else estimateAfterSeconds
        if (stillSeconds <= need) return

        val measured = stillYawDeg / stillSeconds
        driftRateDps = if (driftRateDps == 0.0) {
            measured
        } else {
            driftRateDps * (1.0 - estimateGain) + measured * estimateGain
        }
        stillSeconds = 0.0
        stillYawDeg = 0.0
    }

    companion object {
        const val MOVING_THRESHOLD_DPS = 2.0

        /**
         * 1 回目の推定を出すまでの、静止したサンプルの合計秒数。
         *
         * **率が 0 のあいだ補正は効かない**ので、ここを待つぶんが最初のずれとして残る。
         * **1 回目は荒くても 0 のままより良い**（2 回目からは [ESTIMATE_AFTER_SECONDS] でならす）。
         */
        const val FIRST_ESTIMATE_AFTER_SECONDS = 2.0

        /**
         * 2 回目以降の窓。**静止したサンプルの合計**で数える。
         *
         * 5 秒だったころ、実機の推定は **−0.014〜−0.396 °/秒 まで振れた**（#132）。
         * ドリフトはハードとファームの性質で、1 セッションのあいだ大きく変わるものではない。
         * **振れの出どころは窓の短さ**（しきい値 2°/秒 より遅い首振りが混ざる）なので、
         * 窓を 6 倍にして薄める。ゆっくりした変化には追随できる。
         */
        const val ESTIMATE_AFTER_SECONDS = 30.0
        const val ESTIMATE_GAIN = 0.3
        const val MAX_SAMPLE_GAP_SECONDS = 0.5
    }
}
