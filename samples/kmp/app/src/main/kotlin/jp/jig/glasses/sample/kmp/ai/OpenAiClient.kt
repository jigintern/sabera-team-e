package jp.jig.glasses.sample.kmp.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.EOFException
import java.io.IOException
import java.io.InputStreamReader

/**
 * 星座の解説を OpenAI に作らせる。
 *
 * 送るのは「いま星図に描いた絵」と「端末が計算した星座名」の両方。
 * 絵だけだと緑 8 階調の点描を読み違えても検知できないが、名前は端末が持っている確定値なので、
 * 併せて渡せば同定を間違えようがない。絵は「その名前の星座がいまどう見えているか」を伝える役。
 *
 * 本文の組み立てとパースは HTTP から切り離してある（Android に触らないので JVM テストで検算できる）。
 */
class OpenAiClient(
    private val apiKey: String,
    private val model: String,
    /** 推論の強さ。**空なら送らない**（推論を持たないモデルに送ると 400 で弾かれる） */
    private val reasoningEffort: String = "",
    private val endpoint: String = CHAT_COMPLETIONS,
    private val onTrace: (OpenAiRequestTrace) -> Unit = {},
) {

    val configured: Boolean get() = apiKey.isNotEmpty()

    /**
     * 解説をSSEで受け取る。[onDelta]には生成途中の差分をすぐ渡す。
     *
     * 通信断・408・409・429・5xxは、**本文を1文字も受け取っていない場合だけ**
     * 1回再試行する。途中まで受け取ったあとにやり直すと、同じ文章を二重に表示・
     * 読み上げてしまうため。
     */
    suspend fun explain(request: ExplainRequest, onDelta: (String) -> Unit = {}): String = withContext(Dispatchers.IO) {
        require(configured) { "API キーが設定されていない" }
        val body = buildRequestBody(model, request, reasoningEffort).toString().toByteArray(Charsets.UTF_8)
        var lastFailure: Throwable? = null
        for (attempt in 1..MAX_ATTEMPTS) {
            var received = false
            try {
                return@withContext streamOnce(body, attempt) { delta ->
                    if (delta.isNotEmpty()) received = true
                    onDelta(delta)
                }
            } catch (error: Throwable) {
                if (received || attempt >= MAX_ATTEMPTS || !OpenAiHttp.isTransientFailure(error)) {
                    throw error
                }
                lastFailure = error
                delay(RETRY_BASE_MS * attempt)
            }
        }
        throw lastFailure ?: IOException("AI文章の生成に失敗")
    }

    private fun streamOnce(body: ByteArray, attempt: Int, onDelta: (String) -> Unit): String {
        val startedAt = System.nanoTime()
        var firstByteMs: Long? = null
        var bytes = 0L
        var requestId: String? = null
        var completed = false
        val connection = OpenAiHttp.openPost(endpoint, apiKey)
        connection.setRequestProperty("Accept", "text/event-stream")

        try {
            connection.outputStream.use { it.write(body) }
            val status = connection.responseCode
            requestId = connection.getHeaderField("x-request-id")
            if (status !in 200..299) {
                val detail = connection.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
                throw OpenAiStatusException(status, requestId, errorMessage(status, detail))
            }

            val text = StringBuilder()
            var finishReason = ""
            var refusal = ""
            var streamDone = false
            BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (!line.startsWith(SSE_DATA_PREFIX)) continue
                    val data = line.removePrefix(SSE_DATA_PREFIX).trimStart()
                    val event = parseStreamData(data)
                    if (event.error.isNotEmpty()) throw IOException("AIストリームエラー: ${event.error}")
                    if (event.done) {
                        streamDone = true
                        break
                    }
                    if (event.refusal.isNotEmpty()) refusal += event.refusal
                    if (event.finishReason.isNotEmpty()) finishReason = event.finishReason
                    if (event.delta.isNotEmpty()) {
                        if (firstByteMs == null) firstByteMs = elapsedMs(startedAt)
                        bytes += event.delta.toByteArray(Charsets.UTF_8).size
                        text.append(event.delta)
                        onDelta(event.delta)
                    }
                }
            }

            if (!streamDone) throw EOFException("AI文章のストリームが完了前に切れた")

            if (refusal.isNotBlank()) throw EmptyReplyException("AI が回答を断った: ${refusal.trim()}")
            val result = text.toString().trim()
            if (result.isEmpty()) throw EmptyReplyException(emptyReplyMessage(finishReason))
            completed = true
            return result
        } finally {
            val trace = OpenAiRequestTrace(
                operation = "text",
                attempt = attempt,
                requestId = requestId,
                firstByteMs = firstByteMs,
                totalMs = elapsedMs(startedAt),
                bytes = bytes,
                completed = completed,
            )
            runCatching { onTrace(trace) }
            connection.disconnect()
        }
    }

    companion object {
        const val CHAT_COMPLETIONS = "https://api.openai.com/v1/chat/completions"
        private const val SSE_DATA_PREFIX = "data:"
        private const val MAX_ATTEMPTS = 2
        private const val RETRY_BASE_MS = 350L

        /**
         * 出力の上限。
         *
         * **推論トークンと枠を共用する**ので、推論するモデルでは思考だけで使い切ることがある。
         * 400 にしていたときは、実測で 4 回中 4 回が `finish_reason: length` で本文が空だった
         * （プロンプトを 250 文字・指示 9 個に厚くしたぶん悪化した。200 文字の頃は 5 回中 3 回）。
         * 喋る長さは [SYSTEM_PROMPT]（4〜5 文・250 文字程度）で縛っているので、
         * ここを広げても喋る量は増えない。**切られないための余裕**でしかない。
         */
        private const val MAX_COMPLETION_TOKENS = 2000

        /**
         * 読み上げ前提の指示。
         *
         * 読み上げは記号をそのまま読むので、箇条書きや括弧が混ざると聞けたものではない。
         * 長さを絞るのは、解説中に別の方向を向いても最後まで続ける仕様（app-flow.md）のため。
         * **250 文字で読み上げは 45 秒前後。** その間は次のタップを受けないので、これ以上は伸ばさない。
         *
         * **わざとらしさは言い方から出て、ロマンは中身から出る。**
         * 飾った言い回しはどの声で読んでも芝居に聞こえるので禁じ、代わりに
         * **距離や年数といった事実で宇宙の大きさを出させる**（「この光は十七年前に出た」）。
         * 情緒を言葉づかいで作らせると芝居に戻る（issue #20）。
         *
         * **箇条書きを増やすほど 1 つずつは薄まる。** 実測で、11 個並べた版は末尾の 2 つが
         * 無視された（名前の言い直しと「楽しんでください」が残った）。**守らせたい注意は
         * [ExplainRequest.userText] の末尾に置く**（いちばん効く位置）。
         */
        private const val SYSTEM_PROMPT =
            "あなたはプラネタリウムの解説員です。スマートグラス越しに空を見ている人へ、" +
                "いま視野に入っている星座を解説します。\n" +
                "・読み上げる文章なので、箇条書き・記号・括弧・見出しを使わず、地の文だけで書く\n" +
                "・4 文から 5 文、250 文字程度に収める\n" +
                "・その場で口に出す話し言葉で書く。もったいぶった言い回しや体言止めは使わない\n" +
                "・星座の探し方（目印になる明るい星や並び）を必ず 1 つ入れる\n" +
                "・距離や大きさの数字をひとつ入れて、宇宙の広さが体で分かるようにする。" +
                "「いま見えている光は十七年前に出たもの」のように、聞いた人が自分と引き比べられる形にする\n" +
                "・知って面白い雑学をひとつ添える\n" +
                "・神話や由来は一言まで\n" +
                "・ロマンは事実で出す。飾った言葉で出そうとしない\n" +
                "・視野に複数の星座があるときは、最初に挙がっている星座を主役にする"

        /**
         * リクエスト本文。画像は data URL で本文に埋める
         * （528×330 のほぼ真っ黒な PNG なので数 KB にしかならない）。
         */
        fun buildRequestBody(
            model: String,
            request: ExplainRequest,
            reasoningEffort: String = "",
        ): JSONObject {
            val content = JSONArray().apply {
                put(
                    JSONObject()
                        .put("type", "text")
                        .put("text", request.userText()),
                )
                request.pngBase64?.let { base64 ->
                    put(
                        JSONObject()
                            .put("type", "image_url")
                            .put(
                                "image_url",
                                JSONObject().put("url", "data:image/png;base64,$base64"),
                            ),
                    )
                }
            }

            return JSONObject()
                .put("model", model)
                .put("max_completion_tokens", MAX_COMPLETION_TOKENS)
                .put("stream", true)
                .put(
                    "messages",
                    JSONArray()
                        .put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
                        .put(JSONObject().put("role", "user").put("content", content)),
                )
                .apply {
                    // 空のまま送ると、推論を持たないモデルが 400 を返す。モデルを差し替えても壊れないよう、
                    // 値があるときだけ載せる
                    if (reasoningEffort.isNotEmpty()) put("reasoning_effort", reasoningEffort)
                }
        }

        /** Chat Completionsのdata-only SSEを1イベントずつ読む。 */
        internal fun parseStreamData(data: String): StreamEvent {
            if (data.trim() == "[DONE]") return StreamEvent(done = true)
            val json = JSONObject(data)
            val streamError = json.optJSONObject("error")?.optString("message").orEmpty()
            if (streamError.isNotEmpty()) return StreamEvent(error = streamError)
            val choice = json.optJSONArray("choices")?.optJSONObject(0)
                ?: return StreamEvent()
            val delta = choice.optJSONObject("delta")
            return StreamEvent(
                delta = delta?.optString("content").orEmpty(),
                refusal = delta?.optString("refusal").orEmpty(),
                finishReason = choice.optString("finish_reason"),
            )
        }

        private fun emptyReplyMessage(finishReason: String): String = when (finishReason) {
            // 推論が出力枠を食い潰した典型。reasoning_effort を下げるか枠を広げる
            "length" -> "トークン上限で切れて本文が空（推論が枠を使い切った可能性）"
            "content_filter" -> "フィルタに引っかかって本文が空"
            else -> "本文が空で返ってきた"
        }

        private fun elapsedMs(startedAt: Long): Long = (System.nanoTime() - startedAt) / 1_000_000

        /** HTTP エラーを人が読める形に。OpenAI は error.message に理由を入れてくる */
        fun errorMessage(status: Int, body: String): String {
            val detail = runCatching {
                JSONObject(body).optJSONObject("error")?.optString("message")
            }.getOrNull().orEmpty()
            return when {
                detail.isNotEmpty() -> "HTTP $status: $detail"
                status == 401 -> "HTTP 401: API キーが違う"
                else -> "HTTP $status"
            }
        }
    }
}

