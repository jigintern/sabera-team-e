package jp.jig.glasses.sample.kmp.satellite

import jp.jig.glasses.sample.kmp.starmap.Site
import jp.jig.glasses.sample.kmp.starmap.SkyDarkness
import jp.jig.glasses.sample.kmp.starmap.sunAltitudeDeg
import jp.jig.glasses.sample.kmp.starmap.sunPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/** 太陽の位置と、衛星が影に入っているかの検算 */
class SunlightTest {

    private val sabae = Site(35.9432, 136.1846)

    private fun utc(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0): Long {
        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        c.clear()
        c.set(year, month - 1, day, hour, minute, 0)
        return c.timeInMillis
    }

    @Test
    fun `春分と秋分の太陽は赤道上にいる`() {
        // 2026 年の春分は 3/20、秋分は 9/23（いずれも UTC で昼ごろ）
        assertEquals("春分", 0.0, sunPosition(utc(2026, 3, 20, 14)).decDeg, 0.4)
        assertEquals("秋分", 0.0, sunPosition(utc(2026, 9, 23, 0)).decDeg, 0.4)
    }

    @Test
    fun `夏至と冬至の太陽は回帰線まで行く`() {
        assertEquals("夏至", 23.44, sunPosition(utc(2026, 6, 21, 12)).decDeg, 0.2)
        assertEquals("冬至", -23.44, sunPosition(utc(2026, 12, 21, 12)).decDeg, 0.2)
    }

    @Test
    fun `太陽と地球の距離は 1 天文単位のあたりにある`() {
        val d = sunPosition(utc(2026, 8, 20, 12)).distanceKm
        assertTrue("距離がおかしい: $d", d in 147_000_000.0..152_500_000.0)
    }

    @Test
    fun `鯖江の正午は昼、真夜中は夜になる`() {
        // 日本時間の正午 = UTC 3 時
        val noon = sunAltitudeDeg(sabae, utc(2026, 8, 20, 3))
        val midnight = sunAltitudeDeg(sabae, utc(2026, 8, 20, 15))
        assertTrue("正午の太陽が低い: $noon", noon > 50.0)
        assertTrue("真夜中の太陽が高い: $midnight", midnight < -20.0)
        assertEquals(SkyDarkness.DAY, SkyDarkness.of(noon))
        assertEquals(SkyDarkness.NIGHT, SkyDarkness.of(midnight))
    }

    @Test
    fun `真夜中の真上にある衛星は地球の影に入る`() {
        val time = utc(2026, 8, 20, 15) // 日本時間の真夜中
        val sun = sunPosition(time)
        // 太陽のちょうど反対側、高度 500km の位置に置く
        val ra = (sun.raDeg + 180.0) % 360.0
        val dec = -sun.decDeg
        val r = 6378.137 + 500.0
        val rad = Math.PI / 180.0
        val state = TemeState(
            x = r * Math.cos(dec * rad) * Math.cos(ra * rad),
            y = r * Math.cos(dec * rad) * Math.sin(ra * rad),
            z = r * Math.sin(dec * rad),
            vx = 0.0, vy = 0.0, vz = 0.0,
        )
        assertTrue("影に入っていない", !isSunlit(state, time))

        // 同じ距離でも太陽の側なら日が当たる
        val lit = TemeState(-state.x, -state.y, -state.z, 0.0, 0.0, 0.0)
        assertTrue("日が当たっていない", isSunlit(lit, time))
    }

    @Test
    fun `静止衛星はほとんどいつも日が当たっている`() {
        // 高度 36,000km だと地球の影は細く、年に 2 回の食の時期しか入らない
        val time = utc(2026, 8, 20, 15)
        val sun = sunPosition(time)
        val ra = (sun.raDeg + 180.0) % 360.0
        val rad = Math.PI / 180.0
        val r = 42164.0
        val state = TemeState(
            x = r * Math.cos(ra * rad), y = r * Math.sin(ra * rad), z = 0.0,
            vx = 0.0, vy = 0.0, vz = 0.0,
        )
        // 8 月は太陽の赤緯が +12° ほどあるので、赤道上の静止衛星は影を外れる
        assertTrue("8 月の静止衛星が影に入っている", isSunlit(state, time))
    }

    @Test
    fun `スターリンクのうち日が当たっているのは薄暮に偏る`() {
        val dataDir = generateSequence(java.io.File("").absoluteFile) { it.parentFile }
            .map { java.io.File(it, "data") }
            .first { java.io.File(it, "starlink.tle").exists() }
        val tles = Tle.parseAll(java.io.File(dataDir, "starlink.tle").readText()).take(2000)
        val sgp4 = tles.map { Sgp4(it) }
        val observer = Observer(sabae.latDeg, sabae.lonDeg)

        for (hourUtc in listOf(3, 10, 15)) {
            val time = utc(2026, 8, 20, hourUtc)
            var above = 0
            var lit = 0
            for (s in sgp4) {
                val state = s.at(time) ?: continue
                if (observer.look(state, time).altDeg <= 0.0) continue
                above++
                if (isSunlit(state, time)) lit++
            }
            val sunAlt = sunAltitudeDeg(sabae, time)
            println(
                "UTC ${hourUtc}時（日本時間 ${(hourUtc + 9) % 24}時）太陽高度 ${"%.1f".format(sunAlt)}° " +
                    "頭上 $above 機 / うち日照 $lit 機",
            )
            assertTrue("頭上に 1 機もいない", above > 0)
        }
    }
}
