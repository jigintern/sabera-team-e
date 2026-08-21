package jp.jig.glasses.sample.kmp.starmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * 「実機でグラスを水平に置いて 1 回測る」作業を自動化した部分。
 * **ここが誤判定すると、ロール補正が逆に効いてずれが 2 倍になる**ので、
 * 軸の組み合わせを変えても正しく当てられることを固定する。
 */
class AccelAxisProbeTest {

    /**
     * 与えた基底のグラスが、ピッチ [pitchDeg]・ロール [rollDeg] のときに返す加速度[mg]。
     * 静止時の加速度は「世界の上向き」そのものなので、姿勢から一意に決まる。
     */
    private fun accel(basis: AccelBasis, pitchDeg: Double, rollDeg: Double = 0.0): DoubleArray {
        val pitch = pitchDeg * RAD
        val roll = rollDeg * RAD
        val f = basis.forward
        val u = basis.up
        val r = basis.right
        val scaleForward = 1000.0 * sin(pitch)
        val scaleUp = 1000.0 * cos(pitch) * cos(roll)
        val scaleRight = -1000.0 * cos(pitch) * sin(roll)
        return doubleArrayOf(
            f.x * scaleForward + u.x * scaleUp + r.x * scaleRight,
            f.y * scaleForward + u.y * scaleUp + r.y * scaleRight,
            f.z * scaleForward + u.z * scaleUp + r.z * scaleRight,
        )
    }

    private fun accel(axes: AccelAxes, pitchDeg: Double, rollDeg: Double = 0.0): DoubleArray =
        accel(axes.basis(), pitchDeg, rollDeg)

    private fun feed(axes: AccelAxes, probe: AccelAxisProbe, samples: Int = 400): AccelAxisEstimate =
        feed(axes.basis(), probe, samples)

    private fun feed(basis: AccelBasis, probe: AccelAxisProbe, samples: Int = 400): AccelAxisEstimate {
        var last = probe.estimate()
        repeat(samples) { index ->
            // 空を見ているときの動き。水平付近を挟みながら見上げ下ろしする
            val pitch = when (index % 4) {
                0 -> 2.0
                1 -> 28.0
                2 -> -4.0
                else -> 34.0
            }
            // 首の傾きはピッチと連動させない（4 と 3 は互いに素なので相関が消える）。
            // 連動させた場合も判定は通るが、左右軸の傾きが 0.2 くらいまで持ち上がる
            val roll = when (index % 3) {
                0 -> 3.0
                1 -> -2.0
                else -> 0.5
            }
            val a = accel(basis, pitch, rollDeg = roll)
            last = probe.add(a[0], a[1], a[2], pitch, gyroMagnitudeDps = 0.4)
        }
        return last
    }

    @Test
    fun `仮定どおりの軸を当てられる`() {
        val truth = AccelAxes(upIndex = 0, upSign = -1, forwardIndex = 2, forwardSign = 1)
        val estimate = feed(truth, AccelAxisProbe())
        assertEquals(truth, estimate.resolved)
        assertEquals("前方成分はちょうど sin(ピッチ)", 1.0, estimate.forwardSlope, 0.02)
        assertTrue("左右軸はほとんど動かない: ${estimate.lateralSlope}", kotlin.math.abs(estimate.lateralSlope) < 0.1)
        assertTrue("傾きの確かさも高い: ${estimate.forwardCorrelation}", estimate.forwardCorrelation > 0.99)
    }

    @Test
    fun `別の軸割り当てでも当てられる`() {
        val truth = AccelAxes(upIndex = 2, upSign = 1, forwardIndex = 1, forwardSign = -1)
        val estimate = feed(truth, AccelAxisProbe())
        assertEquals(truth, estimate.resolved)
        assertEquals(2, estimate.upIndex)
        assertEquals(1, estimate.upSign)
        assertEquals(1, estimate.forwardIndex)
        assertEquals(-1, estimate.forwardSign)
        assertEquals(-1.0, estimate.forwardSlope, 0.02)
    }

