package jp.jig.glasses.sample.kmp.narration

import jp.jig.glasses.sample.kmp.glass.GlassTextPage
import jp.jig.glasses.sample.kmp.sky.MoonPhase
import jp.jig.glasses.sample.kmp.sky.ObservedStarFact
import jp.jig.glasses.sample.kmp.sky.Site
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkyTipsTest {

    private fun sky(
        latDeg: Double = 35.0,
        hourOfDay: Int = 21,
        month: Int = 8,
        sunAltDeg: Double = -30.0,
        moonAltDeg: Double = 40.0,
        illuminated: Double = 0.5,
        bodiesUp: List<ObservedStarFact> = emptyList(),
    ) = SkyTips.Sky(
        site = Site(latDeg, 139.0),
        hourOfDay = hourOfDay,
        month = month,
        sunAltDeg = sunAltDeg,
        moon = MoonPhase(ageDays = 7.4, illuminated = illuminated, nameJa = "上弦の月"),
        moonAltDeg = moonAltDeg,
        bodiesUp = bodiesUp,
    )

    /** **押すたびに別のことを言う。** 同じものしか出ないと、2 回目が無反応と区別できない */
    @Test
    fun `押すたびに次のメモへ進み一巡する`() {
        val sky = sky()
        val count = SkyTips.candidates(sky).size

        val texts = (0 until count).map { SkyTips.of(sky, it).text }

        assertEquals("同じメモが混ざっている", count, texts.toSet().size)
        assertEquals("一巡して戻らない", texts.first(), SkyTips.of(sky, count).text)
    }

    /** グラスの解説画面に入る長さ（[GlassTextPage]）。入らなければ末尾が捨てられる */
    @Test
    fun `どのメモもグラスに入る長さ`() {
        for (tip in SkyTips.candidates(sky())) {
            assertTrue(
                "本文が長すぎる ${tip.text.length}文字「${tip.text}」",
                tip.text.length <= GlassTextPage.pagedChars,
            )
            assertTrue(
                "見出しが長すぎる「${tip.header}」",
                tip.header.length <= GlassTextPage.lineChars,
            )
        }
    }

    /** **読み上げる文なので記号がそのまま読まれる**（[AskGuard] の出口検査と同じ理由） */
    @Test
    fun `読み上げて邪魔になる記号を使わない`() {
        for (tip in SkyTips.candidates(sky())) {
            for (ch in "*#`~|<>[]{}※・()（）") {
                assertFalse("記号が入っている $ch「${tip.text}」", ch in tip.text)
            }
        }
    }

    /** 月が沈んでいる夜に「月あかりが強い」と言わない。**空にないものを根拠にしない** */
    @Test
    fun `月が地平線の下なら月あかりの話をしない`() {
        val tips = SkyTips.candidates(sky(moonAltDeg = -20.0, illuminated = 0.95))

        val moon = tips.first { it.header == "今夜の月" }
        assertTrue("沈んでいる月の明るさを語っている「${moon.text}」", "地平線の下" in moon.text)
    }

    /** 肉眼で見える惑星が無ければ、その枠ごと出さない（探させても見つからない） */
    @Test
    fun `出ている惑星が無ければ惑星のメモは出ない`() {
        val none = SkyTips.candidates(sky())
        val some = SkyTips.candidates(
            sky(bodiesUp = listOf(ObservedStarFact("木星", -2.2, 180.0, 42.0, 0.0))),
        )

        assertTrue("空なのに惑星のメモが出た", none.none { it.header == "いま出ている惑星" })
        assertTrue("惑星が出ているのにメモが無い", some.any { it.header == "いま出ている惑星" })
    }

    /** 月は惑星ではない。[jp.jig.glasses.sample.kmp.sky.bodiesUp] は月も返す */
    @Test
    fun `月しか出ていなければ惑星のメモは出ない`() {
        val tips = SkyTips.candidates(
            sky(bodiesUp = listOf(ObservedStarFact("月", -12.0, 120.0, 40.0, 0.0))),
        )

        assertTrue("月を惑星として案内した", tips.none { it.header == "いま出ている惑星" })
    }

    /** 季節の目印は北半球の空の話。南半球では言わない */
    @Test
    fun `南半球では季節の目印を出さない`() {
        val south = SkyTips.candidates(sky(latDeg = -33.0))

        assertTrue("南半球で北半球の空を案内した", south.none { it.header.endsWith("の空の目印") })
        assertTrue("場所のメモが出ていない", south.any { "南緯" in it.text })
    }
}
