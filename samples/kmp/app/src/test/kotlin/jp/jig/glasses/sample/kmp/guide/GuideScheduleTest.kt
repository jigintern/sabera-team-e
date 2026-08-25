package jp.jig.glasses.sample.kmp.guide

import jp.jig.glasses.sample.kmp.sky.GuidanceTarget
import jp.jig.glasses.sample.kmp.sky.GuidanceTargetKind
import jp.jig.glasses.sample.kmp.sky.Look
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ツアーは**一点ではなく幅**で判定する。
 *
 * 空は外から渡すので、Android も星表も要らずに「昇ってくる星座」「沈む星座」を作れる。
 */
class GuideScheduleTest {

    private val start = 1_789_000_000_000L
    private val window = GuideWindow(start, 40)

    private fun target(name: String, altDeg: Double) =
        GuidanceTarget("id:$name", name, GuidanceTargetKind.CONSTELLATION, Look(180.0, altDeg))

    /** 開始時は地平線の下、終わりごろに 30 度まで昇る */
    private fun rising(name: String): (Long) -> List<GuidanceTarget> = { at ->
        val minutes = (at - start) / GuideWindow.MINUTE_MS
        listOf(target(name, -10.0 + minutes))
    }

    /** 開始時は高いが、どんどん沈む */
    private fun setting(name: String): (Long) -> List<GuidanceTarget> = { at ->
        val minutes = (at - start) / GuideWindow.MINUTE_MS
        listOf(target(name, 60.0 - minutes * 2))
    }

    @Test
    fun `段は所要時間を均等に割った時刻に来る`() {
        assertEquals(start, GuideSchedule.slotMillis(window, 0, 4))
        assertEquals(start + 10 * GuideWindow.MINUTE_MS, GuideSchedule.slotMillis(window, 1, 4))
        assertEquals(start + 30 * GuideWindow.MINUTE_MS, GuideSchedule.slotMillis(window, 3, 4))
    }

    @Test
    fun `段が 1 つなら開始時刻そのもの`() {
        assertEquals(start, GuideSchedule.slotMillis(window, 0, 1))
    }

    /**
     * **これが幅で見る理由。** 一点で判定すると、秋のツアーで最後に冬の星座を見せる
     * という一番よくある構成が作れなくなる。
     */
    @Test
    fun `開始時に出ていなくても、終わりまでに昇るものは候補に入る`() {
        val candidates = GuideSchedule.candidates(window, rising("おうし座"))
        assertEquals(listOf("おうし座"), candidates.map { it.nameJa })
    }

    /** 候補に残すのは**最初に届いた時点の見えかた**。幅の終わりの方角で説明しない */
    @Test
    fun `候補の高さは最初に届いた時点のもの`() {
        val candidates = GuideSchedule.candidates(window, rising("おうし座"))
        // -10 度から 1 分に 1 度で昇るので、20 度へ届くのは 30 分後
        assertEquals(20, candidates.single().aim.altDeg.toInt())
    }

    @Test
    fun `一晩じゅう低いものは候補に入らない`() {
        val low: (Long) -> List<GuidanceTarget> = { listOf(target("みなみじゅうじ座", 5.0)) }
        assertTrue(GuideSchedule.candidates(window, low).isEmpty())
    }

    /**
     * 段ごとの時刻で見るので、**同じ台本でも順番を変えると通ったり落ちたりする**。
     * 沈むものは先に回らないと間に合わない。
     */
    @Test
    fun `沈むものは後ろの段に置くと落ちる`() {
        val step = GuideStep("おおいぬ座", GuidanceTargetKind.CONSTELLATION, "", "本文")
        val filler = GuideStep("ふたご座", GuidanceTargetKind.CONSTELLATION, "", "本文")
        val sky = setting("おおいぬ座")

        // 1 段目（開始時・60 度）なら通る
        val first = GuideSchedule.check(listOf(step, filler), window, sky)
        assertTrue(first[0].visible)

        // 2 段目（20 分後・20 度）でぎりぎり、4 段中の 4 段目（30 分後・0 度）では落ちる
        val last = GuideSchedule.check(listOf(filler, filler, filler, step), window, sky)
        assertFalse(last[3].visible)
    }

    /** **断るだけで終わらせない。** ずらせば入るのは端末が計算できる */
    @Test
    fun `入らないものには、いつからなら入るかを添える`() {
        val from = GuideSchedule.availableFrom("おうし座", null, GuideWindow(start, 5), rising("おうし座"))
        assertNotNull(from)
        // -10 度から 1 分 1 度なので 30 分後に届く。**代案は 10 分刻みで探す**ので、
        // 答えるのは 35 分後（分単位で答えても人は動けない）
        assertEquals(35, ((from!! - start) / GuideWindow.MINUTE_MS).toInt())
    }

    @Test
    fun `その晩じゅう出ないものは null`() {
        val never: (Long) -> List<GuidanceTarget> = { listOf(target("ふうちょう座", -40.0)) }
        assertNull(GuideSchedule.availableFrom("ふうちょう座", null, window, never))
    }

    @Test
    fun `端末が知らない名前は入れられない`() {
        assertNull(GuideSchedule.availableFrom("そんな座", null, window, rising("おうし座")))
    }
}
