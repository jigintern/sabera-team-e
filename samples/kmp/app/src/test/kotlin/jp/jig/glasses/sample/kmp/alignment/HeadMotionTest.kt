package jp.jig.glasses.sample.kmp.alignment

import jp.jig.glasses.sample.kmp.sky.Look
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 首の動きの速さと、**絵が届くころの視線**。
 *
 * 転送に 0.4 秒かかるので「止まってから送る」と、止めてから絵が出るまで
 * 0.5 秒以上グラスが暗い。減速に入った時点で先を読んで送るための計算をここで固定する。
 * **行き過ぎた絵は「合っていない星図」になる**ので、割引と頭打ちが効いていることを見る。
 */
class HeadMotionTest {

    /** 6DoF は 10Hz。窓（300ms）に入る 4 点で 1 回ぶんの首振りを作る */
    private fun samples(motion: HeadMotion, vararg azDeg: Double, altDeg: Double = 0.0) {
        azDeg.forEachIndexed { index, az -> motion.add(index * 100L, Look(az, altDeg)) }
    }

    @Test
    fun `サンプルが無ければ止まっている扱い`() {
        val motion = HeadMotion()
        assertEquals(0.0, motion.speedDps, 0.0)
        assertFalse(motion.slowing)
        // 外挿できないときは、いまの視線をそのまま返す（勝手に先へ進めない）
        val now = Look(120.0, 30.0)
        assertEquals(now.azDeg, motion.predict(now, 400L, 0.6).azDeg, 0.0)
        assertEquals(now.altDeg, motion.predict(now, 400L, 0.6).altDeg, 0.0)
    }

    @Test
    fun `一定の速さで振ると角速度が出る`() {
        val motion = HeadMotion()
        samples(motion, 0.0, 2.0, 4.0, 6.0)
        // 0.3 秒で 6° なので 20°/秒
        assertEquals(20.0, motion.speedDps, 1e-9)
    }

    @Test
    fun `方位の折り返しで速さが跳ねない`() {
        val motion = HeadMotion()
        samples(motion, 357.0, 359.0, 1.0, 3.0)
        // 357° → 3° は +6° で、-354° ではない
        assertEquals(20.0, motion.speedDps, 1e-9)
    }

    @Test
    fun `天頂に近いほど同じ首振りでも空の上の移動は小さい`() {
        val level = HeadMotion().also { samples(it, 0.0, 4.0, 8.0, 12.0, altDeg = 0.0) }
        val overhead = HeadMotion().also { samples(it, 0.0, 4.0, 8.0, 12.0, altDeg = 80.0) }
        assertTrue(overhead.speedDps < level.speedDps)
        // cos(80°) ぶん。方位の生の差だけで見ると 6 倍近く過大に見える
        assertEquals(level.speedDps * 0.1736, overhead.speedDps, 0.05)
    }

    @Test
    fun `後半が緩めば減速とみなす`() {
        val motion = HeadMotion()
        // 前半 30°/秒 → 後半 10°/秒
        samples(motion, 0.0, 4.0, 6.0, 7.0)
        assertTrue(motion.slowing)
    }

    @Test
    fun `一定の速さで振っている間は減速とみなさない`() {
        val motion = HeadMotion()
        samples(motion, 0.0, 2.0, 4.0, 6.0)
        assertFalse(motion.slowing)
    }

    @Test
    fun `サンプルが足りないうちは減速と言わない`() {
        val motion = HeadMotion()
        samples(motion, 0.0, 4.0, 6.0)
        // 3 点では減速と言わない。**割れないからではなく、そう決めているから**（下のテスト）
        assertFalse(motion.slowing)
    }

