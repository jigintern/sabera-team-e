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
/**
 * 加速度計の座標系での「上・前・右」。**軸に丸めずベクトルで持つ。**
 *
 * 実機で測ったら `前=-Y 傾き=-0.94/-0.26` だった。つまり**視線方向は 1 軸に乗っていない**
 * （−Y と −Z が 0.94 : 0.26 で混ざる ＝ 取付が視線から 15.5° ずれている）。
 * 1 軸へ丸めると、**見上げたときに重力の前方成分が左右成分へ漏れて、ロールが最大 14° ずれる**。
 * 水平では合うので気づきにくいうえ、その状態で追従を入れると星図が 14° 回る。
 */
class AccelBasis(up: Vec3, forward: Vec3) {
    val up: Vec3 = up.normalized()

    /** 上に直交させた視線方向。回帰で出した傾きベクトルには上成分が混ざるので必ず落とす */
    val forward: Vec3 = forward.minusProjection(this.up)

    /** 右 = 前 × 上（[Basis] と同じ規約） */
    val right: Vec3 = this.forward cross this.up

    /** 取付が視線からどれだけ回っているか[度]。0 なら軸に素直に乗っている */
    fun mountingOffsetDeg(): Double {
        val dominant = listOf(Vec3(1.0, 0.0, 0.0), Vec3(0.0, 1.0, 0.0), Vec3(0.0, 0.0, 1.0))
            .maxBy { kotlin.math.abs(forward dot it) }
        return angleBetweenDeg(forward, if ((forward dot dominant) < 0) dominant.negated() else dominant)
    }
}

private fun Vec3.minusProjection(axis: Vec3): Vec3 {
    val amount = this dot axis
    return Vec3(x - axis.x * amount, y - axis.y * amount, z - axis.z * amount).normalized()
}

internal fun Vec3.negated(): Vec3 = Vec3(-x, -y, -z)

data class AccelAxes(
    /** 頭の上下方向にあたる軸（0=X, 1=Y, 2=Z） */
    val upIndex: Int,
    /** 水平に置いたとき [upIndex] が +1g を返すなら +1 */
    val upSign: Int,
    /** 視線方向の軸 */
    val forwardIndex: Int,
    /** 見上げたとき [forwardIndex] の値が増えるなら +1 */
    val forwardSign: Int = 1,
) {
    /** 残った 1 軸。左右方向にあたる */
    val lateralIndex: Int get() = 3 - upIndex - forwardIndex

    /**
     * 左右軸の符号は**推定しなくてよい**。右方向 = 前 × 上（[Basis] と同じ規約）なので、
     * 上と前の符号が決まれば外積の符号として決まる。**ここを勘で決めると補正が逆に効く。**
     */
    val lateralSign: Int get() = upSign * forwardSign * leviCivita(forwardIndex, upIndex, lateralIndex)

    /** 軸に丸めた割り当てを基底に直す。表示用の割り当てから推定を組むときに使う */
    fun basis(): AccelBasis = AccelBasis(unit(upIndex, upSign), unit(forwardIndex, forwardSign))

    private fun unit(index: Int, sign: Int): Vec3 = when (index) {
        0 -> Vec3(sign.toDouble(), 0.0, 0.0)
        1 -> Vec3(0.0, sign.toDouble(), 0.0)
        else -> Vec3(0.0, 0.0, sign.toDouble())
    }

    init {
        require(upIndex in 0..2 && forwardIndex in 0..2 && upIndex != forwardIndex) {
            "軸の割り当てが不正: up=$upIndex forward=$forwardIndex"
        }
        require(upSign == 1 || upSign == -1) { "上向きの符号が不正: $upSign" }
        require(forwardSign == 1 || forwardSign == -1) { "前方の符号が不正: $forwardSign" }
    }

    companion object {
        /**
         * **実機で測った割り当て**（2026-08-21・team-e の 1 台）。上 = +X、前 = −Y。
         * それまでの仮定（上 = −X・前 = +Z）は**軸も符号も外れていた**。
         *
         * ただし前方は 1 軸に乗っておらず（−Y と −Z が 0.94 : 0.26）、
         * **正確な基底は [AccelAxisProbe] が実行時に出す。** ここは判定が済むまでの初期値。
         */
        val MEASURED = AccelAxes(upIndex = 0, upSign = 1, forwardIndex = 1, forwardSign = -1)

        /** e_i × e_j = ε_ijk e_k の符号 */
        internal fun leviCivita(i: Int, j: Int, k: Int): Int = when {
            i == j || j == k || i == k -> 0
            (i + 1) % 3 == j -> 1
            else -> -1
        }
    }
}

class RollEstimator(
    /** [AccelAxisProbe] が基底を出したら差し替える。変えたら推定はやり直す */
    basis: AccelBasis = AccelAxes.MEASURED.basis(),
    private val gain: Double = GAIN,
) {
    constructor(axes: AccelAxes, gain: Double = GAIN) : this(axes.basis(), gain)

    var basis: AccelBasis = basis
        set(value) {
            if (field !== value) {
                field = value
                rollDeg = null
            }
        }

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

        val gravity = Vec3(axis[0], axis[1], axis[2])
        val up = gravity dot basis.up
        // 右へ傾けると重力の右成分は負になる（世界の上が左へ回る）ので符号を反転する
        val lateral = -(gravity dot basis.right)
        // 真上・真下を向くと重力が前方へ寄り、この 2 方向から角度が決まらない
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

/** 上向き成分を落とした残り。傾きベクトルから視線方向を取り出すのに使う */
internal fun Vec3.dropAlong(axis: Vec3): Vec3 {
    val amount = this dot axis
    return Vec3(x - axis.x * amount, y - axis.y * amount, z - axis.z * amount)
}

/** [dropAlong] と同じだが、正規化せず長さを残す。姿勢の前提が合っているかを見るため */
internal fun Vec3.dropAlongKeepingLength(axis: Vec3): Vec3 = dropAlong(axis)

internal fun Vec3.length(): Double = kotlin.math.sqrt(x * x + y * y + z * z)
