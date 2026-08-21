package jp.jig.glasses.sample.kmp.starmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * 「実機で加速度の軸を 1 回測る」作業を自動化した部分。
 *
 * **加速度だけでは前方が決まらない**（見上げるときに首を傾ける癖が混ざる）ことが実機で分かり、
 * **うなずきの回転軸をジャイロから取る**方式に変えた。ここではその分離を固定する。
 */
class AccelAxisProbeTest {

    private val truth = AccelBasis(up = Vec3(1.0, 0.0, 0.0), forward = Vec3(0.0, -1.0, 0.0))

    /** 静止時の加速度[mg]。世界の上向きそのものになる */
    private fun gravity(basis: AccelBasis, pitchDeg: Double, rollDeg: Double): Vec3 {
        val pitch = pitchDeg * RAD
        val roll = rollDeg * RAD
        return basis.forward * (1000.0 * sin(pitch)) +
            basis.up * (1000.0 * cos(pitch) * cos(roll)) +
            basis.right * (-1000.0 * cos(pitch) * sin(roll))
    }

    /** うなずいている間のジャイロ[dps]。見上げる回転は「右」軸まわり */
    private fun nodGyro(basis: AccelBasis, pitchRateDps: Double, rightHanded: Boolean): Vec3 =
        basis.right * (pitchRateDps * if (rightHanded) 1.0 else -1.0)

    /**
     * 空を見上げ下ろしする動きを作る。[rollPerPitch] は「見上げると首も傾く」癖の強さで、
     * **加速度から前方を出す方式はこれで壊れる**（実機で −0.31 → +0.87 まで振れた）。
     */
    private fun feed(
        probe: AccelAxisProbe,
        basis: AccelBasis = truth,
        rightHanded: Boolean = true,
        rollPerPitch: Double = 0.0,
        yawWhileNodding: Double = 0.0,
        cycles: Int = 6,
    ): AccelAxisEstimate {
        var at = 0L
        var last = probe.estimate()
        val targets = listOf(0.0, 45.0, 5.0, 50.0)

        fun hold(pitch: Double, samples: Int) {
            repeat(samples) {
                at += 100L
                val a = gravity(basis, pitch, pitch * rollPerPitch)
                last = probe.add(a.x, a.y, a.z, 0.1, 0.0, 0.0, pitch, at)
            }
        }

        fun moveTo(from: Double, to: Double) {
            val step = if (to > from) 1.5 else -1.5
            var pitch = from
            while (abs(pitch - to) > 1.4) {
                pitch += step
                at += 100L
                val rate = step * 10.0
                val gyro = nodGyro(basis, rate, rightHanded) + basis.up * yawWhileNodding
                val a = gravity(basis, pitch, pitch * rollPerPitch)
                last = probe.add(a.x, a.y, a.z, gyro.x, gyro.y, gyro.z, pitch, at)
            }
        }

        var current = 0.0
        hold(0.0, 20)
        repeat(cycles) {
            for (target in targets) {
                moveTo(current, target)
                hold(target, 8)
                current = target
            }
        }
        return last
    }

    @Test
    fun `うなずくだけで上と前が決まる`() {
        val estimate = feed(AccelAxisProbe())
        val basis = estimate.resolvedBasis
        assertTrue("確定しない: ${estimate.describe()}", basis != null)
        assertEquals("上が合っている", 0.0, angleBetweenDeg(basis!!.up, truth.up), 0.5)
        assertEquals("前が合っている", 0.0, angleBetweenDeg(basis.forward, truth.forward), 0.5)
        assertEquals(true, estimate.gyroRightHanded)
        assertTrue("回転軸が揃っている: ${estimate.nodConcentration}", estimate.nodConcentration > 0.99)
    }

    @Test
    fun `見上げると首を傾ける癖があっても前を間違えない`() {
        // 実機で起きていた形。ピッチ 45° のときロール 18° まで傾く
        val estimate = feed(AccelAxisProbe(), rollPerPitch = 0.4)
        val basis = estimate.resolvedBasis
        assertTrue("確定しない: ${estimate.describe()}", basis != null)
        // 残るのは `cosφ` の 2 次項（傾くと上成分がわずかに縮む）。1° 以下ならデッドバンド 12° に対して十分
        assertEquals("癖を落とせば前は狂わない", 0.0, angleBetweenDeg(basis!!.forward, truth.forward), 1.0)
        assertEquals("上も狂わない", 0.0, angleBetweenDeg(basis.up, truth.up), 1.0)
        // 落とした量がそのまま癖の強さ。**加速度だけでやると、これがそのまま誤差になっていた**
        assertTrue(
            "首の傾きの混入が見えるはず: ${estimate.rollContaminationDeg}",
            estimate.rollContaminationDeg > 8.0,
        )
    }

