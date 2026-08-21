package jp.jig.glasses.sample.kmp.starmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 天体アライメントは夜に屋外で実機を持っていないと試せないので、
 * **計算の部分だけは机の上で固定しておく**。ずれの符号を取り違えると星図が倍ずれる。
 */
class CelestialAlignmentTest {

    private val alignment = CelestialAlignment()
    private val scale = projectionScale(STAR_MAP_WIDTH, ObservationDefaults.STAR_MAP_FOV_DEG)

    @Test
    fun `十字を中央で合わせた1点から方位と仰角のオフセットが戻る`() {
        val headingOffset = 17.5
        val pitchOffset = -2.3
        val glassYaw = 100.0
        val glassPitch = 30.0
        val correspondence = AlignmentCorrespondence(
            atMs = 0L,
            glassYawDeg = glassYaw,
            glassPitchDeg = glassPitch,
            panelX = STAR_MAP_WIDTH / 2.0,
            panelY = STAR_MAP_HEIGHT / 2.0,
            targetName = "ベガ",
            targetAzDeg = azimuthFromYaw(glassYaw, headingOffset),
            targetAltDeg = glassPitch + pitchOffset,
        )

        val solved = alignment.solve(listOf(correspondence))!!
        assertEquals(headingOffset, solved.headingOffsetDeg, 1e-6)
        assertEquals(pitchOffset, solved.pitchOffsetDeg, 1e-6)
        assertEquals(0.0, solved.maxResidualDeg, 1e-6)
    }

    @Test
    fun `パネルの中央以外に置いた対応点でも解ける`() {
        val look = Look(200.0, 35.0)
        val target = enu(212.0, 38.0)
        val at = project(target, Basis(look.azDeg, look.altDeg), scale, STAR_MAP_WIDTH, STAR_MAP_HEIGHT)!!
        assertTrue("この配置ならパネル内に来るはず", at[0] in 0.0..STAR_MAP_WIDTH.toDouble())

        val headingOffset = -9.0
        val pitchOffset = 1.5
        val solved = alignment.solve(
            listOf(
                AlignmentCorrespondence(
                    atMs = 0L,
                    glassYawDeg = normalizeDeg(headingOffset - look.azDeg),
                    glassPitchDeg = look.altDeg - pitchOffset,
                    panelX = at[0],
                    panelY = at[1],
                    targetName = "アークトゥルス",
                    targetAzDeg = 212.0,
                    targetAltDeg = 38.0,
                ),
            ),
        )!!
        assertEquals(headingOffset, solved.headingOffsetDeg, 0.02)
        assertEquals(pitchOffset, solved.pitchOffsetDeg, 0.02)
        assertTrue("残差は無視できる大きさになる: ${solved.maxResidualDeg}", solved.maxResidualDeg < 0.02)
    }

    @Test
    fun `食い違う2点目は残差に出る`() {
        val first = centered(glassYaw = 40.0, glassPitch = 25.0, headingOffset = 10.0, name = "シリウス")
        // 2 点目だけ方位を 4° 取り違えた（別の星に合わせてしまった、という状況）
        val second = centered(glassYaw = 130.0, glassPitch = 30.0, headingOffset = 14.0, name = "プロキオン")

        val solved = alignment.solve(listOf(first, second))!!
        assertEquals(12.0, solved.headingOffsetDeg, 0.6)
        assertTrue("取り違えは残差で気づける: ${solved.maxResidualDeg}", solved.maxResidualDeg > 1.0)
    }

    @Test
    fun `2点が揃っていれば残差はほぼ残らない`() {
        val first = centered(glassYaw = 40.0, glassPitch = 25.0, headingOffset = 11.0, name = "シリウス")
        val second = centered(glassYaw = 130.0, glassPitch = 30.0, headingOffset = 11.0, name = "プロキオン")
        val solved = alignment.solve(listOf(first, second))!!
        assertEquals(11.0, solved.headingOffsetDeg, 1e-6)
        assertTrue(solved.maxResidualDeg < 1e-6)
        assertTrue("2 点そろえば ±3° の要求に入る", solved.maxResidualDeg <= ACCEPTABLE_RESIDUAL_DEG)
    }

