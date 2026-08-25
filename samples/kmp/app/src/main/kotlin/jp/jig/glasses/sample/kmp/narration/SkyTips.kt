package jp.jig.glasses.sample.kmp.narration

import jp.jig.glasses.sample.kmp.satellite.Observer
import jp.jig.glasses.sample.kmp.satellite.SatelliteScene
import jp.jig.glasses.sample.kmp.sky.MoonPhase
import jp.jig.glasses.sample.kmp.sky.ObservationSnapshot
import jp.jig.glasses.sample.kmp.sky.ObservedStarFact
import jp.jig.glasses.sample.kmp.sky.Site
import jp.jig.glasses.sample.kmp.sky.TIP_PASS_WINDOW_MIN
import jp.jig.glasses.sample.kmp.sky.allowsSatellites
import jp.jig.glasses.sample.kmp.sky.cardinalDirection16
import kotlin.math.abs
import kotlin.math.ceil
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
        /**
         * これから上がってくる人工衛星。**近いものが 1 機だけ**。
         *
         * 呼ぶ側が `SatelliteScene.nextPasses` から詰め替える（ここは衛星の計算に触らない）。
         */
        val risingPass: RisingPass? = null,
        /** その日に活動している流星群。無ければ null */
        val shower: ActiveShower? = null,
    )

    /**
     * これから上がってくる 1 機。
     *
     * **「いま空に出ている」だけでは、待つという選択ができない。**
     * 真っ暗な方角でも、10 分後にそこを ISS が通るなら待つ価値がある。
     */
    class RisingPass(
        val nameJa: String,
        val inMinutes: Double,
        /**
         * 出る方角と抜ける方角。
         *
         * `SatelliteScene.Pass.path`（「西 → 南東」）を**そのまま使わない**。
         * ここは読み上げる文なので、矢印が記号のまま読まれてしまう。
         */
        val riseDirection: String,
        val setDirection: String,
        val peakAltDeg: Int,
        val peakDirection: String,
        /** いちばん高いところで日が当たっているか。当たっていなければ肉眼では見えない */
        val sunlit: Boolean,
    )

    /**
     * その日の流星群と、いまの放射点の位置。
     *
     * [zhr] は**理想条件**の 1 時間あたりの出現数なので、喋るときは必ず条件を断る。
     */
    class ActiveShower(
        val nameJa: String,
        val zhr: Int,
        val nearPeak: Boolean,
        /** 極大まであと何日。過ぎていればマイナス */
        val daysToPeak: Int,
        val radiantAzDeg: Double,
        val radiantAltDeg: Double,
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
        // **時間が決まっているものが先。** 待てば見えるものは、待つと決められるうちに言う
        risingPass(sky),
        shower(sky),
        darkness(sky),
        moon(sky),
        planet(sky),
        season(sky),
        place(sky),
        motion(sky),
    )

    /**
     * まもなく上がってくる人工衛星。**呼ぶ側が近いパスに絞って渡す**ので、あれば必ず先頭。
     *
     * 出る方角と抜ける方角を言うのは、**待つ向きが決まらないと待てない**から。
     */
    private fun risingPass(sky: Sky): SkyTip? {
        val pass = sky.risingPass ?: return null
        val minutes = ceil(pass.inMinutes).toInt().coerceAtLeast(1)
        return SkyTip(
            "まもなく人工衛星",
            "あと%d分で%sが上がってきます。%sから%sへ抜けて、いちばん高いところは高度%d度、%sです。".format(
                minutes,
                pass.nameJa,
                pass.riseDirection,
                pass.setDirection,
                pass.peakAltDeg,
                pass.peakDirection,
            ) + if (pass.sunlit) {
                "日が当たっているので、動く光として肉眼でも見えます。"
            } else {
                "ただし地球の影に入るので、肉眼では見えません。"
            },
        )
    }

    /**
     * その日の流星群。**「今夜がいちばん多い日」は見に行く理由そのもの。**
     *
     * **専門用語を使わない。** 「極大」「放射点」「ZHR」は天文の言葉で、
     * **使う人は天文の初心者**（AGENTS.md）。言われても何をすればよいか分からない。
     * 極大は「いちばんよく流れる日」、放射点は「流れ星のもと」と言い換える。
     *
     * **もとを見つめさせない。** 流れ星は空全体に出るし、もとのすぐそばに出るものは
     * 短くて目立たない。方角を言うのは「どちらの空が主役か」を伝えるため。
     */
    private fun shower(sky: Sky): SkyTip? {
        val shower = sky.shower ?: return null
        val where = if (shower.radiantAltDeg < 0.0) {
            "流れ星のもとになるあたりは、まだ地平線の下です。昇ってくるほど数が増えるので、夜がふけてからのほうがよく見えます。"
        } else {
            "流れ星は%sの空、高度%d度あたりを中心に、空じゅうへ飛び出します。".format(
                cardinalDirection16(shower.radiantAzDeg),
                shower.radiantAltDeg.roundToInt(),
            )
        }
        // 数を言うのはいちばん多い日のころだけ。**外れた日の見積りは当てにならない**
        val howMany = when {
            shower.nearPeak ->
                "街あかりの無いところなら、1時間に%d個ほど見えます。".format(shower.zhr)

            // **過ぎた日を「あと -11 日」と言わない。** 活動期間は極大よりずっと長いので、
            // 極大のあとに開く日のほうがむしろ多い（実機のログで踏んだ）
            shower.daysToPeak > 0 ->
                "いちばん多い日まで、あと%d日です。".format(shower.daysToPeak)

            else -> "いちばん多い日は過ぎましたが、まだ流れています。"
        }
        val lead = if (shower.nearPeak) {
            "今夜は${shower.nameJa}が、いちばんよく流れる日です。"
        } else {
            "いまは${shower.nameJa}の時季です。"
        }
        return SkyTip(
            "今夜の流れ星",
            lead + where + howMany + "一点を見つめるより、そのまわりを広く眺めるほうが、長い流れ星に出会えます。",
        )
    }

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
            "日が沈んだあとの明るさが、まだ空に残っています。" +
                "あと少しで空はいちばん暗くなり、見える星が一気に増えます。",
        )

        else -> SkyTip(
            "いちばん暗い空",
            "いま空はいちばん暗い状態です。これ以上暗くはならないので、" +
                "天の川をさがすならいまです。",
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
                "今夜は${name}で、新月から数えて${age}日目。" +
                    "光と影の境目では、クレーターの影がいちばん長く伸びて見えます。",
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
                "北極星は真北の空、高度${degrees}度あたりにあります。" +
                    "ほかの星はみんな、その一点を中心にゆっくり回っています。",
            )
        } else {
            SkyTip(
                "この場所の空",
                "ここからは北極星が見えません。星は南の空にある一点を中心に、" +
                    "ゆっくり回っています。",
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
                "腕をのばした握りこぶし一つが、およそ十度。" +
                    "星は一時間にその一つ半ぶん、東から西へ動きます。",
            )
        }
}

