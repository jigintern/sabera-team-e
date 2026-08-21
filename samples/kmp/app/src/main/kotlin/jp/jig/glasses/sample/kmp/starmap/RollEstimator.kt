package jp.jig.glasses.sample.kmp.starmap

import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * 首の傾き（ロール）を 3 軸加速度から出す。仕様は docs/team-e/coordinate-system.md ④⑤。
 *
 * **ロールは融合値として提供されない。** 加速度計は重力を測るので絶対値が取れるが、
 * 実測で分かっているのは「`gyroXDps` が鉛直軸」だけで、**生の加速度の軸定義は未文書**。
 * 前方がどの軸かは実機で 1 回測らないと決まらないので、
 * **軸の割り当ては差し替えられる形にして、既定はロール追従そのものをオフにしてある**。
 *
 * ロールを入れると視野端が合う（首を 15° 傾けたときの端のずれ 4.6° が消える）が、
 * **傾けるたびに 528×330 を送り直す**ので、画面が 0.4 秒消える回数が増える。
 * だから追従にはデッドバンドを付ける（[StarMapScreen] 側）。
 */
data class AccelAxes(
    /** 頭の上下方向にあたる軸（0=X, 1=Y, 2=Z） */
    val upIndex: Int,
    /** 水平に置いたとき [upIndex] が +1g を返すなら +1 */
    val upSign: Int,
    /** 視線方向の軸 */
    val forwardIndex: Int,
) {
    /** 残った 1 軸。左右方向にあたる */
    val lateralIndex: Int get() = 3 - upIndex - forwardIndex

    init {
        require(upIndex in 0..2 && forwardIndex in 0..2 && upIndex != forwardIndex) {
            "軸の割り当てが不正: up=$upIndex forward=$forwardIndex"
        }
    }

    companion object {
        /**
         * 実測で確かなのは「鉛直が X」だけ。前方は Z、上向きは −X と**仮定**している。
         * **実機でグラスを水平に置いて 1 回測れば確定する。** それまでは追従を有効にしない。
         */
        val ASSUMED = AccelAxes(upIndex = 0, upSign = -1, forwardIndex = 2)
    }
}

class RollEstimator(
    private val axes: AccelAxes = AccelAxes.ASSUMED,
    private val gain: Double = GAIN,
) {
    /** 最後に確定したロール[度]。まだ 1 サンプルも使えていなければ null */
    var rollDeg: Double? = null
        private set

    fun reset() {
        rollDeg = null
    }

    /**
     * 加速度[mg]を 1 サンプル反映する。**使えないサンプルは黙って捨てる**
     * （首を振っている間は重力以外の加速度が乗り、真上を向くと基準が消える）。
     */
    fun update(accelXMilliG: Double, accelYMilliG: Double, accelZMilliG: Double): Double? {
        val axis = doubleArrayOf(accelXMilliG, accelYMilliG, accelZMilliG)
        val magnitude = sqrt(
            accelXMilliG * accelXMilliG + accelYMilliG * accelYMilliG + accelZMilliG * accelZMilliG,
        )
        // 1g から大きく外れていたら、重力以外の加速度が乗っている
        if (magnitude < MIN_MAGNITUDE_MG || magnitude > MAX_MAGNITUDE_MG) return rollDeg

        val up = axes.upSign * axis[axes.upIndex]
        val lateral = axis[axes.lateralIndex]
        // 真上・真下を向くと重力が前方軸へ寄り、この 2 軸から角度が決まらない
        if (hypot(lateral, up) < MIN_PLANAR_MG) return rollDeg

        val measured = atan2(lateral, up) * DEG
        val previous = rollDeg
        rollDeg = if (previous == null) measured else previous + normalizeDeg(measured - previous) * gain
        return rollDeg
    }

    companion object {
        const val GAIN = 0.25

        /** 静止していれば 1000mg。この幅を外れたサンプルは動いている */
        const val MIN_MAGNITUDE_MG = 700.0
        const val MAX_MAGNITUDE_MG = 1300.0

        /** 視線が鉛直に近いと、ロールは定義できても測れない */
        const val MIN_PLANAR_MG = 200.0
    }
}
