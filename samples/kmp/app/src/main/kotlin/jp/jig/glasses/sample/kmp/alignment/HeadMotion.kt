package jp.jig.glasses.sample.kmp.alignment

import jp.jig.glasses.sample.kmp.sky.Look
import jp.jig.glasses.sample.kmp.sky.RAD
import jp.jig.glasses.sample.kmp.sky.clampAltDeg
import jp.jig.glasses.sample.kmp.sky.normalizeDeg
import kotlin.math.cos
import kotlin.math.hypot

/**
 * 首の動きの速さと、**絵が届くころの視線**。
 *
 * 星図は 1 枚の転送に 332〜390ms かかる。「止まってから送る」だと、
 * **止まってから絵が出るまで 0.5 秒以上**かかり、その間は転送で画面が消えている。
 * 一方で動いている最中に送ると、届いたときには視線がもう別の方を向いている。
 *
 * そこで**止まりきるのを待たず、止まる先へ送る**。人の首は急には止まらないので、
 * 減速に入った時点で「転送が終わるころの視線」を外挿して、そこ向きの絵を焼く。
 * バイトは 1 つも増えないまま、絵が出るのが 0.2〜0.4 秒早くなる（**実機で未確認**）。
 *
 * 外挿は必ず割り引く（[predict] の `damping`）。**行き過ぎるより届かないほうが安全**で、
 * 足りない分は次の描き直しが埋める。行き過ぎた絵は「合っていない星図」になる。
 *
 * 天頂付近では方位が少し首を振るだけで大きく動くので、速さは **cos(高度) を掛けた
 * 球面上の角度**で測る（[jp.jig.glasses.sample.kmp.ui.StarMapScreen] の描き直し判定は
 * 方位と高度の生の差を見ているので、こちらとは別の物差し）。
 *
 * Android に触らないので JVM テストで固定できる。
 */
class HeadMotion(private val windowMs: Long = WINDOW_MS) {

    private class Sample(val atMs: Long, val azDeg: Double, val altDeg: Double)

    private val samples = ArrayDeque<Sample>()

    fun add(atMs: Long, look: Look) {
        samples.addLast(Sample(atMs, look.azDeg, look.altDeg))
        // 窓から出た古いサンプルは捨てる。2 点は速さを出すために必ず残す
        while (samples.size > 2 && atMs - samples.first().atMs > windowMs) samples.removeFirst()
    }

    /** 方位合わせのやり直しなど、視線の基準が変わったときに呼ぶ */
    fun clear() {
        samples.clear()
    }

    /** 角速度[度/秒]。サンプルが 1 つ以下なら 0（＝止まっている扱い） */
    val speedDps: Double
        get() = rate(samples.firstOrNull(), samples.lastOrNull())

    /**
     * 減速しているか。**窓の後半が前半より遅ければ「止まりかけ」**とみなす。
     *
     * 速さだけで判定すると、ゆっくり流し見しているだけの首も「止まった」と読んでしまい、
     * 送るたびに転送で画面が消える。
     */
    val slowing: Boolean
        get() {
            // **4 点を要求しているのは事故ではない。いまは意図してここで止めている**（#152）。
            //
            // 窓は 300ms で、呼ぶ側は `delay(POLL_MS = 100)` のループから足す。`delay` は
            // 「100ms 以上」しか保証しないので間隔は 100ms を超え、**4 点目は窓から出る**
            // （間隔 100ms なら 4 点、101ms なら 3 点）。つまりこの 1 行が
            // **先出しを止めている栓**で、[jp.jig.glasses.sample.kmp.glass.RedrawDecider.shouldPredict]
            // へ渡る `slowing` が実機では立たない。
            //
            // 3 点へ緩めて実機で走らせたら（#149 / #150）、**首を振っている最中に何度も点滅した**。
            // `slowing`（後半が前半の [SLOWING_RATIO] 倍未満）は「止まる直前」だけでなく
            // **流し見の速度のゆらぎでも成立する**ので、止まる気のない首振り中も
            // `PREDICT_COOLDOWN_MS` ごとに発火し、そのたび 369〜540ms の暗転が入る。
            //
            // **緩めるなら「ほぼ止まっている」条件を足してから**（`speedDps` の上限）。
            // 経緯と測った数字は docs/team-e/73_backlog.md。
            if (samples.size < 4) return false
            val middle = samples[samples.size / 2]
            val first = rate(samples.first(), middle)
            if (first <= 0.0) return false
            return rate(middle, samples.last()) < first * SLOWING_RATIO
        }

    /**
     * [horizonMs] 後の視線。
     *
     * @param damping そのまま進むとは限らないぶんの割引（0..1）
     * @param maxLeadDeg 外挿の頭打ち。速い首振りで遠くを描かないための保険
     */
    fun predict(
        now: Look,
        horizonMs: Long,
        damping: Double,
        maxLeadDeg: Double = MAX_LEAD_DEG,
    ): Look {
        val first = samples.firstOrNull() ?: return now
        val last = samples.lastOrNull() ?: return now
        val seconds = (last.atMs - first.atMs) / 1000.0
        if (seconds <= 0.0) return now
        val horizon = horizonMs / 1000.0 * damping
        var az = normalizeDeg(last.azDeg - first.azDeg) / seconds * horizon
        var alt = (last.altDeg - first.altDeg) / seconds * horizon
        val lead = hypot(az * cos(now.altDeg * RAD), alt)
        if (lead > maxLeadDeg) {
            val scale = maxLeadDeg / lead
            az *= scale
            alt *= scale
        }
        return Look(
            (normalizeDeg(now.azDeg + az) + 360.0) % 360.0,
            clampAltDeg(now.altDeg + alt),
        )
    }

    private fun rate(from: Sample?, to: Sample?): Double {
        if (from == null || to == null) return 0.0
        val seconds = (to.atMs - from.atMs) / 1000.0
        if (seconds <= 0.0) return 0.0
        val az = normalizeDeg(to.azDeg - from.azDeg) * cos(to.altDeg * RAD)
        return hypot(az, to.altDeg - from.altDeg) / seconds
    }

    companion object {
        /**
         * 速さを測る窓。
         *
         * 6DoF は 10Hz なので 300ms で 3〜4 サンプル。短くすると 1 サンプルの揺れを
         * 速さと読み、長くすると止まったことに気づくのが遅れる。
         */
        const val WINDOW_MS = 300L

        /** 後半がこの割合より遅くなったら減速とみなす */
        private const val SLOWING_RATIO = 0.7

        /** 外挿の上限[度]。画角 35° に対しておよそ 1/4 */
        private const val MAX_LEAD_DEG = 8.0
    }
}
