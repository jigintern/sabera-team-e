package jp.jig.glasses.sample.kmp.glass

import jp.jig.glasses.sample.kmp.sky.angleBetweenDeg
import jp.jig.glasses.sample.kmp.sky.enu

// 星図をいつ描き直すか（送るか）の方針の数値。**動きに追従させると点滅にしかならない**ので、
// 「止まってから送る」「減速に入ったら止まる先へ 1 枚だけ先出しする」を数字で決めている。
// 実測の根拠は docs/team-e/70_measurements.md。

/**
 * 200 バイト 1 パケットの見積り時間。
 *
 * **実測 8〜9ms**（Pixel 9a・2026-08-20 に 12 回）。10ms にしていたぶんは毎フレーム
 * 数十 ms の待ちすぎになっていた。**多く見積もるほど次の絵が遅れる**ので実測の上端に合わせる。
 */
const val PACKET_MS_DEFAULT = 9f

/**
 * この幅を超えて動いたら「動いている」とみなす。6DoF のふらつきは 1 度に届かない。
 *
 * ここも空の上の隔たりで測る（[lookSeparationDeg]）。方位の生の差で測っていたころは、
 * **天頂に近いほど同じ首の揺れが大きな数字になり**、真上を見ている間だけ
 * 「止まった」と判定されにくかった。
 */
const val STILL_DEG = 1.0

/**
 * 動きが止まってからこれだけ待って送る。
 *
 * **短いほど早く出る。** 400ms から詰めた。長くすると「止めたのに出てこない」時間がそのまま伸び、
 * 短くしすぎると首を動かしている途中で送り始めて、転送のあいだ真っ暗になる回数が増える。
 */
const val STILL_MS = 180L

/**
 * 前に送った絵からこれだけ視線がずれたら描き直す。
 *
 * 送り直すたびグラスは転送中の約 0.4 秒黙るので、少し動いたくらいでは送らない。
 * 画角 35° に対しておよそ 1/6。
 *
 * **測るのは空の上の隔たり**（[lookSeparationDeg]）。方位の差をそのまま使わない。
 */
const val REDRAW_DEG = 6.0

/**
 * 最後のパケットを送ってからグラスが展開して描き終わるまでの余裕。
 *
 * 0.5.0 までは送信が重なるとどの画像も組み立てられなかったので厚めに取っていたが、
 * 0.6.0 で SDK が直列化したため、次のフレームのパケットは後ろに並ぶだけになった。
 * **待っている間は次の絵を作らない**ので、ここはそのまま体感の遅さになる。200ms から詰めた。
 */
const val SETTLE_MS = 80L

/**
 * 首の傾きがこれだけ変わったら描き直す。
 *
 * 傾きは方位も高度も変えないので、[REDRAW_DEG] では引っかからない。
 * 画角 35° の端で 6° 回すと 1.8° ずれるので、**方位のしきい値より小さくする**。
 */
const val REDRAW_ROLL_DEG = 5.0

/**
 * 先出しの外挿をどれだけ割り引くか（0..1）。
 *
 * **行き過ぎるより届かないほうが安全。** 足りない分は止まったあとの描き直しが埋めるが、
 * 行き過ぎた絵は「合っていない星図」として出たままになる。
 */
const val PREDICT_DAMPING = 0.6

/**
 * 先出しを繰り返さない間隔。
 *
 * 首を振り続けている間に何枚も先出しすると、**転送のたびに画面が消えて点滅になる**
 * （動きに追従させない理由そのもの）。転送 1 枚が 0.4 秒なので、その 3 倍を空ける。
 */
const val PREDICT_COOLDOWN_MS = 1_200L

/**
 * 首の傾きを寄せる速さ（0..1）。
 *
 * 加速度は 1 サンプルごとに揺れるので、そのまま使うと絵がぱたぱた回る。
 * 10Hz で 0.2 なら、傾けてから 1 秒ほどで追いつく。
 */
const val ROLL_SMOOTHING = 0.2

/**
 * 追従ループ 1 周ぶんの判断。**時刻は引数で受ける**ので JVM テストで固定できる。
 *
 * 判定だけを持ち、送る・焼くはしない（送る側の都合は StarMapScreen / FrameSender が知っている）。
 */
/**
 * 2 つの視線の、**空の上での隔たり**[度]。
 *
 * **方位の差をそのまま使わない。** 天頂に近いほど方位は同じ首の動きで大きく動くので、
 * 高度 80° では方位 6° が空の上では 1.0° にしかならない。生の差で測ると、
 * **空がほとんど動いていないのに 0.4 秒の暗転（転送）が走る**。逆に真上では
 * わずかな揺れが 1° を超えて「動いている」になり、星図が出てこない。
 *
 * [HeadMotion] は最初から cos(高度) を掛けて測っているので、判定側をそちらへ揃える。
 */
fun lookSeparationDeg(fromAzDeg: Double, fromAltDeg: Double, toAzDeg: Double, toAltDeg: Double): Double =
    angleBetweenDeg(enu(fromAzDeg, fromAltDeg), enu(toAzDeg, toAltDeg))

class RedrawDecider {

    private var previousAz = Double.NaN
    private var previousAlt = Double.NaN
    private var movedAt = 0L
    private var predictedAt = 0L

    /**
     * 首が止まっているか。**呼ぶたびに直前の視線が進む**ので 1 周に 1 回だけ呼ぶ。
     * [STILL_DEG] を超えて動いたら「動いている」、そこから [STILL_MS] 静止で「止まった」。
     */
    fun settle(nowMillis: Long, azDeg: Double, altDeg: Double): Boolean {
        if (!previousAz.isNaN()) {
            val step = lookSeparationDeg(previousAz, previousAlt, azDeg, altDeg)
            if (step > STILL_DEG) movedAt = nowMillis
        }
        previousAz = azDeg
        previousAlt = altDeg
        return nowMillis - movedAt > STILL_MS
    }

    /** 止まっているとき、送り直す価値があるか（視線 6°・傾き 5°・観測条件の変化） */
    fun shouldRedraw(settled: Boolean, observationChanged: Boolean, driftDeg: Double, rolledDeg: Double): Boolean =
        settled && (observationChanged || driftDeg > REDRAW_DEG || rolledDeg > REDRAW_ROLL_DEG)

    /** 減速に入ったら「止まる先」へ 1 枚だけ先出しするか。連発は [PREDICT_COOLDOWN_MS] で抑える */
    fun shouldPredict(nowMillis: Long, driftDeg: Double, slowing: Boolean): Boolean =
        driftDeg > REDRAW_DEG && slowing && nowMillis - predictedAt > PREDICT_COOLDOWN_MS

    fun onPredicted(nowMillis: Long) {
        predictedAt = nowMillis
    }

    /** ふつうの描き直しが通ったら、先出しの間隔は数えなおす */
    fun onDrawn() {
        predictedAt = 0L
    }
}
