package jp.jig.glasses.sample.kmp.ai

import java.net.HttpURLConnection
import java.net.URL

/** OpenAIのJSON/PCM APIで共通の接続設定。 */
internal object OpenAiHttp {
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000

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
}
