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
        shower: SkyTips.ActiveShower? = null,
    ) = SkyTips.Sky(
        site = Site(latDeg, 139.0),
        hourOfDay = hourOfDay,
        month = month,
        sunAltDeg = sunAltDeg,
        moon = MoonPhase(ageDays = 7.4, illuminated = illuminated, nameJa = "上弦の月"),
        moonAltDeg = moonAltDeg,
        bodiesUp = bodiesUp,
        shower = shower,
    )

    private fun shower(
        nearPeak: Boolean = true,
        radiantAltDeg: Double = 58.0,
        daysToPeak: Int = if (nearPeak) 0 else 5,
    ) = SkyTips.ActiveShower(
        nameJa = "ペルセウス座流星群",
        zhr = 100,
        nearPeak = nearPeak,
        daysToPeak = daysToPeak,
        radiantAzDeg = 45.0,
        radiantAltDeg = radiantAltDeg,
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
        for (tip in SkyTips.candidates(sky(shower = shower()))) {
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
        for (tip in SkyTips.candidates(sky(shower = shower()))) {
            for (ch in "*#`~|<>[]{}※・()（）→←") {
                assertFalse("記号が入っている $ch「${tip.text}」", ch in tip.text)
            }
        }
    }

    /**
     * **時間が決まっているものが先。**
     *
     * 「今夜がいちばんよく流れる日」は、待つと決められるうちに言わないと意味がない。
     * 空の暗さや星の動きは、いつ出しても同じことが言える。
     */
    @Test
    fun `今夜の流れ星が先頭に来る`() {
        val tips = SkyTips.candidates(sky(shower = shower()))

        assertEquals("今夜の流れ星", tips.first().header)
    }

    /** 出現数を言うのは極大のころだけ。**外れた日の ZHR は当てにならない** */
    @Test
    fun `流星群の数は極大のころだけ言う`() {
        val peak = SkyTips.candidates(sky(shower = shower())).first { it.header == "今夜の流れ星" }
        val before = SkyTips.candidates(sky(shower = shower(nearPeak = false)))
            .first { it.header == "今夜の流れ星" }

        assertTrue("いちばん多い日なのに数を言わない", "1時間に100個" in peak.text)
        assertTrue("いちばん多い日と言っていない", "いちばんよく流れる日" in peak.text)
        assertFalse("そうでない日に数を言った", "1時間に" in before.text)
        assertTrue("いちばん多い日までの日数が無い", "あと5日" in before.text)
    }

    /**
     * **過ぎた日を「あと -11 日」と言わない。**
     *
     * 活動期間は極大よりずっと長い（ペルセウス座は極大 8/12 で 8/24 まで）ので、
     * **極大のあとに開く日のほうがむしろ多い**。実機のログで踏んだ。
     */
    @Test
    fun `いちばん多い日を過ぎたらマイナスの日数を言わない`() {
        val tip = SkyTips.candidates(sky(shower = shower(nearPeak = false, daysToPeak = -11)))
            .first { it.header == "今夜の流れ星" }

        assertFalse("マイナスの日数を言った「${tip.text}」", "-11" in tip.text)
        assertTrue("過ぎたことを言っていない「${tip.text}」", "過ぎました" in tip.text)
    }

    /**
     * **一点を見つめさせない。** 流れ星は空全体に出るし、
     * もとのすぐそばに出るものは短くて目立たない。
     */
    @Test
    fun `一点を見つめろとは言わない`() {
        val tip = SkyTips.candidates(sky(shower = shower())).first { it.header == "今夜の流れ星" }

        assertTrue("広く眺める話が無い「${tip.text}」", "広く眺める" in tip.text)
    }

    /** もとが沈んでいるのに「高度マイナス 12 度」と言わない */
    @Test
    fun `流れ星のもとが地平線の下なら待てと言う`() {
        val tip = SkyTips.candidates(sky(shower = shower(radiantAltDeg = -12.0)))
            .first { it.header == "今夜の流れ星" }

        assertTrue("沈んでいるのに高度を言った「${tip.text}」", "地平線の下" in tip.text)
        assertFalse("高度を言っている", "高度" in tip.text)
    }

    /**
     * **天文の言葉をそのまま出さない。** 使う人は天文の初心者で、
     * 「極大」「放射点」と言われても何をすればよいか分からない。
     */
    @Test
    fun `専門用語をそのまま出さない`() {
        val tips = SkyTips.candidates(sky(shower = shower()))

        for (word in listOf("極大", "放射点", "薄明", "天の極", "月齢", "ZHR", "等級")) {
            assertTrue(
                "専門用語が出ている「$word」",
                tips.none { word in it.text },
            )
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
        assertTrue("場所のメモが出ていない", south.any { "北極星が見えません" in it.text })
    }
}