    /**
     * **実機のサンプル間隔では減速と言わない。これは意図した状態**（#152）。
     *
     * 呼ぶ側（`StarMapScreen` の追従ループ）は `delay(POLL_MS = 100)` から足すので、
     * `delay` が「100ms 以上」しか保証しないぶん**間隔は 100ms を超える**。
     * 窓は 300ms なので 4 点目は出ていき、3 点しか残らない。
     *
     * **つまり `samples.size < 4` が先出しを止めている栓。**
     * 3 点へ緩めて実機で走らせたら（#149 / #150）、首を振っている最中に何度も点滅した。
     * 緩めるなら「ほぼ止まっている」条件を足してから（docs/team-e/73_backlog.md）。
     */
    @Test
    fun `間隔が100msを超えたら減速と読まない`() {
        for (intervalMs in listOf(101L, 105L, 110L, 130L, 150L)) {
            val motion = HeadMotion()
            // 減速の形（前半が速く後半が緩む）を入れても、窓に 3 点しか残らないので立たない
            listOf(0.0, 4.0, 6.0, 7.0).forEachIndexed { index, az ->
                motion.add(index * intervalMs, Look(az, 0.0))
            }
            assertFalse("間隔 ${intervalMs}ms で減速と読んだ（先出しが動いてしまう）", motion.slowing)
        }
    }

    /** ちょうど 100ms なら 4 点残るので、これまでどおり減速と読む */
    @Test
    fun `間隔がちょうど100msなら減速と読む`() {
        val motion = HeadMotion()
        listOf(0.0, 4.0, 6.0, 7.0).forEachIndexed { index, az ->
            motion.add(index * 100L, Look(az, 0.0))
        }
        assertTrue(motion.slowing)
    }

    @Test
    fun `外挿は割引ぶんだけ先へ出す`() {
        val motion = HeadMotion()
        samples(motion, 0.0, 2.0, 4.0, 6.0)
        // 20°/秒 × 0.4 秒 × 割引 0.5 = 4°
        val predicted = motion.predict(Look(6.0, 0.0), 400L, 0.5)
        assertEquals(10.0, predicted.azDeg, 1e-9)
        assertEquals(0.0, predicted.altDeg, 1e-9)
    }

    @Test
    fun `速い首振りでも頭打ちより先は描かない`() {
        val motion = HeadMotion()
        samples(motion, 0.0, 20.0, 40.0, 60.0)
        // 200°/秒 × 2 秒 = 400° 先になるので、頭打ちで止める
        val predicted = motion.predict(Look(60.0, 0.0), 2_000L, 1.0, maxLeadDeg = 5.0)
        assertEquals(65.0, predicted.azDeg, 1e-9)
    }

    @Test
    fun `外挿しても天頂より上は向かない`() {
        val motion = HeadMotion()
        listOf(88.0, 89.0, 90.0).forEachIndexed { index, alt ->
            motion.add(index * 100L, Look(0.0, alt))
        }
        val predicted = motion.predict(Look(0.0, 90.0), 1_000L, 1.0)
        assertEquals(90.0, predicted.altDeg, 1e-9)
    }

    @Test
    fun `外挿した方位は0から360に収まる`() {
        val motion = HeadMotion()
        samples(motion, 352.0, 354.0, 356.0, 358.0)
        val predicted = motion.predict(Look(358.0, 0.0), 400L, 1.0)
        assertTrue("方位が範囲外: ${predicted.azDeg}", predicted.azDeg in 0.0..360.0)
        assertEquals(6.0, predicted.azDeg, 1e-9)
    }

    @Test
    fun `基準が変わったら忘れる`() {
        val motion = HeadMotion()
        samples(motion, 0.0, 2.0, 4.0, 6.0)
        motion.clear()
        assertEquals(0.0, motion.speedDps, 0.0)
        assertFalse(motion.slowing)
    }

    @Test
    fun `窓から出た古いサンプルは速さに効かない`() {
        val motion = HeadMotion(windowMs = 200L)
        motion.add(0L, Look(0.0, 0.0))
        motion.add(100L, Look(30.0, 0.0))
        motion.add(200L, Look(31.0, 0.0))
        motion.add(300L, Look(32.0, 0.0))
        // 最初の激しい 1 点が残っていると 100°/秒 と読む。窓に残るのは後ろの緩い動きだけ
        assertEquals(10.0, motion.speedDps, 1e-9)
    }
}
