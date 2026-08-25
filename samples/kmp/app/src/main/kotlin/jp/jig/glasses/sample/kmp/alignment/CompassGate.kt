package jp.jig.glasses.sample.kmp.alignment

/**
 * 磁気精度で方位合わせを止めるかどうか。**8 の字で止めるが、止めっぱなしにはしない。**
 *
 * OS の信頼度が上がらない端末は実在し、そこで止めきると方位合わせから先へ一切進めない。
 * [MagneticQuality] の歪みを「止めずに警告する」にしたのと同じ判断で、
 * **待たせすぎたら赤字を出したうえで通す**。
 *
 * Android に触らないので、待ち時間の詰め方は JVM テストで固められる。
 */
data class CompassGate(
    /** 8 の字を出し始めた時刻。出していないなら 0 */
    val promptingSince: Long = 0L,
    /** 待たせすぎたので警告付きで通したか */
    val bypassed: Boolean = false,
) {

    /** @param accurate OS の信頼度が足りているか */
    fun ready(accurate: Boolean): Boolean = accurate || bypassed

    /**
     * @param prompting 6DoF も方位も来ていて、**磁気精度だけが足りない**状態か。
     *   グラスの返事を待っている間まで数えると、待ち時間が精度と関係なく溶ける
     */
    fun advance(nowMillis: Long, accurate: Boolean, prompting: Boolean): CompassGate = when {
        // 上がったら逃げ道ごと畳む。**赤字も消える**
        accurate -> CompassGate()
        // 数えない間も **bypassed は畳まない。** 一度逃がした人をまた待たせない
        !prompting -> copy(promptingSince = 0L)
        promptingSince == 0L -> copy(promptingSince = nowMillis)
        nowMillis - promptingSince >= COMPASS_PROMPT_LIMIT_MS -> copy(bypassed = true)
        else -> this
    }
}

/**
 * 8 の字を出したまま待つ上限。
 *
 * 磁力計の較正は振れば数秒から十数秒で上がる。短くすると**振れば上がる端末まで
 * 警告付きで通してしまう**ので、振り切れる長さを取る。
 */
const val COMPASS_PROMPT_LIMIT_MS = 15_000L