    @Test
    fun `見上げ下ろしが足りないうちは決めない`() {
        val truth = AccelAxes.MEASURED
        val probe = AccelAxisProbe()
        var last = probe.estimate()
        repeat(400) {
            val a = accel(truth, pitchDeg = 3.0)
            last = probe.add(a[0], a[1], a[2], 3.0, gyroMagnitudeDps = 0.3)
        }
        assertNull("ピッチが動いていないと前方軸が決まらない", last.resolved)
        assertTrue(last.describe().contains("判定中") || last.resolved == null)
    }

    @Test
    fun `動いているサンプルは数に入れない`() {
        val truth = AccelAxes.MEASURED
        val probe = AccelAxisProbe()
        var last = probe.estimate()
        repeat(400) { index ->
            val pitch = if (index % 2 == 0) 5.0 else 30.0
            val a = accel(truth, pitch)
            // 首を振っている（ジャイロが立っている）ので捨てられる
            last = probe.add(a[0], a[1], a[2], pitch, gyroMagnitudeDps = 40.0)
        }
        assertEquals(0, last.sampleCount)
        assertNull(last.resolved)
    }

    @Test
    fun `左右軸の符号は外積で決まる`() {
        // 右 = 前 × 上。上 = +Z・前 = +X なら右 = −Y になる
        val axes = AccelAxes(upIndex = 2, upSign = 1, forwardIndex = 0, forwardSign = 1)
        assertEquals(1, axes.lateralIndex)
        assertEquals(-1, axes.lateralSign)

        // その割り当てで右へ 15° 傾けたときの加速度から、ロールが +15° として戻る
        val estimator = RollEstimator(axes)
        val a = accel(axes, pitchDeg = 0.0, rollDeg = 15.0)
        assertEquals(15.0, estimator.update(a[0], a[1], a[2])!!, 1e-6)
    }

    @Test
    fun `取付が視線からずれていても、見上げたときのロールが狂わない`() {
        // 実機で出た形。前方が −Y と −Z に 0.94 : 0.26 で混ざる（取付が視線から 15.5° 回っている）
        val truth = AccelBasis(up = Vec3(1.0, 0.0, 0.0), forward = Vec3(0.0, -0.94, -0.26))
        assertEquals(15.5, truth.mountingOffsetDeg(), 0.3)

        val estimate = feed(truth, AccelAxisProbe())
        val basis = estimate.resolvedBasis
        assertTrue("取付がずれていても判定は通る", basis != null)
        assertEquals("上に直交する傾きの大きさは 1", 1.0, estimate.pitchResponse, 0.03)
        assertEquals("取付のずれも読める", 15.5, estimate.mountingOffsetDeg, 0.6)
        // 軸に丸めた形では −Y が前になる（表示用）
        assertEquals(1, estimate.resolved!!.forwardIndex)
        assertEquals(-1, estimate.resolved!!.forwardSign)

        // 見上げた姿勢で、傾けていないのにロールが出たら補正が悪化する
        val level = accel(truth, pitchDeg = 40.0, rollDeg = 0.0)
        assertEquals(0.0, RollEstimator(basis!!).update(level[0], level[1], level[2])!!, 0.5)

        // 軸に丸めるとここが 14° ずれる。**丸めてはいけない理由そのもの**
        val rounded = RollEstimator(estimate.resolved!!)
        val roundedRoll = rounded.update(level[0], level[1], level[2])!!
        assertTrue("丸めると見上げたときに大きくずれる: $roundedRoll", kotlin.math.abs(roundedRoll) > 8.0)
    }

    @Test
    fun `軸が変わったらロールの推定はやり直す`() {
        val estimator = RollEstimator(AccelAxes.MEASURED)
        val a = accel(AccelAxes.MEASURED, pitchDeg = 0.0, rollDeg = 10.0)
        assertEquals(10.0, estimator.update(a[0], a[1], a[2])!!, 1e-6)
        estimator.basis = AccelAxes(upIndex = 2, upSign = 1, forwardIndex = 0, forwardSign = 1).basis()
        assertNull("前の基底で積んだ値を持ち越さない", estimator.rollDeg)
    }
}
