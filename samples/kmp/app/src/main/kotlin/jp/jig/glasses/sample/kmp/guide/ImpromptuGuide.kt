package jp.jig.glasses.sample.kmp.guide

import jp.jig.glasses.sample.kmp.sky.GuidanceTarget
import jp.jig.glasses.sample.kmp.sky.GuidanceTargetKind
import jp.jig.glasses.sample.kmp.sky.cardinalDirection16

/**
 * 即興ガイドのテーマ。**選び方だけを決める**（文面は [ImpromptuGuide] が組む）。
 *
 * どれも「いま空に出ていて、星図に線と名前が出る星座」から選ぶ。
 * **空に無いものを台本に載せない**ので、再生で全部飛ぶことがない。
 */
enum class GuideTheme(val label: String, val hint: String) {
    TONIGHT("今夜のおすすめ", "高く上がっていて、いちばん見つけやすいものから"),
    BRIGHT("明るくて探しやすい", "町の明かりの下でも形が読めるものだけ"),
    MYTH("神話をたどる", "物語の伝わっている星座をつないで回ります"),
    SETTING("沈む前に見ておく", "西へ傾いているものから。待つと見られなくなります"),
}

/**
 * その場で台本を組む（toC）。
 *
 * **候補を選ぶのは端末。** AI に選ばせない。空に出ていない星座を並べられると、
 * 再生時に全部飛んで「何も起きないガイド」になる。AI に任せるのは**文面だけ**
 * （`openai/OpenAiGuide.kt`）で、ここが**その退路**でもある。
 *
 * **通信ゼロで必ず 1 本できる。** `intro` は端末が組む定型、`body` は同梱の 88 星座
 * （`data/constellation-lore.json`）をそのまま使う。圏外で作っても中身は変わらない。
 *
 * **Android に触らない**ので、テーマごとの選定と文面を JVM テストで固定できる。
 */
object ImpromptuGuide {

    /** 台本の長さ。短すぎると物足りず、長いと首が疲れる */
    const val MAX_STEPS = 5

    /** 「明るくて探しやすい」で残す明るさ。`SkyDensity.TOWN` の星座下限に合わせる */
    const val BRIGHT_LIMIT_MAGNITUDE = 2.0

    /**
     * 候補を選ぶ。**星座だけ**（恒星や大三角を混ぜると「次はベガです」と点を探させることになる）。
     *
     * **選定の規則はここ 1 か所に置く。** 2 か所に分けると、テーマを足すたびに両方直すことになる。
     *
     * [brightestMagnitude] はその星座でいちばん明るい星の等級。分からなければ null でよく、
     * そのときは明るさで絞らない（**候補が消えて台本が作れなくなるより、少し暗くても載せる**）。
     */
    fun candidates(
        targets: List<GuidanceTarget>,
        theme: GuideTheme,
        lore: (String) -> String? = { null },
        brightestMagnitude: (String) -> Double? = { null },
        minAltDeg: Double = GuidePlan.MIN_ALTITUDE_DEG,
        max: Int = MAX_STEPS,
    ): List<GuidanceTarget> {
        // 解説文を持っていない星座は、案内できても喋ることが無い
        val visible = targets
            .filter { it.kind == GuidanceTargetKind.CONSTELLATION }
            .filter { it.aim.altDeg >= minAltDeg }
            .distinctBy { it.nameJa }
        val highFirst = visible.sortedByDescending { it.aim.altDeg }
        val themed = when (theme) {
            // 高いものほど建物と木に邪魔されない
            GuideTheme.TONIGHT -> highFirst

            GuideTheme.BRIGHT -> highFirst
                .filter { (brightestMagnitude(it.nameJa) ?: -99.0) <= BRIGHT_LIMIT_MAGNITUDE }

            GuideTheme.MYTH -> highFirst.filter { hasMyth(lore(it.nameJa)) }

            // 西へ傾いているものが先。**待つと見られなくなる順**
            GuideTheme.SETTING -> visible
                .filter { it.aim.azDeg in WEST_FROM..WEST_TO }
                .sortedBy { it.aim.altDeg }
        }
        // **テーマで 1 つも残らないなら、テーマを諦めて空の見やすいほうを返す。**
        // 「今夜は作れません」と断るより、少しテーマから外れても回れるほうがよい
        return spread(themed.ifEmpty { highFirst }, max)
    }

