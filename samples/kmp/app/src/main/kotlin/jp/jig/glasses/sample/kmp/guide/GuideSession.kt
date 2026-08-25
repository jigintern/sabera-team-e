package jp.jig.glasses.sample.kmp.guide

/** ガイドがいま何をしているか。**スマホの画面とグラスの見出しに出すためだけに持つ** */
enum class GuidePhase(val label: String) {
    /** 次の星座を告げている */
    INTRO("次の星座を案内中"),

    /** 矢印を出して、向いてもらうのを待っている */
    GUIDING("星座を探しています"),

    /** 向いたので解説している */
    NARRATING("解説しています"),
}

/**
 * ガイドの進み具合。
 *
 * **グラスに進捗の枠を増やさない。** テキスト枠は 8 つしかなく、星座名と月惑星で埋まる。
 * 数字は解説画面の見出し（もともとある 1 行）に混ぜ、詳しい状態はスマホに出す。
 */
data class GuideProgress(
    val title: String,
    /** 0 始まり */
    val stepIndex: Int,
    val stepCount: Int,
    val stepName: String,
    val phase: GuidePhase,
) {
    /** 「3/5」。解説画面の見出しに足す */
    val counter: String get() = "${stepIndex + 1}/$stepCount"
}
