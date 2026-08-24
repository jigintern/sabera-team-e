package jp.jig.glasses.sample.kmp.sky

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class ObservationModeTest {
    private val sydney = CityCatalog.cities.single { it.id == "sydney" }
    private val epoch = Instant.parse("2026-08-24T10:30:00Z").toEpochMilli()

    @Test
    fun `シミュレーションはGPSの観測地と端末時刻を使わない`() {
        val mode = ObservationMode.Simulation(sydney, epoch)
        val snapshot = mode.snapshot(
            liveSite = Site(35.9432, 136.1846),
            nowMillis = 1L,
            liveZoneId = ZoneId.of("Asia/Tokyo"),
        )

        assertEquals(sydney.site, snapshot.site)
        assertEquals(epoch, snapshot.epochMillis)
        assertTrue(snapshot.simulation)
        assertTrue(snapshot.shortLabel().contains("シドニー 8/24 20:30"))
    }

    @Test
    fun `現在の空は最新のGPSと端末時刻を使う`() {
        val site = Site(35.0, 140.0)
        val snapshot = ObservationMode.Live.snapshot(site, 1234L, ZoneId.of("Asia/Tokyo"))

        assertEquals(site, snapshot.site)
        assertEquals(1234L, snapshot.epochMillis)
        assertFalse(snapshot.simulation)
    }

    @Test
    fun `二秒ごとに十分進む`() {
        var simulation = ObservationMode.Simulation(sydney, epoch).startPlayback(1_000L)
        assertFalse(simulation.tick(2_999L, settled = true).redraw)

        val tick = simulation.tick(3_000L, settled = true)
        simulation = tick.simulation
        assertTrue(tick.redraw)
        assertEquals(epoch + 10 * 60_000L, simulation.epochMillis)
    }

    @Test
    fun `首が動いている時間を後からまとめて進めない`() {
        var simulation = ObservationMode.Simulation(sydney, epoch).startPlayback(0L)
        simulation = simulation.tick(6_000L, settled = false).simulation
        assertEquals(epoch, simulation.epochMillis)

        val settled = simulation.tick(6_100L, settled = true)
        assertFalse(settled.redraw)
        assertEquals(epoch, settled.simulation.epochMillis)
    }

    @Test
    fun `開始から三十秒で自動停止する`() {
        val simulation = ObservationMode.Simulation(sydney, epoch).startPlayback(100L)
        val result = simulation.tick(30_100L, settled = true)

        assertFalse(result.simulation.playing)
        assertFalse(result.redraw)
    }

    @Test
    fun `シミュレーションでは元期から七日を超えた衛星を隠す`() {
        val simulation = ObservationMode.Simulation(sydney, epoch).snapshot(Site(0.0, 0.0), 0L)
        assertTrue(simulation.allowsSatellites(7.0))
        assertFalse(simulation.allowsSatellites(7.01))
        assertFalse(simulation.allowsSatellites(-7.01))

        val live = ObservationMode.Live.snapshot(Site(0.0, 0.0), epoch)
        assertTrue(live.allowsSatellites(30.0))
    }
}
