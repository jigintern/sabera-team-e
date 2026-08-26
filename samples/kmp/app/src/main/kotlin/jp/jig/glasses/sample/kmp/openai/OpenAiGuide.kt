package jp.jig.glasses.sample.kmp.openai

import jp.jig.glasses.sample.kmp.guide.GuideOrigin
import jp.jig.glasses.sample.kmp.guide.GuideStep
import jp.jig.glasses.sample.kmp.guide.GuideTheme
import jp.jig.glasses.sample.kmp.guide.StarGuide
import jp.jig.glasses.sample.kmp.sky.GuidanceTarget
import jp.jig.glasses.sample.kmp.sky.GuidanceTargetKind
import jp.jig.glasses.sample.kmp.sky.cardinalDirection16
import jp.jig.glasses.sample.kmp.support.NANOS_PER_MILLI
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * ガイドの文面を AI に書かせる（即興ガイド・toC）。
 *
 * **ここは「解説文を AI に生成させない」の唯一の例外**で、成り立つのは次の条件がすべて
 * そろっているときだけ（docs/team-e/16_guide.md）。
 *
 * - **通信するのは台本を作るときだけ。** 出来た文は台本に焼き込むので、
 *   **再生時は圏外でも最後まで喋る**（禁止の理由は「いちばん要るときに黙る」ことだった）
 * - **失敗・圏外なら同梱の 88 星座へ落ちる**（`ImpromptuGuide.compose`）。断って終わらせない
 * - **星座を選ぶのは端末。** ここへ渡すのは端末が確定した並びだけで、
 *   AI は文を書くだけ。空に出ていない星座を台本に載せさせない（#37 と同じ考え方）
 * - 返ってきた文も端末が検査する（[AskGuard.sanitizeAnswer] が記号を落とし、
 *   グラスの解説画面に入る長さまで文の切れ目で切る）
 */
