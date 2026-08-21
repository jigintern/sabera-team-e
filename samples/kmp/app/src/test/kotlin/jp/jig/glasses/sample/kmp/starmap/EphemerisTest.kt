package jp.jig.glasses.sample.kmp.starmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 月と惑星は「実際に空のどこにあったか」を外から確かめられる。
 * **記録に残っている天文現象の瞬間を使う**（日食＝月と太陽の黄経が一致、合＝2 惑星の黄経が一致）。
 * 軌道要素を 1 桁打ち間違えるとここで落ちる。
 */
class EphemerisTest {

    private fun utc(text: String): Long = java.time.Instant.parse(text).toEpochMilli()

    private fun separationFromSunDeg(lonDeg: Double, at: Long): Double =
        abs(normalizeDeg(lonDeg - sunEclipticLonDeg(at)))

    @Test
    fun `2017年8月21日の皆既日食の瞬間、月は太陽と同じ黄経にある`() {
        val at = utc("2017-08-21T18:26:00Z")
        val moon = moonPosition(at)
        assertTrue("新月なので黄経が一致する: ${separationFromSunDeg(moon.lonDeg, at)}", separationFromSunDeg(moon.lonDeg, at) < 0.6)
        assertTrue("皆既日食なので黄緯もほぼ 0: ${moon.latDeg}", abs(moon.latDeg) < 0.6)
    }

    @Test
    fun `2024年4月8日の皆既日食でも一致する`() {
        val at = utc("2024-04-08T18:17:00Z")
        val moon = moonPosition(at)
        assertTrue(separationFromSunDeg(moon.lonDeg, at) < 0.6)
        assertTrue(abs(moon.latDeg) < 0.6)
    }

    @Test
    fun `2020年12月21日の木星と土星の大接近で、両者の黄経がそろう`() {
        val at = utc("2020-12-21T13:00:00Z")
        val jupiter = planetPosition(SolarSystemBody.JUPITER, at)
        val saturn = planetPosition(SolarSystemBody.SATURN, at)
        val separation = abs(normalizeDeg(jupiter.lonDeg - saturn.lonDeg))
        assertTrue("大接近は 0.1° まで近づいた: $separation", separation < 0.4)
        assertTrue("木星は −2 等より明るい: ${jupiter.magnitude}", jupiter.magnitude < -1.7)
        assertTrue("土星は 0〜1.5 等: ${saturn.magnitude}", saturn.magnitude in 0.0..1.6)
    }

    @Test
    fun `2012年6月6日の金星の太陽面通過で、金星は太陽と重なる`() {
        val at = utc("2012-06-06T01:29:00Z")
        val venus = planetPosition(SolarSystemBody.VENUS, at)
        assertTrue("黄経が一致する: ${separationFromSunDeg(venus.lonDeg, at)}", separationFromSunDeg(venus.lonDeg, at) < 0.4)
        assertTrue("黄緯もほぼ 0: ${venus.latDeg}", abs(venus.latDeg) < 0.3)
    }

    @Test
    fun `2020年10月13日の火星の衝で、火星は太陽の正反対に来る`() {
        val at = utc("2020-10-13T23:20:00Z")
        val mars = planetPosition(SolarSystemBody.MARS, at)
        val opposition = abs(abs(normalizeDeg(mars.lonDeg - sunEclipticLonDeg(at))) - 180.0)
        assertTrue("衝なので 180° 差: $opposition", opposition < 1.0)
        assertTrue("最接近なので −2 等より明るい: ${mars.magnitude}", mars.magnitude < -2.0)
    }

    @Test
    fun `太陽の黄経は既存の太陽計算と一致する`() {
        // 独立に書いた 2 系統が合っていれば、どちらも壊れていない
        for (at in listOf("2026-01-01T12:00:00Z", "2026-06-21T00:00:00Z", "2026-08-21T09:00:00Z")) {
            val epoch = utc(at)
            val fromElements = sunEclipticLonDeg(epoch)
            val existing = sunPosition(epoch)
            val equatorial = eclipticToEquatorial(fromElements, 0.0, epoch)
            assertEquals("赤経が一致する（$at）", existing.raDeg, equatorial[0], 0.05)
            assertEquals("赤緯が一致する（$at）", existing.decDeg, equatorial[1], 0.05)
        }
    }

