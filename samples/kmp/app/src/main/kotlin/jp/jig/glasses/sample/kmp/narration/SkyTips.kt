package jp.jig.glasses.sample.kmp.narration

import jp.jig.glasses.sample.kmp.sky.MoonPhase
import jp.jig.glasses.sample.kmp.sky.ObservedStarFact
import jp.jig.glasses.sample.kmp.sky.Site
import jp.jig.glasses.sample.kmp.sky.cardinalDirection16
import kotlin.math.abs
import kotlin.math.roundToInt

/** 一口メモ 1 つ。[header] はグラスの見出し 1 行、[text] は本文（読み上げる文でもある） */
class SkyTip(val header: String, val text: String)

/**
 * ダブルタップで出す**その場の一口メモ**。
 *
 * **通信も生成も要らない。** 中身は「いまの時刻」と「この場所」から端末が計算できることだけで、
 * 解説文を同梱してあるのと同じ理由（**星を見に行く場所ほど電波が届かない**）。
 *
 * **押すたびに別のことを言う。** 同じ空でも言えることは複数あるので、
 * 押し直しで一巡できるようにしてある（[of] の `index`）。
 *
 * ここは Android に触らない（時刻も場所も呼ぶ側が渡す）ので、**JVM テストで文面を固定できる**。
 */
object SkyTips {

    /** メモを作るのに要る、端末が確定できる事実だけ */
    class Sky(
        val site: Site,
        /** 現地時刻の「時」。0..23 */
        val hourOfDay: Int,
        /** 現地の月。1..12 */
        val month: Int,
        val sunAltDeg: Double,
        val moon: MoonPhase,
        val moonAltDeg: Double,
        /** 地平線より上にいる月・惑星（明るい順）。`bodiesUp` の結果をそのまま渡す */
        val bodiesUp: List<ObservedStarFact>,
    )

    /**
     * [index] 番目のメモ。押すたびに [index] を 1 つ進めれば一巡する。
     *
     * 言えることは空によって増えたり減ったりするので、**残った数で割る**。
     * 数が変わっても押し続ければ一巡することは変わらない。
     */
    fun of(sky: Sky, index: Int): SkyTip {
        val tips = candidates(sky)
        return tips[((index % tips.size) + tips.size) % tips.size]
    }

    /**
     * 言えることを、**いまに近い順**に並べる。
     *
     * 1 回目のダブルタップでいちばん役に立つのは、目の前の空がどれだけ暗いか。
     * 「星は 1 時間に 15 度動く」のような、いつ押しても同じことは後ろに置く。
     */
    fun candidates(sky: Sky): List<SkyTip> = listOfNotNull(
        darkness(sky),
        moon(sky),
        planet(sky),
        season(sky),
        place(sky),
        motion(sky),
    )

    /** いまの空がどれだけ暗いか。**暗さで「何を探せるか」が決まる** */
    private fun darkness(sky: Sky): SkyTip = when {
        sky.sunAltDeg > -0.833 -> SkyTip(
            "まだ昼の空",
            "太陽がまだ地平線の上にいます。日が沈んでから一時間半ほどで、" +
                "星がよく見える暗さになります。",
        )

        sky.sunAltDeg > -6.0 -> SkyTip(
            "空の明るさ",
            "日が沈んだばかりで、まだ空に明るさが残っています。" +
                "いま見えるのは、いちばん明るい星と惑星だけです。",
        )

        sky.sunAltDeg > -18.0 -> SkyTip(
            "空の明るさ",
            "まだ薄明が残っています。太陽が地平線の下十八度まで沈むと、" +
                "空はいちばん暗くなり、見える星が一気に増えます。",
        )

        else -> SkyTip(
            "いちばん暗い空",
            "太陽は地平線の下十八度より深くにいます。今夜これ以上暗くはならないので、" +
                "天の川をねらうならいまです。",
        )
    }

