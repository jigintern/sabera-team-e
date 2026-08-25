package jp.jig.glasses.sample.kmp.guide

import jp.jig.glasses.sample.kmp.sky.Site

/**
 * 編集中の台本（toB）。
 *
 * [StarGuide] と分けてあるのは、**編集中にしか要らないもの**を保存する形へ混ぜないため。
 * 想定した空（[window] と [site]）は台本にも残るが、ここでは**候補を出すために毎回使う**。
 *
 * **Android に触らない。** 並べ替えも上限の判定もここで完結するので、
 * 画面のテストを書かなくても振る舞いを JVM で固定できる。
 *
 * 名前が [GuideDraft]（即興ガイドを作った結果）と紛らわしくならないよう、
 * **出どころの [GuideOrigin.AUTHORED] に合わせて**いる。
 */
data class AuthoredGuide(
    val id: String,
    val title: String,
    val summary: String,
    val createdAtMillis: Long,
    val window: GuideWindow,
    val site: Site,
    val steps: List<GuideStep>,
    val origin: GuideOrigin = GuideOrigin.AUTHORED,
    val derivedFrom: GuideSource? = null,
) {

    /** 保存する形へ。**配るときだけ [locked] を立てる** */
    fun toGuide(locked: Boolean = false): StarGuide = StarGuide(
        id = id,
        title = title.ifBlank { UNTITLED },
        summary = summary,
        createdAtMillis = createdAtMillis,
        origin = origin,
        steps = steps,
        plannedAtMillis = window.startMillis,
        plannedMinutes = window.minutes,
        plannedLatDeg = site.latDeg,
        plannedLonDeg = site.lonDeg,
        locked = locked,
        derivedFrom = derivedFrom,
    )

    /** 段を掴んで動かす。**範囲外は黙って何もしない**（ドラッグは端で行き過ぎる） */
    fun moved(from: Int, to: Int): AuthoredGuide {
        if (from !in steps.indices || to !in steps.indices || from == to) return this
        val next = steps.toMutableList()
        next.add(to, next.removeAt(from))
        return copy(steps = next)
    }

    fun plus(step: GuideStep): AuthoredGuide = copy(steps = steps + step)

    fun removedAt(index: Int): AuthoredGuide =
        if (index in steps.indices) copy(steps = steps.filterIndexed { i, _ -> i != index }) else this

    fun replacedAt(index: Int, step: GuideStep): AuthoredGuide =
        if (index in steps.indices) copy(steps = steps.mapIndexed { i, old -> if (i == index) step else old }) else this

    /** 配る直前の ON/OFF。**段は消さない**ので、翌週そのまま戻せる */
    fun toggledAt(index: Int): AuthoredGuide =
        if (index in steps.indices) replacedAt(index, steps[index].let { it.copy(enabled = !it.enabled) }) else this

    /** すでに入っている対象。候補の一覧から重複を外すのに使う */
    fun contains(name: String): Boolean = steps.any { it.targetName == name }

    /** グラスに入らない長さの段。**黙って切らず、ここで名指しする** */
    val tooLongSteps: List<Int>
        get() = steps.indices.filter { steps[it].body.length > GuideCodec.MAX_BODY_CHARS }

    /** 配れる状態か。段が 1 つも無い／長すぎる段がある／QR に入らない、のいずれでもない */
    fun shareable(): Boolean =
        steps.any { it.enabled } && tooLongSteps.isEmpty() && GuideCodec.fitsInQr(toGuide())

    companion object {
        const val UNTITLED = "名前のない台本"

        /** まっさらから */
        fun empty(id: String, createdAtMillis: Long, window: GuideWindow, site: Site) = AuthoredGuide(
            id = id,
            title = "",
            summary = "",
            createdAtMillis = createdAtMillis,
            window = window,
            site = site,
            steps = emptyList(),
        )

        /**
         * 既にある台本を開く。
         *
         * **受け取った台本や即興ガイドに手を入れたら、出どころは「手で書いた」に変わる。**
         * 「受け取った」のまま中身だけ変わると嘘になり、消してしまうと
         * 何を元にしたか辿れなくなる（[GuideOrigin] を置いた理由）。元は [derivedFrom] に残す。
         *
         * 想定した空を持たない台本（toC の即興ガイド）は、[fallbackWindow] と [fallbackSite] で開く。
         */
        fun edit(
            guide: StarGuide,
            fallbackWindow: GuideWindow,
            fallbackSite: Site,
        ): AuthoredGuide = AuthoredGuide(
            id = guide.id,
            title = guide.title,
            summary = guide.summary,
            createdAtMillis = guide.createdAtMillis,
            window = GuideWindow(
                startMillis = guide.plannedAtMillis ?: fallbackWindow.startMillis,
                minutes = guide.plannedMinutes ?: fallbackWindow.minutes,
            ),
            site = Site(
                latDeg = guide.plannedLatDeg ?: fallbackSite.latDeg,
                lonDeg = guide.plannedLonDeg ?: fallbackSite.lonDeg,
            ),
            steps = guide.steps,
            origin = GuideOrigin.AUTHORED,
            derivedFrom = when (guide.origin) {
                // すでに手で書いたものなら、元の記録はそのまま持ち越す
                GuideOrigin.AUTHORED -> guide.derivedFrom
                else -> GuideSource(guide.id, guide.title)
            },
        )
    }
}