    @Test
    fun `月の距離と黄緯は物理的にありえる範囲に収まる`() {
        var minimum = Double.MAX_VALUE
        var maximum = 0.0
        var maxLatitude = 0.0
        // 2026 年を 1 日おきに 1 年ぶん
        for (day in 0 until 365) {
            val at = utc("2026-01-01T00:00:00Z") + day * 86_400_000L
            val moon = moonPosition(at)
            val km = moon.distanceAu * 149_597_870.7
            minimum = minOf(minimum, km)
            maximum = maxOf(maximum, km)
            maxLatitude = maxOf(maxLatitude, abs(moon.latDeg))
        }
        assertTrue("近地点は 35.6 万 km あたり: $minimum", minimum in 355_000.0..372_000.0)
        assertTrue("遠地点は 40.5 万 km あたり: $maximum", maximum in 398_000.0..409_000.0)
        assertTrue("黄緯は ±5.3° を超えない: $maxLatitude", maxLatitude in 4.5..5.4)
    }

    @Test
    fun `月の視差は地上で 1 度ぶん高度を下げる`() {
        val at = utc("2026-08-21T12:00:00Z")
        val site = Site(35.9432, 136.1846)
        val topocentric = bodyAltAz(SolarSystemBody.MOON, site, at)
        val geocentric = moonPosition(at).let { position ->
            val equatorial = eclipticToEquatorial(position.lonDeg, position.latDeg, at)
            toAltAz(equatorial[0], equatorial[1], localSiderealDeg(daysFromJ2000(at), site.lonDeg), site.latDeg)
        }
        val pushedDown = geocentric[1] - topocentric[1]
        assertTrue("視差は 0.5〜1.0° 押し下げる（大気差で少し戻る）: $pushedDown", pushedDown in 0.3..1.0)
        assertEquals("方位は動かない", geocentric[0], topocentric[0], 1e-9)
    }

    @Test
    fun `視野に入っている月と惑星だけを返す`() {
        val site = Site(35.9432, 136.1846)
        // 月が地平線の上にある時刻を探す（1 か月あれば必ずある）
        val at = (0 until 24 * 30).map { utc("2026-01-01T00:00:00Z") + it * 3_600_000L }
            .first { bodyAltAz(SolarSystemBody.MOON, site, it)[1] > 20.0 }
        val moon = bodyAltAz(SolarSystemBody.MOON, site, at)

        val looking = bodiesInView(site, at, Look(moon[0], moon[1]), fovDeg = 35.0)
        assertTrue("見ている先の月は入る", looking.any { it.nameJa == "月" })
        assertTrue("中心なので距離はほぼ 0", looking.first { it.nameJa == "月" }.distanceFromCenterDeg < 0.01)

        val away = bodiesInView(site, at, Look((moon[0] + 120.0) % 360.0, moon[1]), fovDeg = 35.0)
        assertTrue("反対を向いていれば入らない", away.none { it.nameJa == "月" })

        // 地平線の下にあるものは返さない
        for (fact in bodiesInView(site, at, Look(moon[0], moon[1]), fovDeg = 180.0)) {
            assertTrue("${fact.nameJa} が地平線の下", fact.altDeg >= 0.0)
            assertTrue("${fact.nameJa} が暗すぎる", fact.magnitude <= BODY_LIMIT_MAGNITUDE)
        }
    }

