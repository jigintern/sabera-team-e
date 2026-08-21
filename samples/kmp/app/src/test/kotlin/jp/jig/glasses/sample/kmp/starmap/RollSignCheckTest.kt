package jp.jig.glasses.sample.kmp.starmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * ロールの符号が合っているかを、人に聞かずに確かめる部分。
 * **実機で「+ が右だったか」を覚えていられない**ので、ここで機械に判定させる。
 */
class RollSignCheckTest {

    private val basis = AccelBasis(up = Vec3(1.0, 0.0, 0.0), forward = Vec3(0.0, -0.94, -0.26))

    private fun gravity(pitchDeg: Double, rollDeg: Double): Vec3 {
        val pitch = pitchDeg * RAD
        val roll = rollDeg * RAD
        return basis.forward * (1000.0 * sin(pitch)) +
            basis.up * (1000.0 * cos(pitch) * cos(roll)) +
            basis.right * (-1000.0 * cos(pitch) * sin(roll))
    }

    /** 首を左右に傾ける動き。[rightHanded] は端末のジャイロの規約 */
    private fun tilt(check: RollSignCheck, rightHanded: Boolean = true): RollSignEstimate {
        var at = 0L
        var last = check.estimate()
        // 右へ 18°、左へ 18°、を 3 往復。1 サンプル 100ms で 2°/サンプル = 20°/秒
        val path = (0..9).map { it * 2.0 } + (9 downTo -9).map { it * 2.0 } + (-9..0).map { it * 2.0 }
        repeat(3) {
            var previous = path.first()
            for (roll in path) {
                at += 100L
                val rate = (roll - previous) * 10.0
                previous = roll
                val a = gravity(20.0, roll)
                // 右へ傾けるのは前方軸まわりの正の回転
                val gyro = basis.forward * (rate * if (rightHanded) 1.0 else -1.0)
                last = check.add(a.x, a.y, a.z, gyro.x, gyro.y, gyro.z, at)
            }
        }
        return last
    }

    @Test
    fun `符号が合っていれば傾きは1になる`() {
        val estimate = tilt(RollSignCheck(basis, gyroRightHanded = true))
        assertEquals(RollSignVerdict.CORRECT, estimate.verdict)
        assertEquals("重力から出した角速度とジャイロが一致する", 1.0, estimate.slope, 0.1)
        assertTrue(estimate.sampleCount >= RollSignCheck.MIN_SAMPLES)
        assertTrue(estimate.describe().contains("正しい"))
    }

    @Test
    fun `左手系の端末でも手系をそろえれば合う`() {
        val estimate = tilt(RollSignCheck(basis, gyroRightHanded = false), rightHanded = false)
        assertEquals(RollSignVerdict.CORRECT, estimate.verdict)
        assertEquals(1.0, estimate.slope, 0.1)
    }

    @Test
    fun `手系を取り違えていたら反転として出る`() {
        // **これが拾いたい間違い。** 右手系の端末を左手系だと思って組むと、ロールの符号が逆になる
        val estimate = tilt(RollSignCheck(basis, gyroRightHanded = false), rightHanded = true)
        assertEquals(RollSignVerdict.INVERTED, estimate.verdict)
        assertTrue(estimate.slope < -0.3)
        assertTrue(estimate.describe().contains("反転"))
    }

    @Test
    fun `前方を180度取り違えても符号の検査は通る`() {
        // 前と右が同時に反転する（＝上まわりに 180° 回した基底）ので、この検査では見えない。
        // **そこはうなずきの回転軸で決まっている**（AccelAxisProbe）ので、役割が分かれている
        val flipped = AccelBasis(up = basis.up, forward = -basis.forward)
        assertEquals(RollSignVerdict.CORRECT, tilt(RollSignCheck(flipped, gyroRightHanded = true)).verdict)
    }

    @Test
    fun `うなずきや首振りでは判定しない`() {
        val check = RollSignCheck(basis, gyroRightHanded = true)
        var at = 0L
        var last = check.estimate()
        repeat(100) {
            at += 100L
            val a = gravity(20.0, 0.0)
            // うなずき（右軸まわり）と首振り（上軸まわり）だけ
            val gyro = basis.right * 30.0 + basis.up * 20.0
            last = check.add(a.x, a.y, a.z, gyro.x, gyro.y, gyro.z, at)
        }
        assertEquals(RollSignVerdict.UNKNOWN, last.verdict)
        assertEquals(0, last.sampleCount)
    }

    @Test
    fun `傾けていないうちは判定しない`() {
        val check = RollSignCheck(basis, gyroRightHanded = true)
        var at = 0L
        var last = check.estimate()
        repeat(100) {
            at += 100L
            val a = gravity(20.0, 0.0)
            last = check.add(a.x, a.y, a.z, 0.1, 0.0, 0.0, at)
        }
        assertEquals(RollSignVerdict.UNKNOWN, last.verdict)
        assertTrue(last.describe().contains("判定中"))
    }
}