    @Test
    fun `取付が視線から回っていても当てられる`() {
        val mounted = AccelBasis(up = Vec3(1.0, 0.0, 0.0), forward = Vec3(0.0, -0.94, -0.26))
        val estimate = feed(AccelAxisProbe(), basis = mounted, rollPerPitch = 0.2)
        val basis = estimate.resolvedBasis
        assertTrue("確定しない: ${estimate.describe()}", basis != null)
        assertEquals(0.0, angleBetweenDeg(basis!!.forward, mounted.forward), 0.5)
        assertEquals(0.0, angleBetweenDeg(basis.right, mounted.right), 0.5)
    }

    @Test
    fun `ジャイロが左手系でも符号を合わせられる`() {
        val estimate = feed(AccelAxisProbe(), rightHanded = false)
        assertTrue(estimate.resolvedBasis != null)
        assertEquals(false, estimate.gyroRightHanded)
        assertEquals(0.0, angleBetweenDeg(estimate.resolvedBasis!!.forward, truth.forward), 0.5)
    }

    @Test
    fun `首を振りながらのうなずきは使わない`() {
        // ヨーが混ざると回転軸が上向きへ寄る。混ぜたまま平均すると前方がずれる
        val estimate = feed(AccelAxisProbe(), yawWhileNodding = 60.0)
        assertNull("軸が混ざったサンプルで確定してはいけない", estimate.resolvedBasis)
        assertEquals(0, estimate.nodCount)
    }

    @Test
    fun `うなずかないうちは決めない`() {
        val probe = AccelAxisProbe()
        var at = 0L
        var last = probe.estimate()
        repeat(300) {
            at += 100L
            val a = gravity(truth, 3.0, 0.0)
            last = probe.add(a.x, a.y, a.z, 0.1, 0.0, 0.0, 3.0, at)
        }
        assertNull(last.resolvedBasis)
        assertTrue(last.describe().contains("判定中"))
        assertFalse(last.stillCount == 0)
    }

    /**
     * **確定したあとに一致が落ちても取り消さない。**
     * 一致はうなずき全体の平均なので、確定後に首を傾ければ（B2 の手順そのもの）下がり続ける。
     * 実機では確定の 30 秒後に 0.84 → 0.76 まで落ちて「判定中」へ戻り、
     * そのあいだロールが古い値のまま星図へ焼かれていた。
     */
    @Test
    fun `確定したあとに一致が落ちても取り消さない`() {
        val probe = AccelAxisProbe()
        val resolved = feed(probe)
        assertTrue("先に確定していない: ${resolved.describe()}", resolved.resolvedBasis != null)

        // 首を左右に傾ける動き。回転軸が「右」ではなく「前」まわりなので一致が下がる
        var at = 1_000_000L
        var pitch = 0.0
        var last = resolved
        repeat(400) { i ->
            at += 100L
            pitch = if (i % 2 == 0) 4.0 else -4.0
            val gyro = truth.forward * 40.0
            val a = gravity(truth, pitch, 0.0)
            last = probe.add(a.x, a.y, a.z, gyro.x, gyro.y, gyro.z, pitch, at)
        }

        assertTrue("一致が落ちていないので回帰を再現できていない", last.nodConcentration < 0.8)
        val basis = last.resolvedBasis
        assertTrue("確定が取り消された: ${last.describe()}", basis != null)
        assertEquals("上がずれた", 0.0, angleBetweenDeg(basis!!.up, truth.up), 1.0)
        assertEquals("前がずれた", 0.0, angleBetweenDeg(basis.forward, truth.forward), 1.0)
    }

    @Test
    fun `やり直せる`() {
        val probe = AccelAxisProbe()
        assertTrue(feed(probe).resolvedBasis != null)
        probe.reset()
        assertNull(probe.estimate().resolvedBasis)
        assertEquals(0, probe.estimate().stillCount)
    }

    @Test
    fun `決まった基底でロールが正しく出る`() {
        val basis = feed(AccelAxisProbe(), rollPerPitch = 0.3).resolvedBasis!!
        val estimator = RollEstimator(basis)
        // 見上げた姿勢で首を傾けていないなら、ロールは 0
        val level = gravity(truth, pitchDeg = 40.0, rollDeg = 0.0)
        assertEquals(0.0, estimator.update(level.x, level.y, level.z)!!, 1.0)
        // 右に 15° 傾けたら +15°
        estimator.reset()
        val tilted = gravity(truth, pitchDeg = 40.0, rollDeg = 15.0)
        assertEquals(15.0, estimator.update(tilted.x, tilted.y, tilted.z)!!, 1.0)
    }
}