class OpenAiGuide(
    private val apiKey: String,
    private val model: String,
    private val endpoint: String = OpenAiAsk.CHAT_COMPLETIONS,
    private val onTrace: (OpenAiRequestTrace) -> Unit = {},
) {
    val configured: Boolean get() = apiKey.isNotEmpty()

    /**
     * テーマに沿った台本を書かせる。**書けなければ null**（呼ぶ側が同梱へ落ちる）。
     *
     * 星座が 1 つでも欠けたり、検査に落ちて本文が消えたりしたら**台本ごと捨てる**。
     * 半分だけ AI の文で残りが同梱、という台本は、聞いていて口調が途中で変わる
     * （**1 回の解説の中で声を入れ替えない**のと同じ理由・06_narration.md）。
     */
    fun write(
        theme: GuideTheme,
        targets: List<GuidanceTarget>,
        createdAtMillis: Long,
        id: String,
    ): StarGuide? {
        require(configured) { "API キーが設定されていない" }
        if (targets.isEmpty()) return null
        val written = request(theme, targets) ?: return null
        val steps = targets.map { target ->
            val part = written[target.nameJa] ?: return null
            GuideStep(
                targetName = target.nameJa,
                kind = GuidanceTargetKind.CONSTELLATION,
                intro = part.first,
                body = part.second,
            )
        }
        return StarGuide(
            id = id,
            title = theme.label,
            summary = "${steps.size} つの星座を回ります。${theme.hint}",
            createdAtMillis = createdAtMillis,
            origin = GuideOrigin.IMPROMPTU_AI,
            steps = steps,
        )
    }

    /** 星座名 → （向く前の一言・本文）。検査に落ちたものは入らない */
    private fun request(theme: GuideTheme, targets: List<GuidanceTarget>): Map<String, Pair<String, String>>? {
        val startedAt = System.nanoTime()
        val connection = OpenAiHttp.openPost(endpoint, apiKey)
        val payload = JSONObject()
            .put("model", model)
            .put("max_completion_tokens", MAX_TOKENS)
            .put("response_format", JSONObject().put("type", "json_object"))
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
                    .put(JSONObject().put("role", "user").put("content", userText(theme, targets))),
            )
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
                ?: throw IOException("台本が空で返ってきた")
            val message = choice.optJSONObject("message")
            // **optString を JSON の null に使わない**（Android は "null" を返す。AGENTS.md）
            val content = message?.let { if (it.isNull("content")) "" else it.optString("content") }
                .orEmpty().trim()
            if (content.isEmpty()) throw IOException("台本が空で返ってきた")
            return parse(content)
        } finally {
            onTrace(
                OpenAiRequestTrace(
                    operation = "ガイドの下書き",
                    attempt = 1,
                    requestId = requestId(connection),
                    firstByteMs = null,
                    totalMs = (System.nanoTime() - startedAt) / NANOS_PER_MILLI,
                    bytes = bytes,
                    completed = bytes > 0,
                ),
            )
            connection.disconnect()
        }
    }

    /** 返ってきた JSON を検査して詰め替える。**1 つでも本文が消えたら台本ごと捨てる** */
    internal fun parse(content: String): Map<String, Pair<String, String>>? {
        val steps = runCatching { JSONObject(content).optJSONArray("steps") }.getOrNull() ?: return null
        val result = HashMap<String, Pair<String, String>>(steps.length())
        for (i in 0 until steps.length()) {
            val step = steps.optJSONObject(i) ?: return null
            val name = step.text("name") ?: return null
            // **記号と箇条書きを落とす。** 読み上げると「※」も「1.」もそのまま読まれる
            val intro = AskGuard.sanitizeAnswer(step.text("intro").orEmpty()) ?: return null
            // [AskGuard.MAX_ANSWER_CHARS] は 200 文字で、グラスの解説画面に入る
            // [GlassTextPage.pagedChars]（272 文字）より短い。**長すぎる本文はここで
            // 文の切れ目まで切られる**ので、めくり切れずに尻切れになることはない
            val body = AskGuard.sanitizeAnswer(step.text("body").orEmpty()) ?: return null
            result[name] = intro to body
        }
        return result.takeIf { it.isNotEmpty() }
    }

    private fun JSONObject.text(key: String): String? =
        if (isNull(key)) null else optString(key).trim().takeIf { it.isNotBlank() }

    private fun requestId(connection: java.net.HttpURLConnection): String? =
        runCatching { connection.getHeaderField("x-request-id") }.getOrNull()

    private fun userText(theme: GuideTheme, targets: List<GuidanceTarget>): String = buildString {
        append("テーマは「").append(theme.label).append("」（").append(theme.hint).append("）。\n")
        append("回る順番と、いまの見えかたは次のとおりです。**この順番と星座を変えないでください。**\n")
        for ((index, target) in targets.withIndex()) {
            append(index + 1).append(". ").append(target.nameJa)
            append("（").append(cardinalDirection16(target.aim.azDeg))
            append("の空、高さ").append(target.aim.altDeg.toInt()).append(" 度）\n")
        }
        // **末尾がいちばん効く**（AGENTS.md）。守らせたい注意はここに置く
        append("上の星座それぞれについて、intro と body を書いてください。\n")
        append("**intro に方角や高さを書かないでください。** そこは端末が読み上げます。")
        append("intro は「ここからが今夜の主役です。」のような、次の星座へ入る一言だけを 1 文で。\n")
        append("body は神話と豆知識だけを 2 文から 3 文、100 文字程度で。")
        append("どんな形でどこに見えるかは、グラスの星図がそのまま見せているので言いません。\n")
        append("読み上げる文章なので、記号・括弧・箇条書き・見出しを使わず地の文だけで書いてください。\n")
        append("「高度 45 度」「極大」「等級」のような天文の言葉は使わず、")
        append("初心者がそのまま聞いて分かる言い方にしてください。")
    }

    companion object {
        /** 5 段ぶんの本文を書かせるので、質問の回答より広く取る */
        private const val MAX_TOKENS = 2_000

        private const val SYSTEM_PROMPT =
            "あなたはプラネタリウムの解説員です。スマートグラスをかけた人を、" +
                "決められた順番で星座へ案内する台本を書きます。\n" +
                "・JSON だけを返す。形は {\"steps\": [{\"name\": \"星座名\", " +
                "\"intro\": \"向く前の一言\", \"body\": \"解説\"}]}\n" +
                "・**渡された星座と順番を変えない。** 足しても減らしてもいけません\n" +
                "・**方角と高さは端末が読み上げる。** intro にも body にも書かない\n" +
                "・name は渡された星座名をそのまま写す\n" +
                "・読み上げる文章なので、箇条書き・記号・括弧・見出しを使わず、地の文だけで書く\n" +
                "・その場で口に出す話し言葉で書く。むずかしい言葉は使わない\n" +
                "・分からないことは書かない。作り話をしない"
    }
}
