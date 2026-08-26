package jp.jig.glasses.sample.kmp.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import jp.jig.glasses.sample.kmp.alignment.CalibrationEstimate
import jp.jig.glasses.sample.kmp.alignment.CalibrationResult
import kotlinx.coroutines.launch

/**
 * 方位合わせのうち、**センサーの値そのもの以外**の状態。
 *
 * 生の 6DoF・地磁気・測位は 10Hz で画面の effect が書くので composable に残し、
 * ここは「進んでよいか」「確定したか」「立ち直りの操作」を持つ。
 * **確定の操作は無い**（押す動作そのものが精度を壊す）ので、揃ったまま止まると自動で進む。
 */
internal class CalibrationViewModel : ViewModel() {

    /** 精度条件が揃ってからの進み具合（0..1）。的の外周のゲージがこれで満ちる */
    var holdProgress by mutableStateOf(0f)
        private set

    /**
     * 二度渡さないための札。onCalibrated で画面は切り替わるが、
     * そのあとの合成が 1 回走ることがある
     */
    var committed by mutableStateOf(false)
        private set

    /** 立ち直りの操作を送っている最中か */
    var recovering by mutableStateOf(false)
        private set

    /** 立ち直りの操作の結果 */
    var recoveryNote by mutableStateOf<String?>(null)
        private set

    /** グラスが黙り始めた時刻。送り直し・再起動のたびに置き直す */
    var waitingSince by mutableStateOf(System.currentTimeMillis())
        private set

    fun resetHold() {
        holdProgress = 0f
    }

    fun advanceHold(heldMillis: Long) {
        holdProgress = (heldMillis.toFloat() / AUTO_CONFIRM_MS).coerceIn(0f, 1f)
    }

    /** 観測画面へ渡して確定する。渡すのは直近の静止区間の平均（CalibrationEstimator） */
    fun commit(measured: CalibrationEstimate, nowMillis: Long, onCalibrated: (CalibrationResult) -> Unit) {
        if (committed) return
        committed = true
        onCalibrated(
            CalibrationResult(
                headingOffsetDeg = measured.headingOffsetDeg,
                pitchOffsetDeg = measured.pitchOffsetDeg,
                calibratedAt = nowMillis,
                headingStdDeg = measured.headingStdDeg,
                pitchStdDeg = measured.pitchStdDeg,
                sampleCount = measured.sampleCount,
            ),
        )
    }

    /**
     * 送り直し・再起動を 1 本にした立ち直りの操作。
     *
     * [running] は送っている間の表示、[done] / [failed] は結果の言い方。
     * **押している間は二重に走らせない**（BLE に同じ電文が並ぶ）。
     */
    fun recover(running: String, done: String, failed: (String?) -> String, action: suspend () -> Unit) {
        if (recovering) return
        recovering = true
        recoveryNote = running
        viewModelScope.launch {
            val result = runCatching { action() }
            waitingSince = System.currentTimeMillis()
            recovering = false
            recoveryNote = if (result.isSuccess) done else failed(result.exceptionOrNull()?.message)
        }
    }

    /** 画面を出るときに呼ぶ。**入り直したら真っさら** */
    fun leave() {
        holdProgress = 0f
        committed = false
        recovering = false
        recoveryNote = null
        waitingSince = System.currentTimeMillis()
    }
}

/**
 * 進んでよいかの判定。**生の信頼度と、進んでよいかを分ける。**
 *
 * 逃がしたときに「磁気精度は足りている」と見せてしまうと、ずれた方位で合わせたことが
 * 誰にも分からなくなる。**歪みでは止めない**（机の上では常時弾かれ、確認が何もできなかった）。
 */
internal fun calibrationReady(
    imuFresh: Boolean,
    headingReady: Boolean,
    compassReady: Boolean,
    facingReady: Boolean,
    stabilityReady: Boolean,
): Boolean = imuFresh && headingReady && compassReady && facingReady && stabilityReady
