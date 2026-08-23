package jp.jig.glasses.sample.kmp.ui

import jp.jig.glasses.sample.kmp.glass.GlassTextPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 解説を字幕として流す速さ（[explanationDwellMs]）。Android に触らないのでここで固定できる */
class ExplanationScrollTest {

    private val line = GlassTextPage.lineChars

    /** **1 枚目だけ 2 行ぶん新しい。** そのぶん長く置かないと読み終わらない */
    @Test
    fun `1枚目は2行ぶん置く`() {
        assertTrue(
            "1 行ぶんと同じ時間しか置いていない",
            explanationDwellMs(GlassTextPage.bodyChars, 0) > explanationDwellMs(line, 0),
        )
    }

    /**
     * **流すほど少しずつゆっくりになる。**
     *
     * 読み上げは文の切れ目で息が入るのに、字幕は 1 文字あたり一定で数えている。
     * 同じ速さのままだと、流すほど字幕が声より先へ出ていく。
     */
    @Test
    fun `流すほど1行を置く時間が延びる`() {
        val dwells = (0..10).map { explanationDwellMs(line, it) }

        assertTrue(
            "遅くなっていない $dwells",
            dwells.zipWithNext().all { (before, after) -> after >= before },
        )
        assertTrue("最後まで同じ速さ $dwells", dwells.last() > dwells.first())
    }

    /** 頭打ちが無いと、**声が終わったあと字幕だけが延々と残る** */
    @Test
    fun `遅くするのは頭打ちで止まる`() {
        assertEquals(explanationDwellMs(line, 20), explanationDwellMs(line, 60))
    }

    /** 短い行でも、目に入る前に流れていかない */
    @Test
    fun `短い行にも下限がある`() {
        assertEquals(explanationDwellMs(1, 0), explanationDwellMs(2, 0))
    }
}
