package jp.jig.glasses.sample.kmp.starmap

import kotlin.math.sqrt

/**
 * 天体アライメント（段階 2）の計算。仕様は docs/team-e/alignment-accuracy.md。
 *
 * 段階 1（スマホ同期）に残るのは地磁気そのものの誤差 ±5〜15° で、星図を空に重ねる要求（±2〜3°）
 * には届かない。**十字を天体に重ねてもらえば、方位の基準を地磁気から天体へ移せる。**
 *
 * 光学シースルー HMD の較正としては SPAAM（十字を世界の点に複数姿勢から合わせる）の特殊形で、
 * **相手が無限遠なので眼の位置の未知が式から落ちる**のが効きどころ。腕の長さのスマホに
 * 合わせる段階 1 では、眼の位置ずれ 10cm がそのまま 11° になっていた。
 *
 * Android に依存させず、JVM テストで固定できる形にしてある。
 */

/** 「その姿勢のとき、パネルのこの位置に、この天体が重なっていた」1 点ぶん。 */
data class AlignmentCorrespondence(
    val atMs: Long,
    val glassYawDeg: Double,
    val glassPitchDeg: Double,
    val panelX: Double,
    val panelY: Double,
    val targetName: String,
    val targetAzDeg: Double,
    val targetAltDeg: Double,
)

/**
 * 解と、その解で対応点をどれだけ説明できていないか。
 *
 * **未知は方位・仰角の 2 つだけなので、2 点目は精度ではなく検証に効く**（残差として出る）。
 * 「離れた 2 天体で作り、3 つ目は同定の確認に使う」Celestron SkyAlign と同じ考え方。
 */
data class AlignmentSolution(
    val headingOffsetDeg: Double,
    val pitchOffsetDeg: Double,
    val residualsDeg: List<Double>,
    val rmsResidualDeg: Double,
    val maxResidualDeg: Double,
    val count: Int,
)

class CelestialAlignment(
    private val fovDeg: Double = ObservationDefaults.STAR_MAP_FOV_DEG,
    private val width: Int = STAR_MAP_WIDTH,
    private val height: Int = STAR_MAP_HEIGHT,
) {
    private val scale = projectionScale(width, fovDeg)

    fun solve(correspondences: List<AlignmentCorrespondence>): AlignmentSolution? {
        if (correspondences.isEmpty()) return null
        val headings = ArrayList<Double>(correspondences.size)
        val pitches = ArrayList<Double>(correspondences.size)
        for (c in correspondences) {
            val look = lookFor(c)
            headings += headingOffsetFor(look.azDeg, c.glassYawDeg)
            pitches += look.altDeg - c.glassPitchDeg
        }
        val heading = CalibrationEstimator.circularMeanDeg(headings)
        val pitch = pitches.average()
        val residuals = correspondences.map { residualDeg(it, heading, pitch) }
        return AlignmentSolution(
            headingOffsetDeg = heading,
            pitchOffsetDeg = pitch,
            residualsDeg = residuals,
            rmsResidualDeg = sqrt(residuals.sumOf { it * it } / residuals.size),
            maxResidualDeg = residuals.max(),
            count = correspondences.size,
        )
    }

    /** その解で描いたときに、天体がパネル上のどこへ来るか。合った／合っていないを度で言うために使う */
    fun residualDeg(
        correspondence: AlignmentCorrespondence,
        headingOffsetDeg: Double,
        pitchOffsetDeg: Double,
    ): Double {
        val look = look(
            azimuthFromYaw(correspondence.glassYawDeg, headingOffsetDeg),
            correspondence.glassPitchDeg + pitchOffsetDeg,
        )
        val basis = Basis(look.azDeg, look.altDeg)
        val drawn = unproject(correspondence.panelX, correspondence.panelY, basis, scale, width, height)
        return angleBetweenDeg(drawn, enu(correspondence.targetAzDeg, correspondence.targetAltDeg))
    }

    /**
     * 「パネルのこの位置に天体が来る」視線を逆算する。
     *
     * 十字は中央に出すので普段は 1 周目で天体そのものが返るが、
     * 中央以外に置いた対応点（将来の画角合わせ）でも解けるように反復で追い込む。
     */
    private fun lookFor(correspondence: AlignmentCorrespondence): Look {
        val target = enu(correspondence.targetAzDeg, correspondence.targetAltDeg)
        var current = look(correspondence.targetAzDeg, correspondence.targetAltDeg)
        repeat(ITERATIONS) {
            val basis = Basis(current.azDeg, current.altDeg)
            val drawn = unproject(correspondence.panelX, correspondence.panelY, basis, scale, width, height)
            if (angleBetweenDeg(drawn, target) < CONVERGED_DEG) return current
            val moved = rotateAligning(drawn, target, enu(current.azDeg, current.altDeg))
            val angles = horizontalAngles(moved)
            current = look(angles[0], angles[1])
        }
        return current
    }

    /** 描画側（[StarMapScreen]）と同じ丸め方にしないと、残差に見えない差が乗る */
    private fun look(azDeg: Double, altDeg: Double): Look =
        Look((normalizeDeg(azDeg) + 360.0) % 360.0, altDeg.coerceIn(-90.0, 90.0))

    private companion object {
        const val ITERATIONS = 6
        const val CONVERGED_DEG = 1e-6
    }
}

