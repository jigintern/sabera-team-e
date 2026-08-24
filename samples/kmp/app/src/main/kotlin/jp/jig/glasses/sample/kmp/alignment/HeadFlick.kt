package jp.jig.glasses.sample.kmp.alignment

import kotlin.math.abs

/** うなずき 1 回の向き。上を向いたか、下を向いたか */
enum class HeadFlick { UP, DOWN }

/**
 * 首の**上下フリック**（速くうなずいて、すぐ戻す動き）を拾う。
 *
 * **「首の向き」では操作させない。** 一度「首を振ったら空を見たいということ」として
 * 星図へ戻す設計を入れたが、**解説を読んでいる途中で見上げただけで消えた**ので撤去した
 * （app-flow.md）。向きは見たいものを決めるためのもので、命令ではない。
 *
 * **フリックは向きではなく「振って戻る」形で見分ける。** ゆっくり見上げたときは
 * [baseline] が付いていくので反応せず、**速く振って元へ戻ったときだけ**発火する。
 * 戻りを待つぶん 0.1〜0.5 秒遅れるが、**その戻りこそが「操作のつもり」の証拠**なので
 * 削れない（削ると上の失敗をなぞることになる）。
 *
 * **使ってよいのは、首が視線ポインタになっていない画面だけ。** 星図を出している間は
 * 頭の向き＝見ている空なので、命令に使うと**命令のたびに見る空が変わる**うえ、
 * 速い首振りは方位補正がいちばん苦手なところ（10Hz の取りこぼしで 90° あたり 5〜15° 足りない）。
 * いまの用途は解説画面の字幕送りだけ（`GlassPage.EXPLANATION`）。
 *
 * **入るのは 6DoF のピッチ**（重力から出た絶対値なのでドリフトしない）。上向きが正。
 * **6DoF は 10Hz なので、しきい値はどれも 3〜8 サンプルで届く大きさにしてある。**
 *
 * Android に触らないので JVM テストで固定できる。**値はどれも実機未確認。**
 */