    /** 水星は太陽から離れられない。**軌道長半径を打ち間違えるとここで破れる**（最大離角 28°） */
    @Test
    fun `水星の離角は 28 度台を超えず、合の近くも通る`() {
        var maximum = 0.0
        var minimum = 180.0
        val start = utc("2026-01-01T00:00:00Z")
        for (day in 0 until 730) {
            val at = start + day * 86_400_000L
            val separation = separationFromSunDeg(planetPosition(SolarSystemBody.MERCURY, at).lonDeg, at)
            maximum = maxOf(maximum, separation)
            minimum = minOf(minimum, separation)
        }
        assertTrue("水星が太陽から離れすぎている: $maximum", maximum < 29.0)
        assertTrue("最大離角に届いていない: $maximum", maximum > 22.0)
        assertTrue("合の近くを通っていない: $minimum", minimum < 2.0)
    }

    /**
     * 外側の 3 つは動きが遅いので、**どの星座にいたか**で検算できる。
     * 黄経は春分点起点なので、「〇座〇度」がそのまま黄経になる。
     */
    @Test
    fun `天王星・海王星・冥王星は2024年初めの位置に来る`() {
        val at = utc("2024-01-21T12:00:00Z")
        val uranus = planetPosition(SolarSystemBody.URANUS, at)
        val neptune = planetPosition(SolarSystemBody.NEPTUNE, at)
        val pluto = planetPosition(SolarSystemBody.PLUTO, at)

        // 天王星はおうし座の 19〜20°（黄経 49〜50°）
        assertEquals("天王星の黄経", 49.5, uranus.lonDeg, 4.0)
        // 海王星はうお座の後半（黄経 356° 前後）
        assertEquals("海王星の黄経", 356.5, neptune.lonDeg, 4.0)
        // **冥王星がみずがめ座（黄経 300°）へ入った日**
        assertEquals("冥王星の黄経", 300.0, pluto.lonDeg, 4.0)

        // 黄道からの離れ方も桁で確かめる。要素の取り違えはここにも出る
        assertTrue("天王星の黄緯: ${uranus.latDeg}", abs(uranus.latDeg) < 1.0)
        assertTrue("海王星の黄緯: ${neptune.latDeg}", abs(neptune.latDeg) < 2.0)
        assertTrue("冥王星の黄緯: ${pluto.latDeg}", abs(pluto.latDeg) in 0.5..20.0)
    }

    /** 距離と等級は公表値の幅に収まる。級数を 1 行落とすとここで出る */
    @Test
    fun `外側の惑星の距離と等級は既知の範囲に収まる`() {
        val start = utc("2026-01-01T00:00:00Z")
        for (day in 0 until 365 step 7) {
            val at = start + day * 86_400_000L
            val uranus = planetPosition(SolarSystemBody.URANUS, at)
            val neptune = planetPosition(SolarSystemBody.NEPTUNE, at)
            val pluto = planetPosition(SolarSystemBody.PLUTO, at)
            assertTrue("天王星の距離 ${uranus.distanceAu}", uranus.distanceAu in 17.2..21.2)
            assertTrue("海王星の距離 ${neptune.distanceAu}", neptune.distanceAu in 28.7..31.4)
            assertTrue("冥王星の距離 ${pluto.distanceAu}", pluto.distanceAu in 28.6..50.4)
            assertTrue("天王星の等級 ${uranus.magnitude}", uranus.magnitude in 5.2..6.1)
            assertTrue("海王星の等級 ${neptune.magnitude}", neptune.magnitude in 7.5..8.1)
            // **肉眼では見えない。** 6 等より明るく出たら距離か等級式が壊れている
            assertTrue("冥王星の等級 ${pluto.magnitude}", pluto.magnitude in 13.5..16.5)
        }
    }

    @Test
    fun `金星の離角は 47 度を超えない`() {
        var maximum = 0.0
        for (day in 0 until 584) {
            val at = utc("2026-01-01T00:00:00Z") + day * 86_400_000L
            maximum = maxOf(maximum, separationFromSunDeg(planetPosition(SolarSystemBody.VENUS, at).lonDeg, at))
        }
        assertTrue("内惑星なので最大離角は 45〜48°: $maximum", maximum in 44.0..48.0)
    }
}
