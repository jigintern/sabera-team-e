package jp.jig.glasses.sample.kmp.notification

import jp.jig.glasses.sample.kmp.catalog.MeteorShowers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** 次にいつ鳴らすか（#70）。**Android に触らない**ので時刻を固定して検算できる */
class MeteorShowerScheduleTest {

    private val tokyo = ZoneId.of("Asia/Tokyo")

    private val geminids = MeteorShowers.Shower(
        nameJa = "ふたご座流星群",
        peakMonth = 12, peakDay = 14,
        startMonth = 12, startDay = 4,
        endMonth = 12, endDay = 17,
        zhr = 150, raDeg = 112.0, decDeg = 33.0,
    )

    private val quadrantids = MeteorShowers.Shower(
        nameJa = "しぶんぎ座流星群",
        peakMonth = 1, peakDay = 4,
        startMonth = 12, startDay = 28,
        endMonth = 1, endDay = 12,
        zhr = 110, raDeg = 230.0, decDeg = 49.0,
    )

    private fun catalog(vararg showers: MeteorShowers.Shower) =
        MeteorShowers(showers.toList(), peakWindowDays = 2, notifyZhrThreshold = 50)

    private fun at(year: Int, month: Int, day: Int, hour: Int) =
        ZonedDateTime.of(year, month, day, hour, 0, 0, 0, tokyo)

    @Test
    fun `前日の朝なら、その日の夕方に前日の予告を出す`() {
        val planned = MeteorShowerSchedule.next(catalog(geminids), at(2026, 12, 13, 10))!!

        assertEquals(at(2026, 12, 13, 18), planned.at)
        assertEquals(false, planned.notice.onPeakDay)
    }

    /** **18 時を過ぎた日は飛ばす。** 過ぎた予定で鳴らし直しても、その夕方はもう終わっている */
    @Test
    fun `その日の18時を過ぎたら次の日へ送る`() {
        val planned = MeteorShowerSchedule.next(catalog(geminids), at(2026, 12, 13, 19))!!

        assertEquals(at(2026, 12, 14, 18), planned.at)
        assertEquals(true, planned.notice.onPeakDay)
    }

    /** 月の明るさは**知らせる夜**のもの。前日の予告なら翌晩を指す */
    @Test
    fun `前日の予告が指す夜は翌晩`() {
        val eve = MeteorShowerSchedule.next(catalog(geminids), at(2026, 12, 13, 10))!!
        val peak = MeteorShowerSchedule.next(catalog(geminids), at(2026, 12, 14, 10))!!

        assertEquals(at(2026, 12, 14, 22), eve.nightAt)
        assertEquals(at(2026, 12, 14, 22), peak.nightAt)
    }

    /** **年をまたいで次を探す。** しぶんぎ座の極大は 1/4 で、前日は 1/3 */
    @Test
    fun `年をまたいだ次の群を見つける`() {
        val planned = MeteorShowerSchedule.next(catalog(quadrantids), at(2026, 12, 20, 12))!!

        assertEquals(at(2027, 1, 3, 18), planned.at)
        assertEquals("しぶんぎ座流星群", planned.notice.shower.nameJa)
    }

    @Test
    fun `知らせる群が無ければ予約しない`() {
        assertNull(MeteorShowerSchedule.next(catalog(), at(2026, 12, 13, 10)))
    }

    private fun atMinute(year: Int, month: Int, day: Int, hour: Int, minute: Int) =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, tokyo)

    /**
     * **数分遅れて起きても、その回の予告を出す。**
     *
     * 使っているのは setAndAllowWhileIdle（不正確アラーム）で数分の粒度でしか鳴らない。
     * さかのぼる幅が 1 分しか無かったころは、5 分遅れただけで翌日の予定に化けたうえ
     * 「まだ先」と判定され、その回の通知が丸ごと消えていた。
     */
    @Test
    fun `5分遅れて起きてもその回の予告を出す`() {
        val planned = MeteorShowerSchedule.plannedFor(
            catalog(geminids),
            atMinute(2026, 12, 13, 18, 5),
        )!!

        assertEquals(at(2026, 12, 13, 18), planned.at)
        assertEquals(false, planned.notice.onPeakDay)
    }

    @Test
    fun `59分遅れて起きてもその回の予告を出す`() {
        val planned = MeteorShowerSchedule.plannedFor(
            catalog(geminids),
            atMinute(2026, 12, 13, 18, 59),
        )!!

        assertEquals(at(2026, 12, 13, 18), planned.at)
    }

    /** 端末が長く眠っていた回は黙る（夕方はもう終わっている） */
    @Test
    fun `2時間遅れて起きたら黙る`() {
        assertNull(MeteorShowerSchedule.plannedFor(catalog(geminids), at(2026, 12, 13, 20)))
    }

    /** まだ遠い予定を先取りして鳴らさない */
    @Test
    fun `予定の何時間も前に起きたら黙る`() {
        assertNull(MeteorShowerSchedule.plannedFor(catalog(geminids), at(2026, 12, 13, 10)))
    }
}
