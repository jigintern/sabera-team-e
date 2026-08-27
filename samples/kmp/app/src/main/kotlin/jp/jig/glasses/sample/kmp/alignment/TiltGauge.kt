package jp.jig.glasses.sample.kmp.alignment

import kotlin.math.abs

/**
 * 仰角差を姿勢計（人工水平儀）として描くための値。
 *
 * **度をここで正規化し、Canvas には -1..1 だけを渡す。** 描画側に度の意味を持ち込むと、
 * レンジを変えたときに Canvas の中の数字も一緒に直すことになる。
 */
data class TiltGaugeGeometry(
    /**
     * 水平線の中心からのずれ。-1..1 で、**正なら水平線が中心より下**。
     *
     * **グラスが上を向いていると下に出る**ので、上を向くほど空が広がる。
     * 動かしているのは首なので、**見た色がそのまま自分の向き**になる向きに取ってある。
     */
    val horizonOffset: Float,
    /** 端に張り付いているか。**まだ先があること**を別の形で見せるために要る */
    val pegged: Boolean,
    /** 許容（[MAX_TILT_DIFFERENCE_DEG]）に入っているか */
    val within: Boolean,
)

/**
 * 符号付きの仰角差[度]（**グラス − スマホ**、上向きが正）から姿勢計の形を出す。
 */
fun tiltGaugeGeometry(differenceDeg: Double): TiltGaugeGeometry = TiltGaugeGeometry(
    horizonOffset = (differenceDeg / TILT_GAUGE_RANGE_DEG).coerceIn(-1.0, 1.0).toFloat(),
    pegged = abs(differenceDeg) > TILT_GAUGE_RANGE_DEG,
    within = abs(differenceDeg) <= MAX_TILT_DIFFERENCE_DEG,
)

/**
 * これだけ離れたら振り切れとみなす仰角差[度]。**仮の値で、実機で詰める。**
 *
 * 許容の 3° が半径の 1/5 のずれとして出る割合。狭くすると 3° 付近の見分けは付くが、
 * 大きく外しているときに端で張り付いたままになって「どちらへ倒すか」しか分からなくなる。
 */
const val TILT_GAUGE_RANGE_DEG = 15.0

/** ここまで合っていれば正対とみなす仰角差[度] */
const val MAX_TILT_DIFFERENCE_DEG = 3.0
