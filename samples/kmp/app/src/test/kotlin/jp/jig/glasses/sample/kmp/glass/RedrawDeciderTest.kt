package jp.jig.glasses.sample.kmp.glass

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 星図を描き直す判断（[RedrawDecider]）。
 *
 * **0.18 秒静止 ＋ 6° 以上**（AGENTS.md の外せない数値）を境界ごと固定する。
 */
class RedrawDeciderTest {

    @Test
    fun `1度を超えて動いたら止まったことにしない`() {
        val d = RedrawDecider()
        d.settle(1_000L, 100.0, 10.0)
        assertFalse(d.settle(1_050L, 102.0, 10.0)) // 2° 動いた直後
        assertFalse(d.settle(1_200L, 102.0, 10.0)) // 150ms しか経っていない
        assertTrue(d.settle(1_231L, 102.0, 10.0)) // 181ms 静止した
    }

    @Test
    fun `ふらつき1度以内は静止のまま`() {
        val d = RedrawDecider()
        assertTrue(d.settle(1_000L, 100.0, 10.0))
        assertTrue(d.settle(1_100L, 100.9, 10.0)) // 6DoF のふらつきは 1° に届かない
    }

    @Test
    fun `描き直すのは6度か傾き5度か観測条件の変化`() {
        val d = RedrawDecider()
        assertFalse(d.shouldRedraw(settled = true, observationChanged = false, driftDeg = 6.0, rolledDeg = 5.0))
        assertTrue(d.shouldRedraw(settled = true, observationChanged = false, driftDeg = 6.1, rolledDeg = 0.0))
        assertTrue(d.shouldRedraw(settled = true, observationChanged = false, driftDeg = 0.0, rolledDeg = 5.1))
        assertTrue(d.shouldRedraw(settled = true, observationChanged = true, driftDeg = 0.0, rolledDeg = 0.0))
        // 動いている間は何があっても送らない（点滅になる）
        assertFalse(d.shouldRedraw(settled = false, observationChanged = true, driftDeg = 99.0, rolledDeg = 99.0))
    }

    @Test
    fun `先出しは減速中だけ、1200msは連発しない`() {
        val d = RedrawDecider()
        assertFalse(d.shouldPredict(10_000L, driftDeg = 10.0, slowing = false))
        assertTrue(d.shouldPredict(10_000L, driftDeg = 10.0, slowing = true))
        d.onPredicted(10_000L)
        assertFalse(d.shouldPredict(11_100L, driftDeg = 10.0, slowing = true)) // 1100ms 後はまだ
        assertTrue(d.shouldPredict(11_201L, driftDeg = 10.0, slowing = true)) // 1201ms 後なら出せる
    }

    @Test
    fun `ふつうの描き直しが通ったら先出しの間隔は数えなおす`() {
        val d = RedrawDecider()
        d.onPredicted(10_000L)
        d.onDrawn()
        assertTrue(d.shouldPredict(10_100L, driftDeg = 10.0, slowing = true))
    }
}
