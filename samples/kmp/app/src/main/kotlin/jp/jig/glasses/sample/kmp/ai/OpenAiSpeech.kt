package jp.jig.glasses.sample.kmp.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 解説文を OpenAI に読み上げさせる。
 *
 * 端末の `TextToSpeech` は棒読みで、**目指しているミニプラネタリウムの雰囲気を壊す**。
 * `gpt-4o-mini-tts` は [INSTRUCTIONS] で話し方そのものを指示できるので、
 * 「落ち着いた解説員」という肝心のところを注文できる。
 *
 * **受け取るのは PCM。** 届いたぶんから鳴らせるので、全部できるまで待たずに喋り出せる
 * （issue #20 の「返答音声データ垂れ流し」）。mp3 や Opus のほうが軽いが、
 * 途中から鳴らすにはデコーダを挟むことになる。**48KB/秒 は夜の屋外では細いので、
 * 失敗したら端末の読み上げに落とす**（[CloudVoice]）。
 */
class OpenAiSpeech(
    private val apiKey: String,
    private val voice: String,
    private val model: String,
    private val endpoint: String = SPEECH,
) {

    val configured: Boolean get() = apiKey.isNotEmpty()

    /** 話者の識別。声を変えたらキャッシュも別扱いにしないと、前の声が混ざって出る */
    val signature: String get() = "$model|$voice"

    /**
     * 音声を作らせて、届いたぶんから [onPcm] に渡す。24kHz / 16bit / モノラルの生 PCM。
     *
     * **[onPcm] に渡す配列は使い回す。** 溜めておきたいならその場で写す。
     * 失敗は [SpeechException]（HTTP）か [IOException]（通信）で返す。
     */
    suspend fun stream(text: String, onPcm: (ByteArray, Int) -> Unit) = withContext(Dispatchers.IO) {
        require(configured) { "API キーが設定されていない" }
        val body = buildRequestBody(model, voice, text).toString().toByteArray(Charsets.UTF_8)

        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Authorization", "Bearer $apiKey")
        }

        try {
            connection.outputStream.use { it.write(body) }
            val status = connection.responseCode
            if (status !in 200..299) {
                val detail = connection.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
                throw SpeechException(status, OpenAiClient.errorMessage(status, detail))
            }
            val buffer = ByteArray(CHUNK_BYTES)
            connection.inputStream.use { input ->
                while (currentCoroutineContext().isActive) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read > 0) onPcm(buffer, read)
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    /** 直しても直らない失敗（キーが違う・モデル名が違う）を見分けるために status を持つ */
    class SpeechException(val status: Int, message: String) : IOException(message) {
        /** 次に呼んでも同じように落ちるか。そうなら AI 音声を諦めて端末の読み上げに戻す */
        val permanent: Boolean get() = status == 400 || status == 401 || status == 403 || status == 404
    }

    companion object {
        const val SPEECH = "https://api.openai.com/v1/audio/speech"

        /** `response_format = pcm` の形式。AudioTrack に渡すときに要る */
        const val SAMPLE_RATE = 24_000

        const val DEFAULT_MODEL = "gpt-4o-mini-tts"
        const val DEFAULT_VOICE = "sage"

        /**
         * 話し方の注文。**issue #20 の本体はこの文字列**。
         *
         * 文の中身（何を言うか）は [OpenAiClient] のプロンプトが決めていて、ここは言い方だけ。
         */
        const val INSTRUCTIONS =
            "落ち着いたプラネタリウムの解説員として読んでください。\n" +
                "声色は穏やかで低め、暗い場内で静かに語りかけるように。\n" +
                "速さはゆっくりめ。句点では息を置き、次の文へ急がない。\n" +
                "抑揚は控えめにして、驚いたり盛り上げたりしない。\n" +
                "星や星座の名前はていねいに、はっきり発音する。"

        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000

        /** 読み出す単位。小さすぎると呼び出し回数だけ増え、大きすぎると鳴り出しが遅れる */
        private const val CHUNK_BYTES = 4096

        /**
         * リクエスト本文。
         *
         * `instructions` は `tts-1` / `tts-1-hd` では効かないので、そのときは付けない
         * （**話し方を指示できることが gpt-4o-mini-tts を選んだ理由**なので、
         * 旧モデルに落とすなら棒読みに戻ることを承知の上で）。
         */
        fun buildRequestBody(model: String, voice: String, text: String): JSONObject {
            val body = JSONObject()
                .put("model", model)
                .put("voice", voice)
                .put("input", text)
                // 生 PCM。届いたぶんから鳴らすためで、デコーダを持たずに済む
                .put("response_format", "pcm")
            if (!model.startsWith("tts-1")) body.put("instructions", INSTRUCTIONS)
            return body
        }
    }
}