internal data class StreamEvent(
    val delta: String = "",
    val refusal: String = "",
    val finishReason: String = "",
    val error: String = "",
    val done: Boolean = false,
)

/**
 * 応答は返ったのに本文が無かった。**通信の失敗ではない**ので `IOException` と分ける。
 *
 * ここを一緒くたにしていたせいで、推論が枠を使い切っただけなのに
 * 「いまは通信ができないので」と喋っていた。
 */
class EmptyReplyException(message: String) : Exception(message)

/** 失敗の種類。喋り分けるために使う */
enum class FailureKind {
    /** 応答は返ったが本文が空。もう一度頼めば返ることがある */
    EMPTY,

    /** API がエラーを返した。キー・モデル名・レート制限など。再試行しても同じ */
    API,

    /** そもそも届いていない。圏外・タイムアウト */
    NETWORK,
}

/**
 * 例外を「何が起きたか」に翻訳する。
 *
 * **純関数にしてあるのは JVM テストで検算するため。** 実機でしか踏めない経路なので、
 * ここを取り違えると原因の切り分けが何時間も遅れる（実際に遅れた）。
 */
fun classifyFailure(e: Throwable): FailureKind = when (e) {
    is EmptyReplyException -> FailureKind.EMPTY
    is java.net.UnknownHostException,
    is java.net.SocketTimeoutException,
    is java.net.ConnectException,
    is java.net.SocketException,
    is java.io.EOFException,
    is javax.net.ssl.SSLException,
    -> FailureKind.NETWORK
    // errorMessage() が組み立てた HTTP エラーはここに来る
    is IOException -> FailureKind.API
    else -> FailureKind.API
}

