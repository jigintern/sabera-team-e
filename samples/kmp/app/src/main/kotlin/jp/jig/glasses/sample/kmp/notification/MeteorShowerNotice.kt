package jp.jig.glasses.sample.kmp.notification

import jp.jig.glasses.sample.kmp.catalog.MeteorShowers

/**
 * 流星群の予告に出す文（#70）。
 *
 * **Android に触らない**ので、日付と月の明るさを固定して JVM テストできる
 * （`TonightSky` ↔ `SkyTips` と同じ切り方）。
 *
 * **[SkyTips][jp.jig.glasses.sample.kmp.narration.SkyTips] の長文は流用しない。**
 * あちらは 130 字あり、畳んだ通知に見えるのはタイトル 1 行と本文 1 行だけ。
 * 言い方（専門用語を使わない）だけを揃える。
 */
object MeteorShowerNotice {

    /**
     * 月がこれ以上照らされていたら一言添える。
     *
     * **満月に近いと、ZHR 100 の群でも実際に見えるのは 2〜3 割**まで落ちる。
     * ただし**通知そのものは止めない** — 理由も言わずに黙る予報は、壊れているのと区別できない。
     */
    const val BRIGHT_MOON = 0.7

    class Text(val title: String, val body: String)

    /**
     * [notice] の夜に出す文。
     *
     * [moonIlluminated] は **0..1 で「知らせる夜」の月の明るさ**（前日の予告なら翌晩の値）。
     * 月の高度は見ない。18 時に出す通知の時点では、深夜に月が出ているかを言い切れない。
     */
    fun of(notice: MeteorShowers.Notice, moonIlluminated: Double): Text {
        val shower = notice.shower
        val night = if (notice.onPeakDay) "今夜" else "明日の夜"
        val body = StringBuilder("いちばんよく流れる夜です。")
        if (notice.onPeakDay) {
            // 数を言えるのはこの日だけ。**外れた日の見積りは当てにならない**（SkyTips と同じ）
            body.append("街あかりの無いところなら、1時間に${shower.zhr}個ほど見えます。")
        } else {
            body.append("空の暗い場所を、いまのうちに決めておくと安心です。")
        }
        if (moonIlluminated >= BRIGHT_MOON) {
            body.append("${night}は月が明るいので、月から離れた空を眺めてください。")
        }
        return Text(title = "$night、${shower.nameJa}", body = body.toString())
    }
}
