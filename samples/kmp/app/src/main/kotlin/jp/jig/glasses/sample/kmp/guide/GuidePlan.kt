package jp.jig.glasses.sample.kmp.guide

import jp.jig.glasses.sample.kmp.sky.GuidanceTarget

/**
 * 再生できる形になった台本 1 段。
 *
 * [target] が null なら、いまの空では案内できない。**飛ばす理由を持たせておく**ので、
 * 再生側は黙って飛ばさずに一言添えられる（黙って消えると、台本が壊れたように見える）。
 */
data class ResolvedStep(
    val step: GuideStep,
    val target: GuidanceTarget?,
    val skipReason: String?,
) {
    val playable: Boolean get() = target != null
}

/**
 * 台本を**いまの空**に突き合わせる。
 *
 * 台本は星座名しか持っていない（[GuideStep]）。同じ台本を別の日・別の場所で再生できる
 * ようにするためで、**どちらに何度で見えるかは、再生するその瞬間に引き直す**。
 *
 * 候補には `StarMapRenderer.guidanceTargets()` の結果を渡す。**星図に線と名前が出る
 * 星座しか候補に入らない**ので、「台本には載っているのにグラスには何も出ない」が起きない
 * （絵と根拠を別々に計算しない・#37）。
 *
 * **Android に触らないので JVM テストで固定できる**（季節違い・緯度違いの台本）。
 */
object GuidePlan {

    /** これより低いものは案内しない。建物と木で見えないうえ、探させると首が痛くなる */
    const val MIN_ALTITUDE_DEG = 20.0

    fun resolveSteps(
        guide: StarGuide,
        targets: List<GuidanceTarget>,
        minAltDeg: Double = MIN_ALTITUDE_DEG,
    ): List<ResolvedStep> = guide.steps.map { step ->
        val found = targets.firstOrNull { it.kind == step.kind && it.nameJa == step.targetName }
            ?: targets.firstOrNull { it.nameJa == step.targetName }
        when {
            found == null -> ResolvedStep(step, null, "${step.targetName}は、いまの空には出ていません。")
            found.aim.altDeg < minAltDeg ->
                ResolvedStep(step, null, "${step.targetName}は、いま低すぎて見つけにくいので飛ばします。")
            else -> ResolvedStep(step, found, null)
        }
    }

    /**
     * 再生できる段だけを残す。**1 段も残らなければ空。**
     *
     * 呼ぶ側は空のときに「いま出ている星座がありません」を喋って畳む。
     * **無言で終わらせない**（タップして無反応が一番よくない・31_gestures.md）。
     */
    fun playable(resolved: List<ResolvedStep>): List<ResolvedStep> = resolved.filter { it.playable }
}
