package jp.jig.glasses.sample.kmp.guide

import jp.jig.glasses.sample.kmp.sky.GuidanceTargetKind
import org.json.JSONArray
import org.json.JSONObject

/**
 * 星座ガイドの台本。**見る順番と、そのとき喋る中身**を前もって決めておく。
 *
 * いまのアプリは受け身で、空を向いてタップして初めて「あれは何？」に答える。
 * **何を見ればいいか分からない人には、押すきっかけ自体が無い。**
 * 台本を持たせておけば、アプリの側から順に連れて回れる。
 *
 * **Android に触らない。** 台本の形と読み書きは端末の事情から切り離しておく
 * （JVM テストで往復を固定できる）。ファイルへの出し入れは [GuideStore]。
 */
data class StarGuide(
    val id: String,
    /** 一覧の見出し。「今夜の星座めぐり」 */
    val title: String,
    /** 一覧に添える一行 */
    val summary: String,
    val createdAtMillis: Long,
    val origin: GuideOrigin,
    val steps: List<GuideStep>,
) {
    val size: Int get() = steps.size
}

/**
 * 台本 1 段。**方角と高度は持たない。**
 *
 * 持たせると、その日その場所でしか再生できない台本になる。星座がどちらに見えるかは
 * **再生するその瞬間に**引き直す（[GuidePlan.resolveSteps]）。
 */
data class GuideStep(
    /** 星座名。再生時に `GuidanceTarget.nameJa` と突き合わせる */
    val targetName: String,
    val kind: GuidanceTargetKind,
    /**
     * 向く前に足す導入の一言。**方角と高さは入れない。**
     *
     * どちらを向けばよいかは再生するその瞬間に端末が言う（`ImpromptuGuide.intro`）。
     * 台本に書き置いた方角は、別の日・別の場所で再生すると嘘になる。
     * 同梱だけで組んだ台本では空で、AI や人が書いた台本にだけ入る。
     */
    val intro: String,
    /** 向いたあとに読み上げる本文 */
    val body: String,
)

/**
 * 台本の出どころ。**AI が書いたのか同梱の文なのかを残す。**
 *
 * 圏外で作ったのか電波のあるところで作ったのかで中身が変わるので、
 * あとから「なぜこの文なのか」を辿れないと、実機で見た結果を説明できない。
 */
enum class GuideOrigin(val label: String) {
    /** 通信して AI に文を書かせた */
    IMPROMPTU_AI("AI が書いた"),

    /** 同梱の 88 星座を組んだ。**通信ゼロ** */
    IMPROMPTU_BUNDLED("同梱の解説を組んだ"),

    /** 人が書いた台本（toB）。**いまは読むだけ** */
    AUTHORED("手で書いた"),
    ;

    companion object {
        fun of(name: String?): GuideOrigin =
            entries.firstOrNull { it.name == name } ?: IMPROMPTU_BUNDLED
    }
}

/**
 * 台本の JSON。**知らないキーが増えても落ちない**ように、読む側は必要なものだけ拾う。
 *
 * toB の台本も同じ形で置けば読める。**入出力の口をここに 1 つだけ持つ。**
 */
object StarGuideJson {

    fun encode(guide: StarGuide): String = JSONObject()
        .put("version", VERSION)
        .put("id", guide.id)
        .put("title", guide.title)
        .put("summary", guide.summary)
        .put("createdAtMillis", guide.createdAtMillis)
        .put("origin", guide.origin.name)
        .put(
            "steps",
            JSONArray().apply {
                for (step in guide.steps) {
                    put(
                        JSONObject()
                            .put("targetName", step.targetName)
                            .put("kind", step.kind.name)
                            .put("intro", step.intro)
                            .put("body", step.body),
                    )
                }
            },
        )
        .toString()

    /** 読めなければ null。**壊れた 1 本で一覧ごと開けなくならない**ように、例外は投げない */
    fun decode(text: String): StarGuide? = runCatching {
        val json = JSONObject(text)
        val stepsJson = json.optJSONArray("steps") ?: JSONArray()
        val steps = (0 until stepsJson.length()).mapNotNull { i ->
            val step = stepsJson.optJSONObject(i) ?: return@mapNotNull null
            val name = step.string("targetName") ?: return@mapNotNull null
            val body = step.string("body") ?: return@mapNotNull null
            GuideStep(
                targetName = name,
                kind = GuidanceTargetKind.entries.firstOrNull { it.name == step.string("kind") }
                    ?: GuidanceTargetKind.CONSTELLATION,
                intro = step.string("intro").orEmpty(),
                body = body,
            )
        }
        if (steps.isEmpty()) return null
        StarGuide(
            id = json.string("id") ?: return null,
            title = json.string("title") ?: return null,
            summary = json.string("summary").orEmpty(),
            createdAtMillis = json.optLong("createdAtMillis"),
            origin = GuideOrigin.of(json.string("origin")),
            steps = steps,
        )
    }.getOrNull()

    /**
     * **`optString` を JSON の null に使わない**（AGENTS.md）。
     * Android の org.json は文字列 "null" を返し、テストの本物は空を返す。
     * **この取り違えは JVM テストでは落ちず、実機だけで壊れる。**
     */
    private fun JSONObject.string(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

    private const val VERSION = 1
}
