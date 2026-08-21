package jp.jig.glasses.sample.kmp.starmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone
import kotlin.math.abs

/**
 * 座標変換の検算。符号を 1 つ間違えるだけで星図が裏返るので、実機に載せる前にここで潰す。
 * 期待値は docs/team-e/coordinate-system.md に載せたものと同じ。
 */
class AstroTest {

    /** 2026-01-01 12:00 UTC */
    private val epoch = 1767268800000L

    @Test
    fun `J2000 からの経過日数`() {
        // 2000-01-01 12:00 UTC がちょうど 0 日
        assertEquals(0.0, daysFromJ2000(946728000000L), 1e-6)
    }

    @Test
    fun `歳差でシリウスは26年で約0_28度動く`() {
        // ドキュメントの 0.36° は一般歳差（座標系の原点のずれ）で、
        // 個々の星の移動量は位置で変わる。シリウスは年変化の式から 0.2795° になる
        val d = daysFromJ2000(epoch)
        val moved = precess(101.2872, -16.7161, d)
        val sep = angularSeparation(101.2872, -16.7161, moved[0], moved[1])
        assertEquals(0.2795, sep, 0.005)
    }

    @Test
    fun `鯖江のポラリスは真北の低い高度に来る`() {
        // ポラリスは天の北極から約 0.7° 離れているので、方位も高度も緯度ちょうどにはならない
        val site = Site(35.9432, 136.1846)
        val d = daysFromJ2000(epoch)
        val p = precess(37.9529, 89.2641, d)
        val aa = toAltAz(p[0], p[1], localSiderealDeg(d, site.lonDeg), site.latDeg)
        assertTrue("方位 ${aa[0]}° が北から離れすぎ", aa[0] < 3.0 || aa[0] > 357.0)
        assertEquals("高度が緯度から離れすぎ", site.latDeg, aa[1], 1.5)
    }

    @Test
    fun `天頂を向いても投影が壊れない`() {
        // 角度で持つと cos h で割れなくなる点。単位ベクトルなら通る
        val b = Basis(0.0, 90.0)
        val k = projectionScale(576, 40.0)
        val q = project(enu(180.0, 89.0), b, k, 576, 360)
        assertTrue("天頂付近で投影できない", q != null)
    }

    @Test
    fun `視野の端が画面の端に来る`() {
        // 高度 0 でないと「方位差 = 離角」にならない（高度 20° だと方位差 20° の離角は 18.8°）
        val b = Basis(90.0, 0.0)
        val k = projectionScale(576, 40.0)
        val q = project(enu(70.0, 0.0), b, k, 576, 360)!!
        assertEquals(0.0, q[0], 0.5)
    }

    @Test
    fun `東を向くと右手は南になる`() {
        // 手系を間違えると星図が左右反転する
        val b = Basis(90.0, 0.0)
        assertEquals(0.0, b.right.x, 1e-9)
        assertEquals(-1.0, b.right.y, 1e-9)
    }

    @Test
    fun `ヨーの折り返しをまたいで差が取れる`() {
        assertEquals(-20.0, normalizeDeg(170.0 - 190.0), 1e-9)
        assertEquals(20.0, normalizeDeg(-170.0 - 170.0), 1e-9)
    }

    @Test
    fun `地平座標と赤道座標を往復できる`() {
        val d = daysFromJ2000(epoch)
        val lst = localSiderealDeg(d, 136.1846)
        val horizontal = toAltAz(101.2872, -16.7161, lst, 35.9432)
        val equatorial = toRaDec(horizontal[0], horizontal[1], lst, 35.9432)

        assertEquals(101.2872, equatorial[0], 1e-9)
        assertEquals(-16.7161, equatorial[1], 1e-9)
    }

    @Test
    fun `歳差を逆変換するとJ2000座標へ戻る`() {
        val d = daysFromJ2000(epoch)
        val moved = precess(101.2872, -16.7161, d)
        val restored = inversePrecess(moved[0], moved[1], d)

        assertEquals(101.2872, restored[0], 1e-8)
        assertEquals(-16.7161, restored[1], 1e-8)
    }

    @Test
    fun `地平線付近の大気差を補正して往復できる`() {
        assertEquals(0.48, apparentAltitudeDeg(0.0), 0.03)
        assertEquals(0.0, geometricAltitudeDeg(apparentAltitudeDeg(0.0)), 1e-4)
        assertTrue("高高度では補正が小さい", apparentAltitudeDeg(45.0) - 45.0 < 0.02)
    }

    private fun angularSeparation(ra1: Double, dec1: Double, ra2: Double, dec2: Double): Double {
        val a = enu(ra1, dec1)
        val b = enu(ra2, dec2)
        return Math.toDegrees(Math.acos((a dot b).coerceIn(-1.0, 1.0)))
    }

    init {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        require(abs(0.0) < 1.0)
    }
}
