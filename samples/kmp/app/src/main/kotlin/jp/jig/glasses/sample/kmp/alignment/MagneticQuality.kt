package jp.jig.glasses.sample.kmp.alignment

import kotlin.math.abs

/**
 * 磁気が歪んでいないかを、OS の自己申告ではなく外から検証する。
 *
 * `SensorManager` の `SENSOR_STATUS_ACCURACY_*` は**キャリブレーションが進んだか**を言うだけで、
 * 鉄骨やケースの磁石が近くにあっても「高い」のまま返る。**その状態で方位を合わせると、
 * 誤差がそのまま星図に乗る**（実測で ±5〜15°、屋内では 30° を超える）。
 *
 * ただしその場の磁場の**強さと伏角の期待値は分かっている**（`GeomagneticField`）。
 * 生の磁力計と比べれば、少なくとも「明らかに歪んでいる」場合は弾ける。
 * **誤差を減らす手段ではなく、外れ値で合わせてしまうのを止める手段。**
 */
data class MagneticQuality(
    val measuredMicroTesla: Double,
    val expectedMicroTesla: Double,
    val measuredInclinationDeg: Double,
    val expectedInclinationDeg: Double,
    /** 画面に赤字を出すか。**強さでしか判断しない**（[reason] のコメントを参照） */
    val distorted: Boolean,
    val reason: String?,
) {
    /**
     * 向きが土地の値から外れているか。**画面には出さない**が、
     * 「精度」の詳細行に度数がそのまま並ぶので、切り分けはそこでできる。
     */
    val inclinationOff: Boolean get() = inclinationDiffDeg > MAX_INCLINATION_DIFF_DEG

    val strengthRatio: Double get() = if (expectedMicroTesla <= 0.0) 1.0 else measuredMicroTesla / expectedMicroTesla
    val inclinationDiffDeg: Double get() = abs(measuredInclinationDeg - expectedInclinationDeg)
}

fun magneticQuality(
    measuredMicroTesla: Double,
    measuredInclinationDeg: Double,
    expectedMicroTesla: Double,
    expectedInclinationDeg: Double,
): MagneticQuality {
    val ratio = if (expectedMicroTesla <= 0.0) 1.0 else measuredMicroTesla / expectedMicroTesla
    // **伏角のずれは画面に出さない。** 「磁場の向きが土地の値と 67° 違います」は
    // 読んでも打つ手が変わらないうえ、机の上では出っぱなしになる。
    // 強さの異常だけは原因（磁石・鉄）を名指しできるので、そこだけ言う。
    // 伏角は測り続けて [MagneticQuality.inclinationOff] と詳細行に残す
    val reason = when {
        // **数字は括弧に押し込まない。** 折り返しが半端な位置に来るうえ、
        // 倍率を読んでも打つ手は変わらない（詳しい値は下の一覧に残る）
        ratio > MAX_STRENGTH_RATIO -> "磁石か鉄が近くにあります"
        ratio < MIN_STRENGTH_RATIO -> "金属に囲まれていませんか"
        else -> null
    }
    return MagneticQuality(
        measuredMicroTesla = measuredMicroTesla,
        expectedMicroTesla = expectedMicroTesla,
        measuredInclinationDeg = measuredInclinationDeg,
        expectedInclinationDeg = expectedInclinationDeg,
        distorted = reason != null,
        reason = reason,
    )
}

/**
 * ケースの磁石は数百 µT を出すので比で見れば確実に外れる。
 * 一方で±25% の幅を持たせているのは、**歪みの検知より誤検知の抑止を優先している**ため。
 * 屋外で正しく測れているのに「磁石がある」と言われると、そのまま合わせられなくなる。
 */
const val MIN_STRENGTH_RATIO = 0.75
const val MAX_STRENGTH_RATIO = 1.25

/** 伏角は日本で 45〜60° あり、鉄の近くでは容易に 10° 以上動く */
const val MAX_INCLINATION_DIFF_DEG = 6.0
