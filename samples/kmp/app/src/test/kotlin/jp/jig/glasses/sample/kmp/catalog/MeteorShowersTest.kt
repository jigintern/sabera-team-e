package jp.jig.glasses.sample.kmp.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 流星群の引き当て。**日付だけで決まる**ので、Android なしでここに固定できる */
class MeteorShowersTest {

    private val perseids = MeteorShowers.Shower(
        nameJa = "ペルセウス座流星群",
        peakMonth = 8, peakDay = 12,
        startMonth = 7, startDay = 17,
        endMonth = 8, endDay = 24,
        zhr = 100, raDeg = 48.0, decDeg = 58.0,
    )

    /** しぶんぎ座は 12/28 に始まって 1/12 に終わる。**通日の大小では期間を判定できない** */
    private val quadrantids = MeteorShowers.Shower(
        nameJa = "しぶんぎ座流星群",
        peakMonth = 1, peakDay = 4,
        startMonth = 12, startDay = 28,
        endMonth = 1, endDay = 12,
        zhr = 110, raDeg = 230.0, decDeg = 49.0,
    )

    /** おうし座南は活動期間が 2 か月あり、他の群の極大の日にも「活動中」になる */
    private val southernTaurids = MeteorShowers.Shower(
        nameJa = "おうし座南流星群",
        peakMonth = 11, peakDay = 5,
        startMonth = 9, startDay = 10,
        endMonth = 11, endDay = 20,
        zhr = 5, raDeg = 52.0, decDeg = 15.0,
    )

    private val geminids = MeteorShowers.Shower(
        nameJa = "ふたご座流星群",
        peakMonth = 12, peakDay = 14,
        startMonth = 12, startDay = 4,
        endMonth = 12, endDay = 17,
        zhr = 150, raDeg = 112.0, decDeg = 33.0,
    )

    @Test
    fun `活動期間の内と外`() {
        assertTrue(perseids.active(8, 12))
        assertTrue(perseids.active(7, 17))
        assertTrue(perseids.active(8, 24))
        assertFalse(perseids.active(7, 16))
        assertFalse(perseids.active(8, 25))
    }

    /** **年をまたぐ群を落とさない。** 通日で比べると 12/28 〜 1/12 は「開始 > 終了」になる */
    @Test
    fun `年をまたぐ群も活動期間に入る`() {
        assertTrue("大晦日が外れた", quadrantids.active(12, 31))
        assertTrue("元日が外れた", quadrantids.active(1, 1))
        assertTrue(quadrantids.active(1, 4))
        assertFalse(quadrantids.active(1, 13))
        assertFalse(quadrantids.active(12, 27))
    }

    /** 極大までの日数も年をまたぐ。**近いほうで数える** */
    @Test
    fun `極大までの日数は年をまたいでも近いほうで数える`() {
        assertEquals(0, quadrantids.daysToPeak(1, 4))
        assertEquals(4, quadrantids.daysToPeak(12, 31))
        assertEquals(-3, quadrantids.daysToPeak(1, 7))
        assertEquals(2, perseids.daysToPeak(8, 10))
    }

    /**
     * **その日の主役は極大に近いほう。**
     *
     * おうし座南は活動期間が 2 か月あるので、ほうっておくと他の群の極大日にまで顔を出す。
     */
    @Test
    fun `極大に近い群が主役になる`() {
        val catalog = MeteorShowers(listOf(southernTaurids, geminids, perseids), peakWindowDays = 2)

        assertEquals("ふたご座流星群", catalog.today(12, 14)?.nameJa)
        assertEquals("おうし座南流星群", catalog.today(11, 5)?.nameJa)
        assertEquals("ペルセウス座流星群", catalog.today(8, 12)?.nameJa)
    }

    @Test
    fun `どの群も活動していない日は何も返さない`() {
        val catalog = MeteorShowers(listOf(perseids, geminids), peakWindowDays = 2)

        assertNull(catalog.today(3, 1))
    }

    @Test
    fun `極大のころかどうか`() {
        val catalog = MeteorShowers(listOf(perseids), peakWindowDays = 2)

        assertTrue(catalog.nearPeak(perseids, 8, 12))
        assertTrue(catalog.nearPeak(perseids, 8, 10))
        assertFalse(catalog.nearPeak(perseids, 8, 9))
    }

    @Test
    fun `通日は月をまたいで増える`() {
        assertEquals(1, MeteorShowers.dayOfYear(1, 1))
        assertEquals(32, MeteorShowers.dayOfYear(2, 1))
        assertEquals(365, MeteorShowers.dayOfYear(12, 31))
    }
}
