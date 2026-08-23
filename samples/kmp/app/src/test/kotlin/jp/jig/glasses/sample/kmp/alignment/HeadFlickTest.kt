package jp.jig.glasses.sample.kmp.alignment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 首の上下フリック。**6DoF は 10Hz** なので、どのテストも 100ms 刻みで入れる。
 *
 * いちばん大事なのは「**ゆっくり見上げただけでは出ない**」こと。
 * 一度これで失敗している（読んでいる途中に見上げただけで画面が消えた）。
 */
class HeadFlickTest {

    /** 100ms 刻みでピッチを入れて、出たフリックを全部集める */
    private fun feed(detector: HeadFlickDetector, pitches: List<Double>, startMs: Long = 0L): List<HeadFlick> {
        val fired = ArrayList<HeadFlick>()
        for ((i, pitch) in pitches.withIndex()) {
            detector.add(startMs + i * 100L, pitch)?.let { fired += it }
        }
        return fired
    }

    @Test
    fun `速く上へ振って戻すと上が出る`() {
        val fired = feed(HeadFlickDetector(), listOf(0.0, 0.0, 0.0, 15.0, 15.0, 0.0))

        assertEquals(listOf(HeadFlick.UP), fired)
    }

    /**
     * **小さなうなずきで届く。**
     *
     * 12° にしていたときは実機で「もっと小さく振りたい」となった。
     * さらに、**振っている間も基準が付いていくと振れ幅を食べてしまう**ので、
     * しきい値の 2 倍近く振らないと届かなかった。動いている間は基準を止める。
     */
    @Test
    fun `しきい値ぶんだけ振れば出る`() {
        val nod = listOf(0.0, 0.0, 0.0, 3.0, HeadFlickDetector.TRIGGER_DEG, 3.0, 0.0)

        val fired = feed(HeadFlickDetector(), nod)

        assertEquals("しきい値ちょうどで出ない $fired", listOf(HeadFlick.UP), fired)
    }

    /** 2 サンプルかけて上げても、基準に食べられない */
    @Test
    fun `ゆっくりめのうなずきでも振れ幅は目減りしない`() {
        val nod = listOf(0.0, 0.0, 0.0, 2.0, 4.0, 6.0, 3.0, 0.0)

        val fired = feed(HeadFlickDetector(), nod)

        assertEquals("振れ幅が基準に食べられた $fired", listOf(HeadFlick.UP), fired)
    }

    @Test
    fun `速く下へ振って戻すと下が出る`() {
        val fired = feed(HeadFlickDetector(), listOf(0.0, 0.0, 0.0, -15.0, -15.0, 0.0))

        assertEquals(listOf(HeadFlick.DOWN), fired)
    }

    /**
     * **ゆっくり見上げても出ない。**
     *
     * 空を見上げる動きで字幕が飛んだら、読んでいる途中で消えるのと同じことになる。
     * 3 秒かけて 15° 上げる（1 サンプル 0.5°）。
     */
    @Test
    fun `ゆっくり見上げても出ない`() {
        val slow = (0..30).map { it * 0.5 }

        val fired = feed(HeadFlickDetector(), slow)

        assertTrue("ゆっくりした動きで出た $fired", fired.isEmpty())
    }

    /** **上を向いたまま戻さなければ出ない。** そちらを見たいだけなので、命令ではない */
    @Test
    fun `振ったまま戻さなければ出ない`() {
        val held = listOf(0.0, 0.0, 0.0) + List(15) { 15.0 }

        val fired = feed(HeadFlickDetector(), held)

        assertTrue("戻していないのに出た $fired", fired.isEmpty())
    }

    /** 戻ってこないと分かったあとは、その向きを新しい基準にして落ち着く */
    @Test
    fun `戻さずに見上げたあとは新しい向きが基準になる`() {
        val detector = HeadFlickDetector()
        feed(detector, listOf(0.0, 0.0, 0.0) + List(15) { 15.0 })

        // 見上げた先からさらに振って戻す。ここは拾えないと操作できない
        val fired = feed(detector, listOf(15.0, 30.0, 30.0, 15.0), startMs = 2_000L)

        assertEquals(listOf(HeadFlick.UP), fired)
    }

    /** **1 回のうなずきで 1 行。** 戻り際の揺れで続けて出ると、どこを読んでいたか分からなくなる */
    @Test
    fun `続けて振っても間を置くまで出ない`() {
        val twice = listOf(0.0, 0.0, 0.0, 15.0, 15.0, 0.0, 15.0, 15.0, 0.0)

        val fired = feed(HeadFlickDetector(), twice)

        assertEquals("1 回のうなずきで 2 行進んだ", 1, fired.size)
    }

    /** 間を置けば 2 回目は拾う */
    @Test
    fun `間を置けば次を拾う`() {
        val detector = HeadFlickDetector()
        feed(detector, listOf(0.0, 0.0, 0.0, 15.0, 15.0, 0.0))

        val fired = feed(detector, listOf(0.0, 0.0, 15.0, 15.0, 0.0), startMs = 2_000L)

        assertEquals(listOf(HeadFlick.UP), fired)
    }

    /** 画面が変わったら、途中まで振れていた状態を持ち越さない */
    @Test
    fun `clear すると振りかけを捨てる`() {
        val detector = HeadFlickDetector()
        feed(detector, listOf(0.0, 0.0, 0.0, 15.0))

        detector.clear()
        // 振りかけが残っていれば、戻っただけでフリックとして出てしまう
        assertNull(detector.add(500L, 0.0))
        assertNull(detector.add(600L, 0.0))
    }

    /** しきい値に届かない小さな揺れ（読んでいるだけの首は 1〜2°）では出ない */
    @Test
    fun `小さな揺れでは出ない`() {
        val jitter = listOf(0.0, 1.5, -1.0, 2.0, -1.5, 0.5, 1.0, -2.0, 0.0, 1.2, -0.8, 0.0)

        val fired = feed(HeadFlickDetector(), jitter)

        assertTrue("読んでいるだけの揺れで出た $fired", fired.isEmpty())
    }
}
