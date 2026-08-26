package jp.jig.glasses.sample.kmp.alignment

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * 方位合わせのあいだ、進み具合を**手へ返す**。
 *
 * **かざしている人は腕の先の画面を読めない**（十字とマーカーを重ねている）ので、
 * 的の外周が満ちるのと同じことを振動でも返す。
 *
 * `LocalHapticFeedback` ではなく [Vibrator] を直に叩くのは、**強さを決められない**から。
 * 画面を見ずに屋外で受け取る合図なので、UI の軽いコツンでは気づかない（実機で弱すぎた）。
 * 揃った・刻み・崩れた・決まったで**強さと長さを変える**と、見なくても区別が付く。
 */
class HoldFeedback(context: Context) {

    private val vibrator: Vibrator? = runCatching {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    }.getOrNull()

    /** 条件が揃って数えはじめた合図 */
    fun start() = buzz(45, 200)

    /** 満ちていく途中の刻み。**いちばん多く鳴るので、いちばん短く** */
    fun tick() = buzz(35, 180)

    /** 崩れて 0 に戻った。**長く鈍く**して、進んだ合図と取り違えないようにする */
    fun lost() = buzz(90, 130)

    /** 決まった。**2 回打つ**（押していないのに終わるので、終わりだと分かる形にする） */
    fun done() {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        runCatching {
            v.vibrate(
                VibrationEffect.createWaveform(
                    longArrayOf(0, 60, 70, 140),
                    intArrayOf(0, 255, 0, 255),
                    -1,
                ),
            )
        }
    }

    private fun buzz(millis: Long, amplitude: Int) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        // 振れ幅を持たない端末では既定の強さで鳴る（弱くはなるが、無音にはしない）
        val level = if (v.hasAmplitudeControl()) amplitude else VibrationEffect.DEFAULT_AMPLITUDE
        runCatching { v.vibrate(VibrationEffect.createOneShot(millis, level)) }
    }
}
