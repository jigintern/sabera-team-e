package jp.jig.glasses.sample.kmp.glass

import jp.jig.glasses.sample.kmp.alignment.HeadMotion
import jp.jig.glasses.sample.kmp.sky.Look
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 先出しが**実際に発火するか**を、追従ループを丸ごと模して見る。
 *
 * **なぜ要るか。** [RedrawDeciderTest] は `shouldPredict` に `slowing = true` を直接渡し、
 * [jp.jig.glasses.sample.kmp.alignment.HeadMotionTest] は**ちょうど 100ms 間隔**でしか
 * サンプルを入れていなかった。**どちらも単体では通るのに、繋ぐと一度も発火しない**という
 * 状態が長く残った（`slowing` が 4 点を要求し、`delay(POLL_MS)` の間隔では 4 点目が
 * 300ms の窓から出るため）。部品ごとの固定では見えない繋ぎ目なので、ここで順番ごと再現する。
 */
class PrefetchLoopTest {

    /** 追従ループ 1 周ぶん。`StarMapScreen` の while ループと同じ順で呼ぶ */
    private class Loop(private val intervalMs: Long, startAt: Look) {
        private val motion = HeadMotion()
        private val decider = RedrawDecider()
        private var drawn: Look? = startAt

        // **実機の時刻は epoch ミリ秒。** 0 から始めると [PREDICT_COOLDOWN_MS] が初回を塞ぐ
        private var nowMs = 1_789_000_000_000L

        var predictions = 0
            private set
        var redraws = 0
            private set

        /** 最後に先出しで狙った視線。当たったかを測るために持つ */
        var predictedAim: Look? = null
            private set

        /** 先出しを出した時刻 */
        var predictedAtMs = 0L
            private set

        /** 首が止まったと判定された時刻。**先出しが無ければ、ここまで絵は出ない** */
        var firstSettledAtMs = 0L
            private set

        fun step(look: Look, transferMs: Long = 369L) {
            motion.add(nowMs, look)
            val settled = decider.settle(nowMs, look.azDeg, look.altDeg)
            if (settled && firstSettledAtMs == 0L && predictions + redraws > 0) firstSettledAtMs = nowMs
            val drift = drawn?.let {
                lookSeparationDeg(it.azDeg, it.altDeg, look.azDeg, look.altDeg)
            } ?: Double.MAX_VALUE
            if (decider.shouldRedraw(settled, observationChanged = false, driftDeg = drift, rolledDeg = 0.0)) {
                drawn = look
                redraws++
                decider.onDrawn()
            } else if (decider.shouldPredict(nowMs, drift, motion.slowing)) {
                val aim = motion.predict(look, transferMs + SETTLE_MS, PREDICT_DAMPING)
                decider.onPredicted(nowMs)
                drawn = aim
                predictedAim = aim
                if (predictedAtMs == 0L) predictedAtMs = nowMs
                predictions++
            }
            nowMs += intervalMs
        }
    }

    /**
     * 首を振って止めるまで。**なめらかに加速して減速する**（3u² − 2u³）。
     * 実際の首振りは等速では終わらず、必ず減速して止まる。
     */
    private fun swing(loop: Loop, fromAz: Double, toAz: Double, altDeg: Double, steps: Int) {
        for (i in 0..steps) {
            val u = i.toDouble() / steps
            val eased = u * u * (3.0 - 2.0 * u)
            loop.step(Look(fromAz + (toAz - fromAz) * eased, altDeg))
        }
    }

    /** 振り終わったあと、首を止めたまま待つ */
    private fun hold(loop: Loop, at: Look, steps: Int) = repeat(steps) { loop.step(at) }

    @Test
    fun `実機のサンプル間隔でも先出しが発火する`() {
        // 100ms ちょうどは delay(POLL_MS) では起こらない。**101ms 以上で出ることが肝**
        for (intervalMs in listOf(100L, 101L, 105L, 110L, 130L)) {
            val loop = Loop(intervalMs, Look(0.0, 30.0))
            swing(loop, 0.0, 40.0, 30.0, steps = 10)
            assertTrue("間隔 ${intervalMs}ms で先出しが 1 度も発火しない", loop.predictions >= 1)
        }
    }

    @Test
    fun `1回の首振りで先出しは1枚だけ`() {
        val loop = Loop(105L, Look(0.0, 30.0))
        swing(loop, 0.0, 40.0, 30.0, steps = 10)
        // 1.2 秒の間隔（[PREDICT_COOLDOWN_MS]）で抑えているので、1 回の首振りでは 1 枚に収まる
        assertEquals("1 回の首振りで先出しが複数枚出た", 1, loop.predictions)
    }

    @Test
    fun `止めたままなら先出しは出ない`() {
        val loop = Loop(105L, Look(0.0, 30.0))
        hold(loop, Look(0.0, 30.0), steps = 20)
        assertEquals("止まっているのに先出しが出た", 0, loop.predictions)
    }

    /**
     * **どれだけ早まって、枚数がどうなるか。**
     *
     * 数字は割引（[PREDICT_DAMPING]）と転送時間で動くので**固定しない**。
     * 実機で確かめるときの手がかりとして出す。
     */
    @Test
    fun `先出しの効きを見る`() {
        println()
        println("首振り   先出し  止まった先との残り   そのあとの描き直し  合計  早まった時間")
        for (sweepDeg in listOf(10.0, 20.0, 40.0, 80.0)) {
            val loop = Loop(105L, Look(0.0, 30.0))
            swing(loop, 0.0, sweepDeg, 30.0, steps = 10)
            val rest = Look(sweepDeg, 30.0)
            val residual = loop.predictedAim?.let {
                lookSeparationDeg(it.azDeg, it.altDeg, rest.azDeg, rest.altDeg)
            } ?: -1.0
            val predicted = loop.predictions
            val predictedAt = loop.predictedAtMs
            hold(loop, rest, steps = 8)
            // 先出しが無ければ、絵が出るのは「止まったと判定された時刻」以降
            val gain = if (predictedAt > 0 && loop.firstSettledAtMs > predictedAt) {
                "${loop.firstSettledAtMs - predictedAt}ms 早い"
            } else {
                "—"
            }
            println(
                "${"%5.0f".format(sweepDeg)}°   ${predicted}枚    " +
                    "${"%6.2f".format(residual)}°（しきい値 $REDRAW_DEG°）   " +
                    "${loop.redraws}枚        計${predicted + loop.redraws}枚  $gain",
            )
        }
        println()
        println("※ 残りがしきい値を超えると、止まったあとの描き直しが省けず枚数が 1 枚増える")
        println()
    }
}
