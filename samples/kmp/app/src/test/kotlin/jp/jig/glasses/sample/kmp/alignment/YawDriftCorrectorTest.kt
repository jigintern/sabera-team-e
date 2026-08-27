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
     * 合計で数えるようにしても、使い始めを 5 秒待っていたころは 0.8° 残っていた
     * （`MIN_STILL_SECONDS` で詰めた）。
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
        // **最初の首振りより前に率が出る**ので、漏れはほぼ残らない
        assertEquals(trueYaw, result.yawDeg, 0.5)
        // 内訳がログの読み解きに使えること（静止と動作の合計・引いたドリフトの総量）
        assertEquals(45.0, result.movingSecondsTotal, 0.5)
        assertTrue(result.stillSecondsTotal > 120.0)
        assertEquals(-driftDps * result.movingSecondsTotal, result.correctionDeg, 1.0)
    }

    /**
     * **実機で測った筋書きで漏れが残らない**（#132・2026-08-27）。
     *
     * ログの 2 分目は 1 分のうち**静止 27 秒・動作 28 秒**で、通算のドリフト率は
     * **−0.357°/秒**（1 分目 −0.333 とほぼ同じ）。必要な補正は
     * 0.357 × 28 = **+10°/分** だったが、**実際に足していたのは +3°** しかなかった。
     * 窓ごとに測って重み 0.3 で平滑化していたため、**率が 2.5 倍小さい**まま使われていた。
     */
    @Test
    fun `実機で測ったドリフト率と動作の割合で漏れが残らない`() {
        val driftDps = -0.357
        val corrector = YawDriftCorrector()
        var trueYaw = 0.0
        var result = corrector.update(0.0, 0.0, 0.0, 0.1, 0L)
        // 10Hz で 3 分。静止 2 秒 → 首振り 2 秒（30°/秒・左右交互）＝ 動作 50%
        for (sample in 1..1_800) {
            val moving = sample % 40 >= 20
            val turnDps = if (sample / 40 % 2 == 0) 30.0 else -30.0
            if (moving) trueYaw += turnDps * 0.1
            result = corrector.update(
                rawYawDeg = trueYaw + driftDps * sample * 0.1,
                gyroXDps = if (moving) abs(turnDps) else 0.1,
                gyroYDps = 0.0,
                gyroZDps = 0.0,
                timestampMs = sample * 100L,
            )
        }

        assertEquals(driftDps, result.driftRateDps, 0.01)
        // 窓と重みで平滑化していたころは、ここが 20° 近く残っていた
        assertEquals(trueYaw, result.yawDeg, 2.0)
    }

    /**
     * **率は静止 2 秒で使い始める**（#132）。
     *
     * 率が 0 のあいだ補正はまったく効かないので、**待つぶんがそのまま最初のずれになる**。
     * 5 秒待っていたときは、静止 1.5 秒／首振り 1 秒で 2.3° 残った。
     */
    @Test
    fun `ドリフト率は静止2秒で使い始める`() {
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
        // それより前は 0。**測れていないのに引くと、それ自体がずれになる**
        assertEquals(0.0, YawDriftCorrector().driftRateDps, 1e-9)
    }

    /**
     * **率が上がっていくのに追いつく**（#132・2026-08-27）。
     *
     * 実機のログを 1 分ごとに割り直すと、**率そのものが観測の最初の 2 分で上がる**
     * （1 分目 −0.214 → 2 分目 −0.333 → 3 分目 −0.348 °/秒）。
     * 通算平均だと最初の低い値を引きずって **0.1°/秒 足りない**まま首振りのたびに漏れ、
     * 10 分で 8〜11° 溜まった（**「最初はほぼ同じ、最後は目に見えて左」**）。
     *
     * 薄める時定数を 20 秒にすると、**追いついたあとは増えなくなる**。
     */
    @Test
    fun `ドリフト率が上がっていっても追いつく`() {
        val corrector = YawDriftCorrector()
        var trueYaw = 0.0
        var rawDrift = 0.0
        var stillSeconds = 0.0
        var result = corrector.update(0.0, 0.0, 0.0, 0.1, 0L)
        // 10Hz で 10 分。静止 2 秒 → 首振り 2.4 秒（実機の割合＝静止 45%）
        for (sample in 1..6_000) {
            val moving = sample % 44 >= 20
            val turnDps = if (sample / 44 % 2 == 0) 30.0 else -30.0
            if (moving) trueYaw += turnDps * 0.1 else stillSeconds += 0.1
            // 実測のランプ。静止 50 秒ぶんで −0.214 → −0.348 へ上がって落ち着く
            rawDrift += (-0.214 - 0.134 * minOf(1.0, stillSeconds / 50.0)) * 0.1
            result = corrector.update(
                rawYawDeg = trueYaw + rawDrift,
                gyroXDps = if (moving) abs(turnDps) else 0.1,
                gyroYDps = 0.0,
                gyroZDps = 0.0,
                timestampMs = sample * 100L,
            )
        }

        assertEquals(-0.348, result.driftRateDps, 0.01)
        // 通算平均のままだと 9° 残っていた
        assertEquals(trueYaw, result.yawDeg, 4.0)
    }

    /**
     * **ジャイロが止まってもヨーが動いているサンプルを捨てない**（#132・2026-08-27）。
     *
     * 実機の報告は「**45〜90° ヨーして、そこから急激に戻すとずれる**」。
     * ジャイロは**その瞬間**の角速度、差分は**その 100ms の合計**なので、
     * 減速の最後のサンプルは「ジャイロ 2°/秒 未満・ヨーは数度動いた」になる。
     * 静止として捨てていたころは、**この筋書きの 5 分で 124° 消えた**。
     *
     * ドリフトは 1 サンプルで 0.035° しか動かないので、**数度の差分は首振りしかない**。
     */
    @Test
    fun `減速の最後で消える首振りを拾う`() {
        val corrector = YawDriftCorrector()
        var trueYaw = 0.0
        var rawDrift = 0.0
        var result = corrector.update(0.0, 0.0, 0.0, 0.1, 0L)
        // 10Hz で 5 分。1 周期 3.6 秒 = 静止 1 秒 → 右へ 60°（ゆっくり）→ 静止 1 秒
        // → 左へ 60°（0.35 秒で急に戻す）
        for (sample in 1..3_000) {
            // 1 サンプルの間の回転は、細かく刻んで積む（実機のヨーは区間の合計で届く）
            for (sub in 0 until 10) {
                trueYaw += headTurnDps((sample - 1) * 0.1 + sub * 0.01) * 0.01
            }
            rawDrift += DRIFT_DPS * 0.1
            result = corrector.update(
                rawYawDeg = trueYaw + rawDrift,
                // ジャイロは**そのサンプル時点の瞬時値**
                gyroXDps = abs(headTurnDps(sample * 0.1)),
                gyroYDps = 0.0,
                gyroZDps = 0.0,
                timestampMs = sample * 100L,
            )
        }

        // 減速の最後で消えていたぶんが拾えている
        assertTrue("首振りを拾えていない（${result.rescuedTurnDeg}°）", result.rescuedTurnDeg > 100.0)
        // 捨てていたころは 124° 残った
        assertEquals(trueYaw, result.yawDeg, 30.0)
    }

    /** **完全に静止している間は 1 度も拾わない。** ドリフトを首振りと読むと補正が壊れる */
    @Test
    fun `静止しているだけなら首振りを拾わない`() {
        val corrector = YawDriftCorrector()
        var result = corrector.update(0.0, 0.0, 0.0, 0.1, 0L)
        for (sample in 1..3_000) {
            result = corrector.update(DRIFT_DPS * sample * 0.1, 0.0, 0.0, 0.1, sample * 100L)
        }
        assertEquals(0.0, result.rescuedTurnDeg, 1e-9)
        assertEquals(0.0, result.yawDeg, 1e-9)
        assertEquals(DRIFT_DPS, result.driftRateDps, 1e-9)
    }

    @Test
    fun `角度の折り返しをまたいでも短い側の差分になる`() {
        val corrector = YawDriftCorrector()
        corrector.update(179.0, 10.0, 0.0, 0.0, 0L)
        val result = corrector.update(-176.0, 10.0, 0.0, 0.0, 1_000L)
        assertEquals(-176.0, result.yawDeg, 1e-9)
    }

    /**
     * 首の角速度[°/秒]。**1 周期 3.6 秒**で「ゆっくり右へ 60° → 急に左へ 60°」。
     *
     * 戻しは三角形（面積 = 60°）にしてある。**止まりぎわの 100ms で数度動く**のが要点。
     */
    private fun headTurnDps(seconds: Double): Double {
        val t = seconds % TURN_PERIOD_SEC
        return when {
            t < 1.0 -> 0.0
            t < 2.0 -> -60.0
            t < 3.0 -> 0.0
            t < 3.35 -> {
                val u = (t - 3.0) / 0.35
                343.0 * (1.0 - abs(2.0 * u - 1.0))
            }
            else -> 0.0
        }
    }

    private companion object {
        /** 実機で測った、落ち着いたあとのドリフト率 */
        const val DRIFT_DPS = -0.35

        const val TURN_PERIOD_SEC = 3.6
    }
}
