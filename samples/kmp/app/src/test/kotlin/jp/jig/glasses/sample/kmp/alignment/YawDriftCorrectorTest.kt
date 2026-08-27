package jp.jig.glasses.sample.kmp.alignment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class YawDriftCorrectorTest {
    @Test
    fun `静止中のヨードリフトを方位へ足さない`() {
        val corrector = YawDriftCorrector()
        var result = corrector.update(0.0, 0.0, 0.0, 0.1, 0L)
        for (sample in 1..70) {
            result = corrector.update(-0.074 * sample, 0.0, 0.0, 0.1, sample * 100L)
        }

        assertEquals(0.0, result.yawDeg, 1e-9)
        assertEquals(-0.74, result.driftRateDps, 1e-9)
        assertEquals(-5.18, result.heldDriftDeg, 1e-9)
    }

    @Test
    fun `回転中は実測ドリフトを引いて真の回転量を足す`() {
        val corrector = YawDriftCorrector()
        for (sample in 0..60) {
            corrector.update(-0.074 * sample, 0.0, 0.0, 0.1, sample * 100L)
        }

        val result = corrector.update(-0.074 * 60 + 10.0 - 0.074, 20.0, 0.0, 0.0, 6_100L)
        assertTrue(result.moving)
        assertEquals(10.0, result.yawDeg, 1e-9)
    }

    /**
     * **静止が 5 秒続かなくてもドリフト率を測れる**（#132）。
     *
     * 連続 5 秒の静止を待っていたころは、星を見ながら 3〜4 秒おきに首を動かすと
     * **一度も測れなかった**。そのあいだ `driftRateDps` は 0 のままなので、
     * 動いている間のドリフト（実測 0.74°/秒）が丸ごと方位へ入り、
     * この筋書き（動作 25%・3 分）で **33°** ずれた。方位が増える向きなので、
     * **星図の S が左へ流れていく**。
     *
     * 合計で数えるようにしても 1 回目の推定を 5 秒待っていたころは 0.8° 残っていた
     * （`FIRST_ESTIMATE_AFTER_SECONDS` で詰めた）。
     */
    @Test
    fun `静止が5秒続かなくてもドリフト率を測れる`() {
        val driftDps = -0.74
        val corrector = YawDriftCorrector()
        var trueYaw = 0.0
        var result = corrector.update(0.0, 0.0, 0.0, 0.1, 0L)
        // 10Hz で 3 分。静止 3 秒 → 首振り 1 秒（30°/秒・左右交互）を繰り返す
        for (sample in 1..1_800) {
            val moving = sample % 40 >= 30
            val turnDps = if (sample / 40 % 2 == 0) 30.0 else -30.0
            if (moving) trueYaw += turnDps * 0.1
            val gyro = if (moving) abs(turnDps) else 0.1
            result = corrector.update(
                rawYawDeg = trueYaw + driftDps * sample * 0.1,
                gyroXDps = gyro,
                gyroYDps = 0.0,
                gyroZDps = 0.0,
                timestampMs = sample * 100L,
            )
        }

        assertEquals(driftDps, result.driftRateDps, 0.01)
        // **最初の首振りより前に 1 回目の推定が出る**ので、漏れはほぼ残らない
        assertEquals(trueYaw, result.yawDeg, 0.5)
    }

    /**
     * **1 回目の推定は 2 秒で出す**（#132）。
     *
     * 率が 0 のあいだ補正はまったく効かないので、**待つぶんがそのまま最初のずれになる**。
     * 5 秒待っていたときは、静止 1.5 秒／首振り 1 秒で 2.3° 残った。
     */
    @Test
    fun `1回目のドリフト率は静止2秒で出る`() {
        val corrector = YawDriftCorrector()
        corrector.update(0.0, 0.0, 0.0, 0.1, 0L)
        var firstAtSeconds = 0.0
        for (sample in 1..40) {
            val result = corrector.update(-0.074 * sample, 0.0, 0.0, 0.1, sample * 100L)
            if (result.driftRateDps != 0.0) {
                firstAtSeconds = sample * 0.1
                break
            }
        }
        assertEquals(2.0, firstAtSeconds, 0.15)
        assertEquals(-0.74, corrector.driftRateDps, 1e-9)
    }

    @Test
    fun `角度の折り返しをまたいでも短い側の差分になる`() {
        val corrector = YawDriftCorrector()
        corrector.update(179.0, 10.0, 0.0, 0.0, 0L)
        val result = corrector.update(-176.0, 10.0, 0.0, 0.0, 1_000L)
        assertEquals(-176.0, result.yawDeg, 1e-9)
    }
}
