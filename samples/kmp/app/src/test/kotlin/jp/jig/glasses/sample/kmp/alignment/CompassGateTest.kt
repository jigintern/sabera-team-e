package jp.jig.glasses.sample.kmp.alignment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 8 の字のゲート（#65）。
 *
 * **ここが素通りすると、較正していない磁力計で方位を確定する。**
 * その誤差はそのまま星図に乗り、星座の同定（±20°）が崩れる。
 * 逆に止めきると、較正が上がらない端末では方位合わせから先へ一切進めない。
 */
class CompassGateTest {

    private val t0 = 1_789_000_000_000L

    @Test
    fun `精度が足りていれば待たせない`() {
        val gate = CompassGate().advance(t0, accurate = true, prompting = false)
        assertEquals(0L, gate.promptingSince)
        assertFalse(gate.bypassed)
        assertTrue(gate.ready(accurate = true))
    }

    @Test
    fun `精度が足りないうちは通さない`() {
        val gate = CompassGate().advance(t0, accurate = false, prompting = true)
        assertFalse(gate.ready(accurate = false))
    }

    /** 上限のちょうど手前では通さない。**振れば上がる端末を先回りして諦めさせない** */
    @Test
    fun `上限に届くまでは通さない`() {
        val started = CompassGate().advance(t0, accurate = false, prompting = true)
        val almost = started.advance(t0 + COMPASS_PROMPT_LIMIT_MS - 1, accurate = false, prompting = true)
        assertFalse(almost.bypassed)

        val over = almost.advance(t0 + COMPASS_PROMPT_LIMIT_MS, accurate = false, prompting = true)
        assertTrue(over.bypassed)
        assertTrue(over.ready(accurate = false))
    }

    /** 6DoF が来ていない間は精度と関係なく止まっている。**そこを待ち時間に数えない** */
    @Test
    fun `案内を出していない間は数えない`() {
        val started = CompassGate().advance(t0, accurate = false, prompting = true)
        val paused = started.advance(t0 + 10_000, accurate = false, prompting = false)
        assertEquals(0L, paused.promptingSince)

        // 数え直しになるので、ここではまだ通らない
        val resumed = paused.advance(t0 + 10_100, accurate = false, prompting = true)
        val later = resumed.advance(t0 + 20_000, accurate = false, prompting = true)
        assertFalse(later.bypassed)
    }

    /** 一度逃がした人を、姿勢が崩れたくらいでまた 15 秒待たせない */
    @Test
    fun `逃がしたあとに案内が切れても通したままにする`() {
        val bypassed = CompassGate(promptingSince = t0, bypassed = true)
        val paused = bypassed.advance(t0 + 100, accurate = false, prompting = false)
        assertTrue(paused.bypassed)
        assertTrue(paused.ready(accurate = false))
    }

    /** 振って上がったら、赤字ごと畳んで普通の状態へ戻す */
    @Test
    fun `あとから精度が上がったら逃げ道を畳む`() {
        val bypassed = CompassGate(promptingSince = t0, bypassed = true)
        val recovered = bypassed.advance(t0 + 100, accurate = true, prompting = false)
        assertFalse(recovered.bypassed)
        assertEquals(0L, recovered.promptingSince)
    }
}
