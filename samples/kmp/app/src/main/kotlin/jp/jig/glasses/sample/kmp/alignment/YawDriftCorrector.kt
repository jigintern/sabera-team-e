package jp.jig.glasses.sample.kmp.alignment

import jp.jig.glasses.sample.kmp.sky.normalizeDeg
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/** 1サンプルを反映したあとの、方位補正の状態。 */
data class CorrectedYaw(
    val yawDeg: Double,
    val driftRateDps: Double,
    val heldDriftDeg: Double,
    val moving: Boolean,
    /**
     * ジャイロは止まっていたのに、ヨーが**首振りぶん動いていた**量の合計（#132）。
     *
     * **減速の最後のサンプルで起きる。** これを静止として捨てていたころは、
     * 45〜90° 振って急に戻すたびに、戻したぶんの一部が消えていた。
     */
    val rescuedTurnDeg: Double,
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
    private val stillStepNoiseDeg: Double = STILL_STEP_NOISE_DEG,
    private val stillStepMargin: Double = STILL_STEP_MARGIN,
    private val minStillSeconds: Double = MIN_STILL_SECONDS,
    private val rateFadeSeconds: Double = RATE_FADE_SECONDS,
    private val maxSampleGapSeconds: Double = MAX_SAMPLE_GAP_SECONDS,
) {
    var yawDeg: Double? = null
        private set

    /**
     * 使っているドリフト率。**静止していたサンプルの、古いものを薄めた平均**（#132）。
     *
     * **率そのものが観測の最初の 2 分で上がる。** 実機で 1 分目 −0.214、2 分目 −0.333、
     * 3 分目 −0.348 °/秒 と測れた（そのあとは前のセッションの −0.333/−0.357 と同じ）。
     *
     * - **通算平均では追いつかない。** 最初の低い値をいつまでも引きずるので
     *   0.1°/秒 ぶん足りず、**首を振るたびに漏れて積もる**（10 分で 8〜11°）
     * - **窓ごとに測って重みで平滑化するのも駄目。** 窓 30 秒・重み 0.3 では
     *   4 分ぶん静止しないと追いつかず、**必要な量の 1/3 しか引けていなかった**
     *
     * そこで**同じ 1 本の比に指数の重みを掛ける**（[rateFadeSeconds]）。
     * 分母と分子を同じ係数で薄めるので、**率が一定の間は薄めても値が変わらない**。
     * 変わったときだけ追いかける。
     *
     * [minStillSeconds] たまるまでは 0。**測れていないのに引くと、それ自体がずれになる。**
     */
    val driftRateDps: Double
        get() = if (stillSecondsTotal >= minStillSeconds && weightedStillSeconds > 0.0) {
            weightedStillYawDeg / weightedStillSeconds
        } else {
            0.0
        }

    /** 静止中に捨てたヨーの合計。**動いた直後の 1 サンプルも含む**（捨てた総量なので） */
    var heldDriftDeg: Double = 0.0
        private set

    private var previousRawYawDeg: Double? = null
    private var previousTimestampMs: Long? = null

    /**
     * ドリフト率の分母と分子。**静止していたサンプルだけを足し、古いものを薄める**（#132）。
     *
     * ドリフト率は「静止中の変化 ÷ 静止していた時間」なので、
     * **間に首振りが挟まっても足し直せる**（動いている間のぶんを足さなければ同じ値になる）。
     * 薄めるのは静止していたサンプルのときだけで、**動いている間は止まる**
     * （首を振っている時間の長さで率が動いてしまわないように）。
     */
    private var weightedStillSeconds: Double = 0.0
    private var weightedStillYawDeg: Double = 0.0

    /** 薄めない合計。**使い始めの判定とログ用**（経過時間と突き合わせて欠落を見る） */
    private var stillSecondsTotal: Double = 0.0

    /** 直前のサンプルが動いていたか。**動いた直後の 1 サンプルは測定に使わない** */
    private var wasMoving: Boolean = true

    private var movingSecondsTotal: Double = 0.0
    private var correctionDeg: Double = 0.0

    /** ジャイロでは拾えなかった首振りの量。**効いているかを実機のログで見るために数える** */
    private var rescuedTurnDeg: Double = 0.0

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
        // **ジャイロは「いまこの瞬間動いているか」しか答えない。** 差分はその 100ms の合計なので、
        // **減速の最後のサンプルはジャイロが 2°/秒 を切っているのにヨーが数度動いている**
        // （45〜90° 振って急に戻したとき・#132）。静止として捨てると首振りぶんが消える。
        // ドリフトは 0.35°/秒 なので、**1 サンプルで数度の変化は首振りしかない**
        val turnLimit = stillStepNoiseDeg + abs(driftRateDps) * elapsedSeconds * stillStepMargin
        val turned = abs(step) > turnLimit
        val moving = gyroMagnitude > movingThresholdDps || turned
        if (turned && gyroMagnitude <= movingThresholdDps) rescuedTurnDeg += abs(step)

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
                // **分母と分子を同じ係数で薄める。** 率が一定なら値は変わらず、
                // 変わったときだけ [rateFadeSeconds] の時定数で追いかける
                val fade = exp(-elapsedSeconds / rateFadeSeconds)
                weightedStillSeconds = weightedStillSeconds * fade + elapsedSeconds
                weightedStillYawDeg = weightedStillYawDeg * fade + step
                stillSecondsTotal += elapsedSeconds
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
            rescuedTurnDeg = rescuedTurnDeg,
            stillSecondsTotal = stillSecondsTotal,
            movingSecondsTotal = movingSecondsTotal,
            correctionDeg = correctionDeg,
        )
    }

    companion object {
        /**
         * 「いま動いているか」の境目。
         *
         * **ヨーの変化だけでは判定できない**（ドリフトそのものを「動いている」と読む）。
         * 静止中のジャイロのノイズは 0.1°/秒、首振りは 10〜100°/秒 なので間は広い。
         * **減速の最後のサンプルはこれを下回る**ので、[STILL_STEP_NOISE_DEG] と組で使う。
         */
        const val MOVING_THRESHOLD_DPS = 2.0

        /**
         * 1 サンプルの差分を「ドリフトではなく首振り」と読む境目（#132）。
         *
         * ドリフトは 10Hz の 1 サンプルで **0.035°** しか動かないので、
         * **数度の差分は首振りしかない**。ヨーのノイズより上、
         * いちばん遅い首振り（2°/秒 ＝ 1 サンプル 0.2°）より下では拾えないので、
         * ジャイロのしきい値と組で使う。
         */
        const val STILL_STEP_NOISE_DEG = 1.0

        /** サンプルが飛んだときの上げ幅。**穴が長いほどドリフトぶんの差分も大きい** */
        const val STILL_STEP_MARGIN = 3.0

        /**
         * ドリフト率を使い始めるまでに要る、静止したサンプルの合計秒数。
         *
         * **測れていないのに引くと、それ自体がずれになる。** 一方で待ちすぎると、
         * そのあいだのドリフトが最初のずれとして残る（静止 1.5 秒／首振り 1 秒で
         * 5 秒待つと 2.3°、2 秒なら 0.8°）。
         */
        const val MIN_STILL_SECONDS = 2.0

        /**
         * ドリフト率の時定数。**静止していた時間で数える**（首を振っている間は進まない）。
         *
         * 実機で率は観測の最初の 2 分（静止 50 秒ぶん）で −0.21 → −0.35 へ上がる。
         * 30 分回した計算では、**20 秒で誤差が ±5° に収まる**（通算平均は −7〜−11° で
         * 増え続け、10 秒まで詰めるとノイズを拾って引きすぎる）。
         */
        const val RATE_FADE_SECONDS = 20.0

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