/**
 * まもなく上がってくる 1 機（[SkyTips.RisingPass]）。無ければ null。
 *
 * **まだどこからも呼ばれていない**（d354284 で作られたが tonightSky へ渡す配線が無い）。
 * つなぐと一口メモを押すたび nextPasses の軌道計算が走るので、配線は速さと合わせて判断する。
 *
 * **絞るのは肉眼で追えるものだけ。** `nextPasses` は静止軌道と測位衛星を既に落として
 * いるので、ここでは**日が当たっているか**だけを見る（影に入る機体を案内しても、
 * 出てきた空に何も見えない）。
 *
 * **時間の近いものしか出さない。** 3 時間後のパスを一口メモで言われても、
 * そのとき何をしているか分からないので待つ判断ができない。
 */
fun risingPassTip(scene: SatelliteScene?, observation: ObservationSnapshot): SkyTips.RisingPass? {
    if (scene == null || !scene.loaded) return null
    if (
        observation.simulation &&
        !observation.allowsSatellites(scene.elementAgeDays(observation.epochMillis))
    ) {
        return null
    }
    val pass = scene.nextPasses(
        observer = Observer(observation.site.latDeg, observation.site.lonDeg),
        epochMillis = observation.epochMillis,
        withinMinutes = TIP_PASS_WINDOW_MIN,
    ).firstOrNull { it.sunlitAtPeak } ?: return null
    return SkyTips.RisingPass(
        nameJa = pass.name,
        inMinutes = pass.risesInMinutes(observation.epochMillis),
        riseDirection = cardinalDirection16(pass.riseAzDeg),
        setDirection = cardinalDirection16(pass.setAzDeg),
        peakAltDeg = pass.peakAltDeg.roundToInt(),
        peakDirection = cardinalDirection16(pass.peakAzDeg),
        sunlit = pass.sunlitAtPeak,
    )
}
