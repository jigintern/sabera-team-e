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
 * （JVM テストで往復を固定できる）。ファイルへの出し入れは [GuideStore]、
 * QR とファイルの受け渡しは [GuideCodec]。
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
    /**
     * 想定したツアーの開始時刻。**再生には使わない。**
     *
     * 旅行会社は「9/12 20:00、乗鞍高原、40 分」でツアーを組むので、事務所にいながら
     * その夜の空で星座を選べないと作り込めない。**使うのは作るときの検算だけ**で、
     * 再生はいまの空で引き直す（[GuidePlan]）。書き置いた方角が嘘になる問題は起きない。
     */
    val plannedAtMillis: Long? = null,
    /** 想定した所要時間（分）。段ごとの推定時刻を出すのに要る（[GuideSchedule]） */
    val plannedMinutes: Int? = null,
    /** 想定した観測地。**測位のままだと事務所の空で組んでしまう** */
    val plannedLatDeg: Double? = null,
    val plannedLonDeg: Double? = null,
    /**
     * 受け取った側で編集させないか。
     *
     * **これは鍵ではない。** QR の中身は誰でも作れるので、書き換えれば編集できてしまう。
     * 守りたいのは「客がうっかり直してしまう」ことであって、改ざんではない。
     */
    val locked: Boolean = false,
    /** 元にした台本。**手を入れて [origin] が変わったときだけ入る**（辿れなくしない） */
    val derivedFrom: GuideSource? = null,
) {
    val size: Int get() = steps.size

    /** 配るぶん。**外した段は QR に入れない**（読めない段のために枠を食わない） */
    val enabledSteps: List<GuideStep> get() = steps.filter { it.enabled }
}

/** 元にした台本の見出しと id。**「なぜこの文なのか」を辿るためだけに持つ** */
data class GuideSource(val id: String, val title: String)

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
    /**
     * 配る直前の ON/OFF。
     *
     * 「曇っていたらこの段は飛ばす」を台本の分岐で持たせず、**配るときに外して版を決める**。
     * 外した段は原本に残るので、翌週そのまま戻せる。
     * **効くのは原本の JSON だけ**で、配る QR には最初から入らない（[GuideCodec]）。
     */
    val enabled: Boolean = true,
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

    /** 人が書いた台本（toB） */
    AUTHORED("手で書いた"),

    /** QR かファイルで受け取った台本 */
    RECEIVED("受け取った"),
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
        .apply {
            // **無いものは書かない。** 即興ガイド（toC）の台本を toB のキーで太らせない
            guide.plannedAtMillis?.let { put("plannedAtMillis", it) }
            guide.plannedMinutes?.let { put("plannedMinutes", it) }
            guide.plannedLatDeg?.let { put("plannedLatDeg", it) }
            guide.plannedLonDeg?.let { put("plannedLonDeg", it) }
            if (guide.locked) put("locked", true)
            guide.derivedFrom?.let {
                put("derivedFrom", JSONObject().put("id", it.id).put("title", it.title))
            }
        }
        .put(
            "steps",
            JSONArray().apply {
                for (step in guide.steps) {
                    put(
                        JSONObject()
                            .put("targetName", step.targetName)
                            .put("kind", step.kind.name)
                            .put("intro", step.intro)
                            .put("body", step.body)
                            // 既定が true なので、外した段だけ書けば読み戻せる
                            .apply { if (!step.enabled) put("enabled", false) },
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
                enabled = step.optBoolean("enabled", true),
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
            plannedAtMillis = json.longOrNull("plannedAtMillis"),
            plannedMinutes = json.intOrNull("plannedMinutes"),
            plannedLatDeg = json.doubleOrNull("plannedLatDeg"),
            plannedLonDeg = json.doubleOrNull("plannedLonDeg"),
            locked = json.optBoolean("locked", false),
            derivedFrom = json.optJSONObject("derivedFrom")?.let {
                val id = it.string("id") ?: return@let null
                GuideSource(id, it.string("title").orEmpty())
            },
        )
    }.getOrNull()

    /**
     * **`optString` を JSON の null に使わない**（AGENTS.md）。
     * Android の org.json は文字列 "null" を返し、テストの本物は空を返す。
     * **この取り違えは JVM テストでは落ちず、実機だけで壊れる。**
     */
    private fun JSONObject.string(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

    // 数値も同じ理由で、**「無い」と「0」を取り違えない**ようにする。
    // 想定日時が 0 の台本と、想定日時を持たない台本は別物（前者は 1970 年になる）
    private fun JSONObject.longOrNull(key: String): Long? = if (has(key) && !isNull(key)) optLong(key) else null
    private fun JSONObject.intOrNull(key: String): Int? = if (has(key) && !isNull(key)) optInt(key) else null
    private fun JSONObject.doubleOrNull(key: String): Double? =
        if (has(key) && !isNull(key)) optDouble(key).takeIf { !it.isNaN() } else null

    /**
     * **上げるが、読む側は見ない。**
     *
     * いま弾く必要は無い（知らないキーを無視する方針で足りている）。ただし書いておかないと、
     * あとで本当に非互換な変更が要ったとき「古い 1」と「新しいのに 1 のまま」を区別できない。
     */
    private const val VERSION = 2
}
