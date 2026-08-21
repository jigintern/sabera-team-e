package jp.jig.glasses.sample.kmp.ai

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** OpenAIのJSON/PCM APIで共通の接続設定。 */
internal object OpenAiHttp {
    /** 圏外で15秒待たせていた値を短縮。1回だけの再試行を含めても長く黙らせない。 */
    private const val CONNECT_TIMEOUT_MS = 8_000

    /** SSE/PCMの次の1チャンクを待つ上限。ストリーム全体の長さには影響しない。 */
    private const val READ_TIMEOUT_MS = 12_000

    fun openPost(endpoint: String, apiKey: String): HttpURLConnection =
        (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            // 夜の屋外では、待ち続けるより打ち切って端末側の案内へ戻す。
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Authorization", "Bearer $apiKey")
        }

    /** 受信前だけ再試行してよいHTTPステータス。本文や音声の途中では重複するので再試行しない。 */
    fun isTransientStatus(status: Int): Boolean = status == 408 || status == 409 || status == 429 || status >= 500

    /** 接続そのものの一時失敗。認証・モデル名などの恒久エラーは含めない。 */
    fun isTransientFailure(error: Throwable): Boolean = when (error) {
        is OpenAiStatusException -> isTransientStatus(error.status)
        is java.net.UnknownHostException,
        is java.net.SocketTimeoutException,
        is java.net.ConnectException,
        is java.net.SocketException,
        is java.io.EOFException,
        is javax.net.ssl.SSLException,
        -> true
        else -> false
    }
}

/** HTTPステータスとrequest IDを失わずに上位へ返す。 */
open class OpenAiStatusException(
    val status: Int,
    val requestId: String?,
    message: String,
) : IOException(message)

/** 実機ログへ出す、本文や認証情報を含まない通信計測。 */
data class OpenAiRequestTrace(
    val operation: String,
    val attempt: Int,
    val requestId: String?,
    val firstByteMs: Long?,
    val totalMs: Long,
    val bytes: Long,
    val completed: Boolean,
)