/** 静止しているか、どれだけ続いているか。十字を重ねたまま止めてもらう判定に使う */
data class AlignmentHoldState(
    val yawDeg: Double,
    val pitchDeg: Double,
    val yawStdDeg: Double,
    val pitchStdDeg: Double,
    val sampleCount: Int,
    val heldMs: Long,
    val steady: Boolean,
    val confirmed: Boolean,
)

/**
 * 十字を天体に重ねたまま静止した区間を拾う。
 *
 * **ボタンでは取れない。** ツルをタップすると頭が動くうえ、立って空を見上げていると
 * 体が揺れる（SPAAM の研究でも姿勢の揺れが主要な誤差源）。**押した瞬間ではなく、
 * 静止が続いた区間の平均**を対応点にする。
 */
class AlignmentHold(
    private val windowMs: Long = WINDOW_MS,
    private val minSamples: Int = MIN_SAMPLES,
    private val maxStdDeg: Double = MAX_STD_DEG,
    private val requiredHoldMs: Long = REQUIRED_HOLD_MS,
) {
    private class Sample(val atMs: Long, val yawDeg: Double, val pitchDeg: Double)

    private val samples = ArrayDeque<Sample>()
    private var steadySinceMs: Long? = null

    fun reset() {
        samples.clear()
        steadySinceMs = null
    }

    fun add(atMs: Long, yawDeg: Double, pitchDeg: Double): AlignmentHoldState {
        samples.addLast(Sample(atMs, yawDeg, pitchDeg))
        while (samples.isNotEmpty() && atMs - samples.first().atMs > windowMs) samples.removeFirst()

        val yaws = samples.map { it.yawDeg }
        val pitchList = samples.map { it.pitchDeg }
        val yawStd = CalibrationEstimator.circularStdDeg(yaws)
        val pitchStd = CalibrationEstimator.standardDeviation(pitchList)
        val filled = samples.size >= minSamples && atMs - samples.first().atMs >= windowMs / 2
        val steady = filled && yawStd <= maxStdDeg && pitchStd <= maxStdDeg
        if (steady) {
            if (steadySinceMs == null) steadySinceMs = samples.first().atMs
        } else {
            steadySinceMs = null
        }
        val held = steadySinceMs?.let { atMs - it } ?: 0L
        return AlignmentHoldState(
            yawDeg = CalibrationEstimator.circularMeanDeg(yaws),
            pitchDeg = if (pitchList.isEmpty()) 0.0 else pitchList.average(),
            yawStdDeg = yawStd,
            pitchStdDeg = pitchStd,
            sampleCount = samples.size,
            heldMs = held,
            steady = steady,
            confirmed = steady && held >= requiredHoldMs,
        )
    }

    companion object {
        const val WINDOW_MS = 1_200L
        const val MIN_SAMPLES = 6
        const val MAX_STD_DEG = 0.8
        const val REQUIRED_HOLD_MS = 3_000L
    }
}

/**
 * 段階 1 のヨーは押した瞬間の生値と結び付いているが、段階 2 は首を振って何十秒か使うので、
 * その間に生のヨーが 0.74°/秒 流れる。**段階 2 の間だけドリフト補正後のヨーで対応点を作り、
 * 最後に「補正後 − 生」の差を足して、生のヨー基準のオフセットへ戻す。**
 *
 * 観測画面の [YawDriftCorrector] は入った時点の生のヨーから始まるので、
 * この橋渡しを入れないと段階 2 で溜めた補正ぶんだけ星図がずれる。
 */
fun bridgeToRawYaw(headingOffsetDeg: Double, correctedYawDeg: Double, rawYawDeg: Double): Double =
    normalizeDeg(headingOffsetDeg - normalizeDeg(correctedYawDeg - rawYawDeg))

/** 残差から、その解で何ができるかを日本語で言う。数字だけ出しても使う人には判断できない */
fun alignmentGrade(residualDeg: Double): String = when {
    residualDeg <= 1.0 -> "星に重なる精度"
    residualDeg <= 3.0 -> "星図を空に重ねられる精度"
    residualDeg <= 10.0 -> "星座を言い当てられる精度"
    else -> "合っていない。やり直したほうがよい"
}

/** 2 点目を離して取れているか。SkyAlign も SPAAM の研究も「点は広く散らせ」で一致している */
fun separationDeg(a: AlignmentCorrespondence, b: AlignmentCorrespondence): Double =
    angleBetweenDeg(enu(a.targetAzDeg, a.targetAltDeg), enu(b.targetAzDeg, b.targetAltDeg))

/**
 * 星図を空に重ねる要求（±2〜3°）に入っているかの境目。
 *
 * **これを超えた解を隠さない。** 2 点目で残差が出るのは「取り違えた」という情報なので、
 * 見た目のよい 1 点目の解に差し替えてしまうと、間違いに気づく唯一の手段が消える。
 */
const val ACCEPTABLE_RESIDUAL_DEG = 3.0
