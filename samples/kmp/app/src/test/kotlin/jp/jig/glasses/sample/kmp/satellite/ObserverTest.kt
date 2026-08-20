package jp.jig.glasses.sample.kmp.satellite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * 観測地から見た方位・高度の検算。
 *
 * SGP4 を通さず、**位置が分かっている点を置いて**確かめる。
 * 伝播の誤りと座標変換の誤りを混ぜないため。
 */
class ObserverTest {

    private val sabae = Observer(35.9432, 136.1846)
    private val epoch = 1767268800000L // 2026-01-01 12:00 UTC

    /** 地球固定の点を TEME に戻す。テストの入力を作るためだけのもの */
    private fun ecefToTeme(x: Double, y: Double, z: Double, epochMillis: Long): TemeState {
        val g = Sgp4.gstime(Observer.julianDate(epochMillis))
        return TemeState(
            x = x * cos(g) - y * sin(g),
            y = x * sin(g) + y * cos(g),
            z = z,
            vx = 0.0, vy = 0.0, vz = 0.0,
        )
    }

    @Test
    fun `真上にある衛星は高度 90 度になる`() {
        // 「真上」は地心方向ではなく**測地的な鉛直**。地心方向に置くと 2° ずれる
        //（扁平のせいで向きが 0.19° 違い、高度 500km ではそれが 12 倍に効く）
        val lat = 35.9432 * PI / 180.0
        val lon = 136.1846 * PI / 180.0
        val f = 1.0 / 298.257223563
        val e2 = f * (2.0 - f)
        val n = 6378.137 / sqrt(1.0 - e2 * sin(lat) * sin(lat))
        val up = doubleArrayOf(cos(lat) * cos(lon), cos(lat) * sin(lon), sin(lat))
        val state = ecefToTeme(
            n * cos(lat) * cos(lon) + up[0] * 500.0,
            n * cos(lat) * sin(lon) + up[1] * 500.0,
            n * (1.0 - e2) * sin(lat) + up[2] * 500.0,
            epoch,
        )
        val look = sabae.look(state, epoch)
        assertEquals("高度", 90.0, look.altDeg, 0.01)
        assertEquals("距離", 500.0, look.rangeKm, 0.01)
    }

    @Test
    fun `静止衛星の見え方が幾何の公式と一致する`() {
        // ひまわり 8・9 と同じ東経 140.7° の静止軌道に点を置く
        val satLonDeg = 140.7
        val geoRadius = 42164.0
        val lonRad = satLonDeg * PI / 180.0
        val state = ecefToTeme(geoRadius * cos(lonRad), geoRadius * sin(lonRad), 0.0, epoch)
        val look = sabae.look(state, epoch)

        // 静止衛星の仰角・方位の閉じた式（観測地は球面とみなす）
        val lat = 35.9432 * PI / 180.0
        val dLon = (satLonDeg - 136.1846) * PI / 180.0
        val cosGamma = cos(dLon) * cos(lat)
        val expectedAlt = atan((cosGamma - 6378.137 / geoRadius) / sqrt(1.0 - cosGamma * cosGamma)) * 180.0 / PI
        // 衛星が観測地より東にあるなら、南より東（方位 180° 未満）に見える
        val expectedAz = 180.0 - atan(tan(dLon) / sin(lat)) * 180.0 / PI

        assertEquals("高度", expectedAlt, look.altDeg, 0.2)
        assertEquals("方位", expectedAz, look.azDeg, 0.2)
        // 鯖江から見ると南よりやや東の、空の半ばくらいに見えるはず
        assertTrue("方位が南より東でない: ${look.azDeg}", look.azDeg in 160.0..180.0)
        assertTrue("高度がおかしい: ${look.altDeg}", look.altDeg in 40.0..55.0)
        assertEquals("距離", 37000.0, look.rangeKm, 1500.0)
    }

    @Test
    fun `真下の点が置いた場所に戻る`() {
        val state = ecefToTeme(
            (6378.137 + 550.0) * cos(0.0) * cos(140.0 * PI / 180.0),
            (6378.137 + 550.0) * cos(0.0) * sin(140.0 * PI / 180.0),
            0.0,
            epoch,
        )
        val sub = Observer.subPoint(state, epoch)
        assertEquals("経度", 140.0, sub.lonDeg, 0.01)
        assertEquals("緯度", 0.0, sub.latDeg, 0.01)
        assertEquals("高度", 550.0, sub.altitudeKm, 1.0)
    }

    @Test
    fun `ISS の高度と速度が実際の値の範囲に入る`() {
        // 検証用 TLE の中の近地球のものを使い、伝播 → 真下の点まで通す
        val text = checkNotNull(javaClass.classLoader?.getResourceAsStream("sgp4/SGP4-VER.TLE"))
            .bufferedReader().readText()
        val tle = Tle.parseAll(text).first { it.noradId == 6251 } // DELTA 1 DEB、高度 400km 台
        val sgp4 = Sgp4(tle)
        val state = checkNotNull(sgp4.propagate(0.0))
        val sub = Observer.subPoint(state, tleEpochMillis(tle))
        assertTrue("高度が低軌道の範囲でない: ${sub.altitudeKm}", sub.altitudeKm in 300.0..900.0)
        val speed = sqrt(state.vx * state.vx + state.vy * state.vy + state.vz * state.vz)
        assertTrue("速度が秒速 7〜8km でない: $speed", speed in 7.0..8.0)
    }

    private fun tleEpochMillis(tle: Tle): Long =
        ((tle.jdEpoch + tle.jdEpochFrac - 2440587.5) * 86_400_000.0).toLong()
}