class HeadFlickDetector(
    /** ここまで振れたら「振った」とみなす角度[度]。読んでいるだけの揺れ（1〜2°）と分ける */
    private val triggerDeg: Double = TRIGGER_DEG,
    /** ここまで戻ったら「戻った」とみなす角度[度] */
    private val returnDeg: Double = RETURN_DEG,
    /** 1 サンプルでこれ以下しか動かなければ「ゆっくり」。基準はそのときだけ動く */
    private val creepDeg: Double = CREEP_DEG,
    /** 振ってから戻るまでに許す時間[ms]。**過ぎたらただ見上げただけ**として捨てる */
    private val returnMs: Long = RETURN_MS,
    /** 1 回発火してから次を拾うまで[ms]。**1 回のうなずきで 1 行**にする */
    private val cooldownMs: Long = COOLDOWN_MS,
) {

    /** 振れ幅を測る基準。**ゆっくりした動きにはこれが付いていく**ので反応しない */
    private var baseline = Double.NaN

    /** 直前のピッチ。**いま速く動いているか**を見るために持つ（[CREEP_DEG]） */
    private var lastPitch = Double.NaN
    private var swinging = false
    private var swingStartedAt = 0L

    /** 振っている間のいちばん大きな振れ。**向きはここで決まる**（戻り際の符号では決まらない） */
    private var peak = 0.0

    /**
     * 最後に出した時刻。まだ 1 回も出していなければ null。
     *
     * **番兵の値（`Long.MIN_VALUE`）を置かない。** `atMs - firedAt` が桁あふれして
     * **負になり、いつまでも「出したばかり」と判定されて一度も発火しない**。
     */
    private var firedAt: Long? = null

    /**
     * ピッチを 1 サンプル入れる。フリックが成立した瞬間だけ向きを返す。
     *
     * @param atMs 6DoF の `timestampMs`（**受信時刻ではない**。取りこぼしても間隔が正しい）
     * @param pitchDeg 上向きが正のピッチ[度]
     */
    fun add(atMs: Long, pitchDeg: Double): HeadFlick? {
        if (baseline.isNaN()) {
            rebase(pitchDeg)
            return null
        }
        // 出したばかりの間は基準に貼り付けておく。戻り際の揺れで 2 回続けて出さない
        firedAt?.let { at ->
            if (atMs - at < cooldownMs) {
                rebase(pitchDeg)
                return null
            }
        }

        val delta = pitchDeg - baseline
        // **動いている最中は基準を止める。** ここを止めないと、振っている間に基準が
        // 付いていって**振れ幅を食べてしまい**、しきい値の 2 倍近く振らないと届かなくなる
        val creeping = lastPitch.isNaN() || abs(pitchDeg - lastPitch) <= creepDeg
        lastPitch = pitchDeg
        if (!swinging) {
            if (abs(delta) >= triggerDeg) {
                swinging = true
                swingStartedAt = atMs
                peak = delta
            } else if (creeping) {
                // **ゆっくり見上げる動きは、基準ごと動く。** ここが「向きでは操作しない」の実体
                baseline += delta * BASELINE_FOLLOW
            }
            return null
        }

        if (abs(delta) > abs(peak)) peak = delta
        // 戻ってこない ＝ そちらを見たいだけ。基準を置き直して何も出さない
        if (atMs - swingStartedAt > returnMs) {
            swinging = false
            rebase(pitchDeg)
            return null
        }
        if (abs(delta) > returnDeg) return null

        swinging = false
        firedAt = atMs
        val direction = if (peak > 0.0) HeadFlick.UP else HeadFlick.DOWN
        rebase(pitchDeg)
        return direction
    }

    /** 画面が変わったときに呼ぶ。**途中まで振れていた状態を持ち越さない** */
    fun clear() {
        baseline = Double.NaN
        lastPitch = Double.NaN
        swinging = false
        peak = 0.0
        firedAt = null
    }

    private fun rebase(pitchDeg: Double) {
        baseline = pitchDeg
        lastPitch = pitchDeg
        swinging = false
        peak = 0.0
    }

    companion object {
        /**
         * 振ったとみなす角度[度]。
         *
         * 字幕を読んでいるだけの首の揺れは 1〜2° なので、そこから十分離す。
         * **12° では大きすぎた**（実機で「もっと小さく振りたい」）。
         * 動いている間は基準を止めるようにしたので、**ここの数字がそのまま必要な振れ幅**になる。
         */
        const val TRIGGER_DEG = 6.0

        /**
         * 戻ったとみなす角度[度]。ぴったり戻ることは無いので幅を持たせる。
         *
         * **[TRIGGER_DEG] の半分より小さくする。** 近すぎると、振れ幅のまわりの揺れだけで
         * 「振って戻った」が成立してしまう。
         */
        const val RETURN_DEG = 2.5

        /**
         * 「ゆっくり」とみなす 1 サンプルの動き[度]。10Hz なので 15°/秒。
         *
         * これより速く動いている間は基準を止める。空を見回す速さ（30〜100°/秒）は
         * ここを軽く超えるが、そのときは**戻ってこない**ので発火しない。
         */
        const val CREEP_DEG = 1.5

        /** 戻るまでに許す時間[ms]。10Hz で 8 サンプル */
        const val RETURN_MS = 800L

        /** 次を拾うまで[ms]。**1 回のうなずきで 1 行**にする */
        const val COOLDOWN_MS = 700L

        /**
         * 基準が動きに付いていく速さ（0..1）。
         *
         * 10Hz で 0.15 なら、**1 サンプルあたり 0.5°（＝ 3 秒で 15°）のゆっくりした動き**は
         * 基準との差が 3〜4° にしかならず発火しない。
         * **1 サンプルあたり 5°（＝ 0.3 秒で 15°）の速い動き**なら差が開いて発火する。
         */
        private const val BASELINE_FOLLOW = 0.15
    }
}
