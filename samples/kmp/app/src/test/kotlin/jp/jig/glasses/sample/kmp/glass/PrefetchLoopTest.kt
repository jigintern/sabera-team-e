package jp.jig.glasses.sample.kmp.glass

import jp.jig.glasses.sample.kmp.alignment.HeadMotion
import jp.jig.glasses.sample.kmp.sky.Look
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 先出しが**発火するかしないか**を、追従ループを丸ごと模して見る。
 *
 * **いまは「発火しない」のが正しい**（#152）。3 点へ緩めて実機で走らせたら、
 * 首を振っている最中に何度も点滅した。`slowing` は「止まる直前」だけでなく
 * **流し見の速度のゆらぎでも成立する**ので、止まる気のない首振り中も
 * [PREDICT_COOLDOWN_MS] ごとに発火し、そのたび 369〜540ms の暗転が入る。
 *
 * **なぜ通しで見るか。** [RedrawDeciderTest] は `shouldPredict` に `slowing = true` を直接渡し、
 * [jp.jig.glasses.sample.kmp.alignment.HeadMotionTest] は**ちょうど 100ms 間隔**でしか
 * サンプルを入れていなかった。**どちらも単体では通るのに、繋ぐと挙動が変わる**。
 * 部品ごとの固定では見えない繋ぎ目なので、ここで順番ごと再現する。
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

    /**
     * **実機の間隔では発火しない。これが止めている栓**（#152）。
     *
     * `delay(POLL_MS = 100)` は「100ms 以上」しか保証しないので、実機の間隔は 100ms を超える。
     * `HeadMotion.slowing` が 4 点を要求している限り、窓には 3 点しか残らず立たない。
     */
    @Test
    fun `実機のサンプル間隔では先出しが発火しない`() {
        for (intervalMs in listOf(101L, 105L, 110L, 130L)) {
            val loop = Loop(intervalMs, Look(0.0, 30.0))
            swing(loop, 0.0, 40.0, 30.0, steps = 10)
            assertEquals(
                "間隔 ${intervalMs}ms で先出しが発火した（首振り中に点滅する）",
                0,
                loop.predictions,
            )
        }
    }

    /**
     * ちょうど 100ms なら 4 点残るので発火する。**そのときも 1 回の首振りで 1 枚まで**
     * （[PREDICT_COOLDOWN_MS]）。生かし直すときに効いてくるので固定しておく。
     */
    @Test
    fun `間隔がちょうど100msなら先出しは1枚だけ出る`() {
        val loop = Loop(100L, Look(0.0, 30.0))
        swing(loop, 0.0, 40.0, 30.0, steps = 10)
        assertEquals("1 回の首振りで先出しが複数枚出た", 1, loop.predictions)
    }

    @Test
    fun `止めたままなら先出しは出ない`() {
        val loop = Loop(105L, Look(0.0, 30.0))
        hold(loop, Look(0.0, 30.0), steps = 20)
        assertEquals("止まっているのに先出しが出た", 0, loop.predictions)
    }

    /**
     * **生かしたら、どれだけ早まって枚数がどうなるか。**
     *
     * いまは実機の間隔では発火しないので、**発火する 100ms 間隔で測る**。
     * 条件を絞って生かし直すとき（`speedDps` の上限を足す案・docs/team-e/73_backlog.md）の
     * 出発点になる。数字は割引（[PREDICT_DAMPING]）と転送時間で動くので**固定しない**。
     */
    @Test
    fun `生かしたときの効きを見る`() {
        println()
        println("首振り   先出し  止まった先との残り   そのあとの描き直し  合計  早まった時間")
        for (sweepDeg in listOf(10.0, 20.0, 40.0, 80.0)) {
            val loop = Loop(100L, Look(0.0, 30.0))
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
