package jp.jig.glasses.sample.kmp.openai

import jp.jig.glasses.sample.kmp.guide.GuideAsk
import jp.jig.glasses.sample.kmp.guide.GuideStep
import jp.jig.glasses.sample.kmp.narration.AskGuard
import jp.jig.glasses.sample.kmp.sky.GuidanceTarget
import jp.jig.glasses.sample.kmp.sky.GuidanceTargetKind
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** 対話の 1 発言。**台本には残さない**（画面を閉じたら消える） */
data class GuideChatTurn(val fromUser: Boolean, val text: String)

/**
 * AI の返事。[steps] が null なら「台本は変えずに答えただけ」。
 *
 * [dropped] は**候補に無い名前を返してきた数**。黙って捨てるが、
 * 何が起きたかは画面に出す（黙って減ると、書いたのに消えたように見える）。
 */
data class GuideChatReply(val reply: String, val steps: List<GuideStep>?, val dropped: Int = 0)

/**
 * 台本を対話で作らせる（詳細ガイド・toB）。
 *
 * [OpenAiGuide]（即興ガイド・一往復きり）と分けてあるのは、**積み上げる履歴を持つ**から。
 * 16_guide.md の 4 条件はここでも崩さない。
 *
 * - **通信するのは台本を作るときだけ。** 再生時は圏外でも最後まで喋る
 * - 失敗・圏外なら AI の口を出さず、同梱の文で組む（画面側）
 * - **星座を選ぶのは端末。** ここへ渡すのは端末が確定した候補だけで、
 *   **候補に無い名前が返ってきたら黙って捨てる**（[reply] の中で落とす）
 * - 返ってきた文も端末が検査する（[AskGuard.sanitizeAnswer]）
 *
 * 打たれた文そのものの判断（「その日オリオン座は出ていない」）は
 * **通信する前に端末が済ませる**（`guide/GuideAsk`）。
 */
