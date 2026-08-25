package jp.jig.glasses.sample.kmp.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 接続確認の状態。**接続そのもの（BLE ダイアログ）は Android の仕事**なので、
 * ここは「選ばせている最中か」「何に失敗したか」だけを持つ。
 */
internal class ConnectionViewModel : ViewModel() {

    var scanning by mutableStateOf(false)
        private set

    /** 接続に失敗した理由。null なら出さない */
    var error by mutableStateOf<String?>(null)
        private set

    /** つながったら失敗表示は下げる */
    fun onConnected() {
        error = null
    }

    /**
     * 接続ダイアログを開く。[select] が SDK のダイアログ（Activity が要る）を包んで返す。
     * null は「選ばれなかった」。
     */
    fun connect(select: suspend () -> Any?) {
        error = null
        scanning = true
        viewModelScope.launch {
            try {
                if (select() == null) {
                    error = "SABERAが選択されませんでした。もう一度接続をお試しください。"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                error = connectionErrorMessage(e)
            } finally {
                scanning = false
            }
        }
    }

    /** 画面を出るときに呼ぶ。**入り直したら真っさら**という従来の見え方を保つ */
    fun reset() {
        scanning = false
        error = null
    }
}

/** 失敗の言い換え。**原因ごとに「次に何をすればよいか」まで言う** */
internal fun connectionErrorMessage(error: Throwable): String {
    val detail = error.message.orEmpty().lowercase()
    return when {
        error is SecurityException || "permission" in detail || "denied" in detail ->
            "Bluetoothの権限がありません。スマホの設定からSABERAアプリの「付近のデバイス」を許可してください。"
        "bluetooth" in detail && ("off" in detail || "disabled" in detail) ->
            "Bluetoothがオフになっています。Bluetoothをオンにしてから、もう一度お試しください。"
        "timeout" in detail || "timed out" in detail ->
            "接続が時間切れになりました。SABERAをスマホの近くに置き、電源を確認してもう一度お試しください。"
        "bond" in detail || "pair" in detail ->
            "SABERAとのペアリングに失敗しました。端末を近づけて、もう一度お試しください。"
        else ->
            "SABERAに接続できませんでした。電源とBluetoothを確認して、もう一度お試しください。"
    }
}