    /** 今夜の月。**その夜の空の明るさをいちばん左右する** */
    private fun moon(sky: Sky): SkyTip {
        val name = sky.moon.nameJa
        val age = sky.moon.ageDays.roundToInt()
        return when {
            sky.moonAltDeg < 0.0 -> SkyTip(
                "今夜の月",
                "月は地平線の下にいます。月あかりがないので、" +
                    "暗い星や天の川を探すのに向いた夜です。",
            )

            sky.moon.illuminated > 0.7 -> SkyTip(
                "今夜の月",
                "今夜は${name}で、月あかりの強い夜です。" +
                    "暗い星は消えてしまうので、明るい星と惑星を探すのが向いています。",
            )

            sky.moon.illuminated < 0.25 -> SkyTip(
                "今夜の月",
                "今夜は${name}。月あかりが弱いので、暗い星まで見えます。",
            )

            else -> SkyTip(
                "今夜の月",
                "今夜は${name}で、月齢はおよそ${age}日。" +
                    "欠けぎわのクレーターがいちばん立体的に見えるころです。",
            )
        }
    }

    /**
     * いま出ている惑星。**肉眼で見えるものだけ**を言う。
     *
     * 見えないものを挙げると探しに行かせてしまう（星図が天王星まで出すのとは別の話で、
     * こちらは「探すといい」と勧める文になる）。
     */
    private fun planet(sky: Sky): SkyTip? {
        val planet = sky.bodiesUp.firstOrNull { it.nameJa != "月" } ?: return null
        // **等級は言わない。** 読み上げると「マイナス二点二等」になり、
        // 探す手がかりにならないわりに耳に残る
        return SkyTip(
            "いま出ている惑星",
            "%sが%sの空、高度%d度に出ています。".format(
                planet.nameJa,
                cardinalDirection16(planet.azDeg),
                planet.altDeg.roundToInt(),
            ) + "またたかない落ち着いた光なので、まわりの星と見分けられます。",
        )
    }

    /**
     * いまの季節の目印。**初心者が空で最初に見つけるのは星座名ではなく大三角**（AGENTS.md）。
     *
     * 南半球では季節も見える向きも入れ替わるので言わない。
     */
    private fun season(sky: Sky): SkyTip? {
        if (sky.site.latDeg <= 0.0) return null
        return when (sky.month) {
            3, 4, 5 -> SkyTip(
                "春の空の目印",
                "北の空のひしゃくの柄をそのままのばすと、うしかい座のアークトゥルスと" +
                    "おとめ座のスピカに届きます。春の大曲線です。",
            )

            6, 7, 8 -> SkyTip(
                "夏の空の目印",
                "頭の上で明るい三つ、こと座のベガ、わし座のアルタイル、" +
                    "はくちょう座のデネブが夏の大三角です。",
            )

            9, 10, 11 -> SkyTip(
                "秋の空の目印",
                "秋は明るい星が少ない季節ですが、頭の上にペガスス座の四辺形という" +
                    "大きな四角があります。そこを起点に探すと迷いません。",
            )

            else -> SkyTip(
                "冬の空の目印",
                "オリオン座のベテルギウス、おおいぬ座のシリウス、こいぬ座のプロキオンが" +
                    "冬の大三角です。シリウスは全天でいちばん明るい星です。",
            )
        }
    }

    /** この場所の空。**緯度が決まると、空の回り方と見えない範囲が決まる** */
    private fun place(sky: Sky): SkyTip {
        val degrees = abs(sky.site.latDeg).roundToInt()
        return if (sky.site.latDeg > 0.0) {
            SkyTip(
                "この場所の空",
                "ここは北緯およそ${degrees}度。北極星は真北の高度${degrees}度にあって、" +
                    "空はその一点を軸に回っています。",
            )
        } else {
            SkyTip(
                "この場所の空",
                "ここは南緯およそ${degrees}度。北極星は見えず、" +
                    "空は南の天の極を軸に回っています。",
            )
        }
    }

    /** 星の動き。いつ押しても同じことなので最後に置く */
    private fun motion(sky: Sky): SkyTip =
        if (sky.hourOfDay in 0..4) {
            SkyTip(
                "夜明け前の空",
                "真夜中をすぎると、次の季節の星座が東から先に昇ってきます。" +
                    "いま東に見えているのは、数か月あとの夜のはじめの空です。",
            )
        } else {
            SkyTip(
                "星の動き",
                "星は一時間に十五度ずつ、東から西へ動きます。" +
                    "同じ星を同じ場所で見るなら、一か月あとには二時間早い時刻になります。",
            )
        }
}