class OpenAiGuideChat(
    private val apiKey: String,
    private val model: String,
    private val endpoint: String = OpenAiAsk.CHAT_COMPLETIONS,
    private val onTrace: (OpenAiRequestTrace) -> Unit = {},
) {
    val configured: Boolean get() = apiKey.isNotEmpty()

    /**
     * 1 往復。**書けなければ null**（呼ぶ側は同梱のままにする）。
     *
     * [candidates] は端末がその日その時間の空から確定した並び。
     * [current] はいまの台本で、AI には「これを直す」と伝える。
     */
    fun reply(
        instruction: String,
        candidates: List<GuidanceTarget>,
        current: List<GuideStep>,
        history: List<GuideChatTurn> = emptyList(),
    ): GuideChatReply? {
        require(configured) { "API キーが設定されていない" }
        if (candidates.isEmpty()) return null
        val content = request(instruction, candidates, current, history) ?: return null
        return parse(content, candidates.associate { it.nameJa to it.kind })
    }

    /** 返ってきた JSON を検査して詰め替える。**候補に無い名前と、検査に落ちた文は捨てる** */
    internal fun parse(content: String, allowed: Map<String, GuidanceTargetKind>): GuideChatReply? {
        val json = runCatching { JSONObject(content) }.getOrNull() ?: return null
        val reply = json.text("reply").orEmpty()
        val array = json.optJSONArray("steps")
            // 台本を変えずに答えただけ。**返事が空なら何も起きていないので null**
            ?: return if (reply.isEmpty()) null else GuideChatReply(reply, null)
        val steps = ArrayList<GuideStep>(array.length())
        var dropped = 0
        for (i in 0 until array.length()) {
            val step = array.optJSONObject(i)
            val name = step?.text("name")
            // **候補に無い名前は黙って捨てる。** 空に出ていない星座を載せられると、
            // 再生で全部飛んで「何も起きないガイド」になる
            val kind = allowed[name]
            if (name == null || kind == null) {
                dropped++
                continue
            }
            // 記号と箇条書きを落とす。読み上げると「※」も「1.」もそのまま読まれる
            val body = AskGuard.sanitizeAnswer(step.text("body").orEmpty())
            if (body == null) {
                dropped++
                continue
            }
            val intro = AskGuard.sanitizeAnswer(step.text("intro").orEmpty()).orEmpty()
            steps += GuideStep(
                targetName = name,
                kind = kind,
                intro = intro,
                body = body,
            )
        }
        if (steps.isEmpty()) return if (reply.isEmpty()) null else GuideChatReply(reply, null, dropped)
        return GuideChatReply(reply, steps, dropped)
    }

    private fun request(
        instruction: String,
        candidates: List<GuidanceTarget>,
        current: List<GuideStep>,
        history: List<GuideChatTurn>,
    ): String? {
        val startedAt = System.nanoTime()
        val connection = OpenAiHttp.openPost(endpoint, apiKey)
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
            .put(JSONObject().put("role", "user").put("content", situation(candidates, current)))
        // **古い往復から順に積む。** 上限は呼ぶ側（GuideAsk.MAX_TURNS）が持つ
        for (turn in history.takeLast(GuideAsk.MAX_TURNS * 2)) {
            messages.put(
                JSONObject()
                    .put("role", if (turn.fromUser) "user" else "assistant")
                    .put("content", turn.text),
            )
        }
        messages.put(JSONObject().put("role", "user").put("content", instruction))
        val payload = JSONObject()
            .put("model", model)
            .put("max_completion_tokens", MAX_TOKENS)
            .put("response_format", JSONObject().put("type", "json_object"))
            .put("messages", messages)
        var bytes = 0L
        try {
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            if (status !in 200..299) {
                val detail = connection.errorStream?.readBytes()?.toString(Charsets.UTF_8).orEmpty()
                throw OpenAiStatusException(status, requestId(connection), openAiErrorMessage(status, detail))
            }
            val text = connection.inputStream.use { it.readBytes() }.also { bytes = it.size.toLong() }
                .toString(Charsets.UTF_8)
            val choice = JSONObject(text).optJSONArray("choices")?.optJSONObject(0)
                ?: throw IOException("返事が空だった")
            // **optString を JSON の null に使わない**（Android は "null" を返す。AGENTS.md）
            val message = choice.optJSONObject("message")
            val content = message?.let { if (it.isNull("content")) "" else it.optString("content") }
                .orEmpty().trim()
            if (content.isEmpty()) throw IOException("返事が空だった")
            return content
        } finally {
            onTrace(
                OpenAiRequestTrace(
                    operation = "ガイドの相談",
                    attempt = 1,
                    requestId = requestId(connection),
                    firstByteMs = null,
                    totalMs = (System.nanoTime() - startedAt) / 1_000_000,
                    bytes = bytes,
                    completed = bytes > 0,
                ),
            )
            connection.disconnect()
        }
    }

    private fun situation(candidates: List<GuidanceTarget>, current: List<GuideStep>): String = buildString {
        append("その日その時間に空へ出ている対象は、次のとおりです。\n")
        for (target in candidates) {
            append("・").append(target.nameJa)
            append("（高さ").append(target.aim.altDeg.toInt()).append(" 度）\n")
        }
        append("\nいまの台本は")
        if (current.isEmpty()) {
            append("まだ空です。\n")
        } else {
            append("次のとおりです。\n")
            for ((index, step) in current.withIndex()) {
                append(index + 1).append(". ").append(step.targetName).append("\n")
            }
        }
    }

    private fun JSONObject.text(key: String): String? =
        if (isNull(key)) null else optString(key).trim().takeIf { it.isNotBlank() }

    private fun requestId(connection: java.net.HttpURLConnection): String? =
        runCatching { connection.getHeaderField("x-request-id") }.getOrNull()

    companion object {
        /** 詳細ガイドは段が多いので、即興ガイド（2,000）より広く取る */
        private const val MAX_TOKENS = 4_000

        private const val SYSTEM_PROMPT =
            "あなたはプラネタリウムの解説員で、星空ツアーの台本づくりを手伝います。\n" +
                "・JSON だけを返す。形は {\"reply\": \"相手への一言\", \"steps\": " +
                "[{\"name\": \"対象名\", \"intro\": \"向く前の一言\", \"body\": \"解説\"}]}\n" +
                "・**渡された候補にない対象を steps に入れない。** 入れても端末が捨てます\n" +
                "・台本を変える必要がないときは steps を省き、reply だけ返す\n" +
                "・steps を返すときは**台本の全体**を返す。差分ではありません\n" +
                "・**方角と高さは端末が読み上げる。** intro にも body にも書かない\n" +
                "・name は渡された対象名をそのまま写す\n" +
                "・読み上げる文章なので、箇条書き・記号・括弧・見出しを使わず、地の文だけで書く\n" +
                "・その場で口に出す話し言葉で書く。むずかしい言葉は使わない\n" +
                "・「高度 45 度」「極大」「等級」のような天文の言葉は使わず、" +
                "初心者がそのまま聞いて分かる言い方にする\n" +
                "・分からないことは書かない。作り話をしない\n" +
                "・reply は 2 文までの短い日本語で"
    }
}
