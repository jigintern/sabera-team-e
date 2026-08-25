package jp.jig.glasses.sample.kmp.guide

import jp.jig.glasses.sample.kmp.sky.GuidanceTarget
import jp.jig.glasses.sample.kmp.sky.GuidanceTargetKind

/**
 * ツアーの**幅**。旅行会社は「9/12 20:00 から 40 分」で組む。
 *
 * **一点ではなく幅で見る**のが要点。20:00 に地平線の下でも 20:40 には昇っている星座があり、
 * 一点で判定すると**東から昇るものを全部落とす**（秋のツアーで冬の星座を最後に見せる、
 * という一番よくある構成ができなくなる）。
 */
data class GuideWindow(val startMillis: Long, val minutes: Int) {
    val endMillis: Long get() = startMillis + minutes * MINUTE_MS

    companion object {
        const val MINUTE_MS = 60_000L
    }
}

/** その段が「いつ来て」「そのとき見えるか」。見えないなら [availableFromMillis] にずらせる時刻 */
data class SlotCheck(
    val index: Int,
    val atMillis: Long,
    val target: GuidanceTarget?,
    val availableFromMillis: Long?,
) {
    val visible: Boolean get() = target != null
}

/**
 * 段ごとの推定時刻と、その時刻の空で見えるかの判定。
 *
 * 段は順に流れるので、8 段目が来るのは開始から 30 分後。**その時刻で判定する**と、
 * 「沈む前に見ておく」順番が正しいかを作るときに検算できる。
 *
 * **Android に触らない。** 空は `targetsAt: (Long) -> List<GuidanceTarget>` で外から渡す
 * （[GuidePlan] が `targets` を呼び手から受け取るのと同じ形）。呼ぶ側は
 * `StarMapRenderer.guidanceTargets(site, epochMillis)` を包んで渡す。
 */
object GuideSchedule {

    /** 所要時間の選択肢（分）。**自由入力にしない**（刻んでも判定の精度は上がらない） */
    val MINUTE_CHOICES = listOf(30, 45, 60, 90)

    const val DEFAULT_MINUTES = 45

    /** 幅の中を何分刻みで見るか。空は 1 時間に 15 度動くので、5 分で 1.25 度 */
    private const val SAMPLE_MINUTES = 5

    /** 代案を探す先。日が変わるまで見ても意味が薄いので、ツアーの終わりから 6 時間 */
    private const val ALTERNATIVE_HORIZON_MINUTES = 6 * 60

    /** 代案を探す刻み。分単位で答えても人は動けないので 10 分 */
    private const val ALTERNATIVE_STEP_MINUTES = 10

    /**
     * N 段目が来るころ。
     *
     * 最後の段が終了時刻ちょうどに来るのではなく、**段を均等に割った頭**で見る
     * （1 段目は開始時刻そのもの）。
     */
    fun slotMillis(window: GuideWindow, index: Int, count: Int): Long {
        if (count <= 1) return window.startMillis
        val span = window.minutes * GuideWindow.MINUTE_MS
        return window.startMillis + span * index.coerceIn(0, count - 1) / count
    }

    /**
     * 台本の各段を、**その段が来るころの空**で見る。
     *
     * 見えない段には「いつからなら入れられるか」を添える。**断るだけだと行き止まり**になり、
     * そこで人が諦めてしまう（時刻をずらせば入るのは端末が計算できる事実）。
     */
    fun check(
        steps: List<GuideStep>,
        window: GuideWindow,
        targetsAt: (Long) -> List<GuidanceTarget>,
        minAltDeg: Double = GuidePlan.MIN_ALTITUDE_DEG,
    ): List<SlotCheck> = steps.mapIndexed { index, step ->
        val at = slotMillis(window, index, steps.size)
        val found = find(targetsAt(at), step.targetName, step.kind, minAltDeg)
        SlotCheck(
            index = index,
            atMillis = at,
            target = found,
            availableFromMillis = if (found != null) {
                null
            } else {
                availableFrom(step.targetName, step.kind, window, targetsAt, minAltDeg)
            },
        )
    }

    /**
     * ツアーの幅のどこかで見える対象を、**最初に届いた時点の見えかたで**返す。
     *
     * 候補として出すのはここ。幅のどこかで届けば通し、順番は人と AI が決める。
     */
    fun candidates(
        window: GuideWindow,
        targetsAt: (Long) -> List<GuidanceTarget>,
        minAltDeg: Double = GuidePlan.MIN_ALTITUDE_DEG,
    ): List<GuidanceTarget> {
        val seen = LinkedHashMap<String, GuidanceTarget>()
        for (at in samples(window)) {
            for (target in targetsAt(at)) {
                if (target.aim.altDeg < minAltDeg) continue
                // **最初に届いた時点の見えかたを残す。** 上書きすると、幅の終わりの
                // 方角で「いま」を説明することになる
                val key = key(target.nameJa, target.kind)
                if (!seen.containsKey(key)) seen[key] = target
            }
        }
        return seen.values.toList()
    }

    /**
     * 名前で指定されたものが、その日のその時間に入れられるか。
     *
     * AI との対話で「オリオン座も入れて」と打たれたときに呼ぶ。**入らないなら
     * いつからなら入るか**を返す（null なら、その晩じゅう探しても届かない）。
     */
    fun availableFrom(
        name: String,
        kind: GuidanceTargetKind?,
        window: GuideWindow,
        targetsAt: (Long) -> List<GuidanceTarget>,
        minAltDeg: Double = GuidePlan.MIN_ALTITUDE_DEG,
    ): Long? {
        for (at in samples(window)) {
            if (find(targetsAt(at), name, kind, minAltDeg) != null) return at
        }
        var at = window.endMillis
        val until = window.endMillis + ALTERNATIVE_HORIZON_MINUTES * GuideWindow.MINUTE_MS
        while (at <= until) {
            if (find(targetsAt(at), name, kind, minAltDeg) != null) return at
            at += ALTERNATIVE_STEP_MINUTES * GuideWindow.MINUTE_MS
        }
        return null
    }

    private fun samples(window: GuideWindow): List<Long> {
        val step = SAMPLE_MINUTES * GuideWindow.MINUTE_MS
        val result = ArrayList<Long>()
        var at = window.startMillis
        while (at <= window.endMillis) {
            result += at
            at += step
        }
        // 幅が刻みより短くても、終わりは必ず見る
        if (result.lastOrNull() != window.endMillis) result += window.endMillis
        return result
    }

    /** 種別が合うものを先に見て、無ければ名前だけで拾う（[GuidePlan.resolveSteps] と同じ順） */
    private fun find(
        targets: List<GuidanceTarget>,
        name: String,
        kind: GuidanceTargetKind?,
        minAltDeg: Double,
    ): GuidanceTarget? {
        val matched = targets.firstOrNull { it.kind == kind && it.nameJa == name }
            ?: targets.firstOrNull { it.nameJa == name }
        return matched?.takeIf { it.aim.altDeg >= minAltDeg }
    }

    private fun key(name: String, kind: GuidanceTargetKind) = "${kind.name} $name"
}