    @Test
    fun `離れているかを角距離で見る`() {
        val first = centered(glassYaw = 0.0, glassPitch = 30.0, headingOffset = 0.0, name = "A")
        val second = centered(glassYaw = 90.0, glassPitch = 30.0, headingOffset = 0.0, name = "B")
        assertTrue(separationDeg(first, second) > AlignmentTargets.MIN_SEPARATION_DEG)
        assertTrue(separationDeg(first, first) < 1e-9)
    }

    @Test
    fun `ドリフト補正後のヨーで解いたオフセットを生のヨー基準へ戻す`() {
        // オフセットは 方位 ＋ ヨー なので、ヨーの基準が 20° 進んでいたらその分を引いて渡す
        assertEquals(-5.0, bridgeToRawYaw(15.0, correctedYawDeg = 100.0, rawYawDeg = 80.0), 1e-9)
        assertEquals(15.0, bridgeToRawYaw(15.0, correctedYawDeg = 80.0, rawYawDeg = 80.0), 1e-9)
    }

    /**
     * **ヨーは左を向くと増え、方位は右を向くと増える**（[azimuthFromYaw] の説明を参照）。
     * 2026-08-21 に実機で確認した。足し算に戻すと、首を振った分だけ 2 倍ずれる。
     */
    @Test
    fun `右を向くと方位が増える`() {
        val offset = 30.0
        // 右を向く ＝ ヨーが減る
        val facing = azimuthFromYaw(yawDeg = 0.0, headingOffsetDeg = offset)
        val turnedRight = azimuthFromYaw(yawDeg = -20.0, headingOffsetDeg = offset)
        assertEquals(30.0, facing, 1e-9)
        assertEquals(50.0, turnedRight, 1e-9)
    }

    @Test
    fun `オフセットと方位は往復する`() {
        val yaw = -117.0
        val az = 214.0
        val offset = headingOffsetFor(az, yaw)
        assertEquals(az, azimuthFromYaw(yaw, offset), 1e-9)
    }

    @Test
    fun `投影と逆投影が往復する`() {
        val basis = Basis(123.0, 40.0)
        val direction = enu(130.0, 44.0)
        val at = project(direction, basis, scale, STAR_MAP_WIDTH, STAR_MAP_HEIGHT)!!
        val back = unproject(at[0], at[1], basis, scale, STAR_MAP_WIDTH, STAR_MAP_HEIGHT)
        assertEquals(0.0, angleBetweenDeg(direction, back), 1e-6)
    }

    @Test
    fun `静止が続いたときだけ確定する`() {
        val hold = AlignmentHold()
        var state = hold.add(0L, 100.0, 30.0)
        assertFalse(state.steady)

        // 10Hz で 4 秒ぶん。手ぶれの範囲（±0.2°）で揺らす
        var at = 0L
        repeat(40) { index ->
            at += 100L
            state = hold.add(at, 100.0 + if (index % 2 == 0) 0.1 else -0.1, 30.0)
        }
        assertTrue(state.steady)
        assertTrue("3 秒続いたら確定する", state.confirmed)
        assertEquals(100.0, state.yawDeg, 0.2)

        // 首を振ったら取り直し
        repeat(6) {
            at += 100L
            state = hold.add(at, 100.0 + it * 4.0, 30.0)
        }
        assertFalse(state.confirmed)
        assertEquals(0L, state.heldMs)
    }

    @Test
    fun `残差から何ができるかを言う`() {
        assertEquals("星に重なる精度", alignmentGrade(0.8))
        assertEquals("星図を空に重ねられる精度", alignmentGrade(2.5))
        assertEquals("星座を言い当てられる精度", alignmentGrade(8.0))
        assertEquals("合っていない。やり直したほうがよい", alignmentGrade(15.0))
    }

    private fun centered(
        glassYaw: Double,
        glassPitch: Double,
        headingOffset: Double,
        name: String,
    ) = AlignmentCorrespondence(
        atMs = 0L,
        glassYawDeg = glassYaw,
        glassPitchDeg = glassPitch,
        panelX = STAR_MAP_WIDTH / 2.0,
        panelY = STAR_MAP_HEIGHT / 2.0,
        targetName = name,
        targetAzDeg = azimuthFromYaw(glassYaw, headingOffset),
        targetAltDeg = glassPitch,
    )
}
