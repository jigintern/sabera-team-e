package jp.jig.glasses.sample.kmp.notification

import jp.jig.glasses.sample.kmp.catalog.MeteorShowers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 予告の文（#70）。**Android に触らない**ので日付と月を固定して検算できる */
class MeteorShowerNoticeTest {

    private val geminids = MeteorShowers.Shower(
        nameJa = "ふたご座流星群",
        peakMonth = 12, peakDay = 14,
        startMonth = 12, startDay = 4,
        endMonth = 12, endDay = 17,
        zhr = 150, raDeg = 112.0, decDeg = 33.0,
    )

    private fun notice(onPeakDay: Boolean) = MeteorShowers.Notice(geminids, onPeakDay)

    @Test
    fun `当日は今夜と言い、数まで言う`() {
        val text = MeteorShowerNotice.of(notice(onPeakDay = true), moonIlluminated = 0.1)

        assertEquals("今夜、ふたご座流星群", text.title)
        assertTrue(text.body, text.body.contains("1時間に150個"))
    }

    /** **前日に数を言わない。** 出かける先を決めるのが前日の役目 */
    @Test
    fun `前日は明日の夜と言い、場所を決めさせる`() {
        val text = MeteorShowerNotice.of(notice(onPeakDay = false), moonIlluminated = 0.1)

        assertEquals("明日の夜、ふたご座流星群", text.title)
        assertFalse(text.body, text.body.contains("150"))
        assertTrue(text.body, text.body.contains("空の暗い場所"))
    }

    /** 月が明るい夜は一言添える。**通知そのものは止めない** */
    @Test
    fun `月が明るい夜は一言添える`() {
        val tonight = MeteorShowerNotice.of(notice(onPeakDay = true), moonIlluminated = 0.95)
        val eve = MeteorShowerNotice.of(notice(onPeakDay = false), moonIlluminated = 0.95)

        assertTrue(tonight.body, tonight.body.contains("今夜は月が明るいので"))
        // **前日の予告で「今夜」と言わない。** 明るいのは知らせている夜のほう
        assertTrue(eve.body, eve.body.contains("明日の夜は月が明るいので"))
    }

    @Test
    fun `月が暗ければ月の話はしない`() {
        val text = MeteorShowerNotice.of(notice(onPeakDay = true), moonIlluminated = 0.2)

        assertFalse(text.body, text.body.contains("月"))
    }

    /**
     * **天文の言葉を使わない。** 使う人は天文の初心者で、
     * 「極大」「放射点」と言われても何をすればよいか分からない（`SkyTipsTest` と同じ検査）。
     */
    @Test
    fun `専門用語を使わない`() {
        for (onPeakDay in listOf(true, false)) {
            for (moon in listOf(0.1, 0.95)) {
                val text = MeteorShowerNotice.of(notice(onPeakDay), moon)
                val whole = text.title + text.body
                for (word in listOf("極大", "放射点", "薄明", "天の極", "月齢", "ZHR", "等級")) {
                    assertFalse("$word が出た: $whole", whole.contains(word))
                }
            }
        }
    }
}