    /**
     * 台本にする。**同梱の本文が無い星座は落とす。**
     *
     * 落として短くなってもそのまま作る（**作れませんでしたで終わらせない**）。
     * 1 段も残らなければ null。
     */
    fun compose(
        theme: GuideTheme,
        targets: List<GuidanceTarget>,
        lore: (String) -> String?,
        createdAtMillis: Long,
        id: String,
    ): StarGuide? {
        val steps = targets.mapNotNull { target ->
            val body = lore(target.nameJa) ?: return@mapNotNull null
            GuideStep(
                targetName = target.nameJa,
                kind = GuidanceTargetKind.CONSTELLATION,
                // **方角は台本に書かない。** 再生する日と場所で変わるので、
                // 向く前の一言は再生時に [intro] で作り直す（同梱だけの台本に添える言葉は無い）
                intro = "",
                body = body,
            )
        }
        if (steps.isEmpty()) return null
        return StarGuide(
            id = id,
            title = theme.label,
            summary = "${steps.size} つの星座を回ります。${theme.hint}",
            createdAtMillis = createdAtMillis,
            origin = GuideOrigin.IMPROMPTU_BUNDLED,
            steps = steps,
        )
    }

    /**
     * 向く前の一言。**方角と高さだけを言う。**
     *
     * **台本には焼き込まず、再生するその瞬間に作る。** 同じ台本を別の日・別の場所で
     * 再生すると方角はまるで変わるので、書き置いた方角を読み上げると嘘になる。
     *
     * 星座の形をここで説明しない（グラスの星図がそのまま見せている）し、
     * **天文の言葉も使わない**（「高度 45 度」ではなく「高いところ」）。
     */
    fun intro(target: GuidanceTarget): String {
        val where = cardinalDirection16(target.aim.azDeg)
        val height = when {
            target.aim.altDeg >= 70.0 -> "ほとんど真上"
            target.aim.altDeg >= 45.0 -> "高いところ"
            target.aim.altDeg >= 30.0 -> "少し上のほう"
            else -> "低いところ"
        }
        return "次は${where}の${height}、${target.nameJa}です。"
    }

    /**
     * 物語の伝わっている星座か。
     *
     * **「神話」という語の有無では判定できない。** ポンプ座の本文は
     * 「神話は伝わっていません」で、語だけ見ると神話の星座になってしまう。
     */
    private fun hasMyth(text: String?): Boolean {
        if (text == null) return false
        if (NO_MYTH.any { it in text }) return false
        return MYTH_WORDS.any { it in text }
    }

    /**
     * 空じゅうに散らばらせない。**先頭の次からは、いちばん近いものへ送る。**
     *
     * 高さや方角だけで並べると南 → 北 → 南と振り回すことになる。首を大きく回すたびに、
     * 方位補正がいちばん苦手な速い首振りが入る（10Hz の取りこぼしで 90° あたり 5〜15° 足りない）。
     */
    private fun spread(sorted: List<GuidanceTarget>, max: Int): List<GuidanceTarget> {
        if (sorted.isEmpty()) return emptyList()
        val remaining = sorted.toMutableList()
        val picked = ArrayList<GuidanceTarget>(max)
        picked += remaining.removeAt(0)
        while (picked.size < max && remaining.isNotEmpty()) {
            val last = picked.last()
            val nearest = remaining.minByOrNull { separationDeg(last, it) } ?: break
            remaining.remove(nearest)
            picked += nearest
        }
        return picked
    }

    /** 方位の回り込みを数えた大まかな隔たり。順番を決めるだけなので厳密でなくてよい */
    private fun separationDeg(a: GuidanceTarget, b: GuidanceTarget): Double {
        var dAz = kotlin.math.abs(a.aim.azDeg - b.aim.azDeg) % 360.0
        if (dAz > 180.0) dAz = 360.0 - dAz
        return dAz + kotlin.math.abs(a.aim.altDeg - b.aim.altDeg)
    }

    private val NO_MYTH = listOf("神話は伝わっていません", "神話は伝わっていない")
    private val MYTH_WORDS =
        listOf("神話", "ギリシャ", "ゼウス", "女神", "英雄", "王女", "怪物", "物語", "伝説", "姫")

    /** 西寄りと見なす方位の範囲（南西から北西まで） */
    private const val WEST_FROM = 202.5
    private const val WEST_TO = 337.5
}