/**
 * 解説を頼むときに渡すもの。
 *
 * 位置と時刻を必ず入れるのは、**間違っていても星図が「それらしく」出てしまう**から。
 * タイムゾーンの取り違えは 135°、時計の 1 時間ずれは 15° になるが、ユーザーには気づけない。
 * 解説に混ぜておけば、明らかにおかしいときに人間が気づける。
 */
data class ExplainRequest(
    /** 視野中心に近い順の星座名。端末が計算した確定値 */
    val constellations: List<String>,
    val latDeg: Double,
    val lonDeg: Double,
    /** 視線の方位角[度]。真北 = 0 の東回り */
    val azDeg: Double,
    /** 視線の仰角[度] */
    val altDeg: Double,
    /** 「2026-08-20 21:34 JST」のような、タイムゾーンまで含む表記 */
    val localTime: String,
    /** 星図の PNG を Base64 にしたもの。無しでも解説は作れる */
    val pngBase64: String? = null,
) {
    fun userText(): String = buildString {
        append("いま見ている星座は「")
        append(constellations.firstOrNull() ?: "不明")
        append("」です。")
        if (constellations.size > 1) {
            append("同じ視野には")
            append(constellations.drop(1).joinToString("、"))
            append("も入っています。")
        }
        append("\n観測地は北緯 ")
        append("%.3f".format(latDeg))
        append(" 度、東経 ")
        append("%.3f".format(lonDeg))
        append(" 度。日時は ")
        append(localTime)
        append("。視線は方位 ")
        append(azDeg.toInt())
        append(" 度、仰角 ")
        append(altDeg.toInt())
        append(" 度です。")
        if (pngBase64 != null) {
            append(
                "\n添付は、その視野をスマートグラスに出している星図です。" +
                    "黒が空、明るい点が星、細い線が星座線で、緑 1 色の 8 階調でしか描けません。" +
                    "星座名は端末が計算した確定値なので、画像から同定し直さず、" +
                    "この絵の中でその星座がどう見えているかを説明してください。",
            )
        }
        // **ここが最後に来るよう並べてある。** システム側の箇条書きに混ぜた版では
        // どちらも無視された（名前を言い直し、「楽しんでください」で締めた）
        append("\nこの星座について解説してください。")
        append("星座の名前は既に読み上げてあるので、名前を言い直さず続きから話してください。")
        append("最後は言い切って終わり、聞き手への呼びかけや誘いを付けないでください。")
    }
}
