package jp.jig.glasses.sample.kmp.doc

import jp.jig.glasses.sample.kmp.guide.GuideOrigin
import jp.jig.glasses.sample.kmp.guide.GuideStep
import jp.jig.glasses.sample.kmp.guide.StarGuide
import jp.jig.glasses.sample.kmp.sky.GuidanceTargetKind
import jp.jig.glasses.sample.kmp.ui.component.BackgroundStar
import jp.jig.glasses.sample.kmp.ui.component.ConstellationBackground

/**
 * デモ画像に写す中身。**撮り直しても同じ絵になるように固定してある。**
 *
 * 季節で変わるもの（背景の星座）と、端末に何も入っていないと空になるもの（台本）だけ。
 * 星図そのものは実物のパイプラインが描く（[PhoneScreenshotTest]）。
 */
object DemoData {

    /** 背景の星座。**夏のさそり座に固定する**（月ごとに変わると絵が揃わない） */
    fun constellation() = ConstellationBackground(
        name = "さそり座",
        season = "夏",
        stars = listOf(
            0.20f to 0.25f, 0.29f to 0.34f, 0.39f to 0.43f, 0.50f to 0.55f,
            0.56f to 0.69f, 0.68f to 0.78f, 0.80f to 0.72f, 0.86f to 0.58f,
        ).mapIndexed { index, (x, y) -> BackgroundStar(x, y, major = index == 2) },
        lines = (0..6).map { it to it + 1 },
    )

    /** 一覧・編集・QR に写す台本。**同梱の解説から組んだもの**（通信ゼロで作れるほう） */
    fun guide() = StarGuide(
        id = "guide-demo",
        title = "夏の星座めぐり",
        summary = "3 つの星座を 15 分で",
        createdAtMillis = CREATED_AT,
        origin = GuideOrigin.IMPROMPTU_BUNDLED,
        steps = listOf(
            GuideStep(
                targetName = "さそり座",
                kind = GuidanceTargetKind.CONSTELLATION,
                intro = "まずは南の低いところから。",
                body = "赤く光っているのがアンタレスです。さそりの心臓にあたる星で、" +
                    "太陽より 700 倍も大きい星が、遠いので点に見えています。",
            ),
            GuideStep(
                targetName = "こと座",
                kind = GuidanceTargetKind.CONSTELLATION,
                intro = "次は真上を見上げてみてください。",
                body = "いちばん明るい星がベガ、七夕の織姫星です。" +
                    "小さな平行四辺形が、糸を張った竪琴の形になっています。",
            ),
            GuideStep(
                targetName = "はくちょう座",
                kind = GuidanceTargetKind.CONSTELLATION,
                intro = "ベガのすぐ隣に、大きな十字があります。",
                body = "尾にあたるデネブから、くちばしのアルビレオまで伸びる十字です。" +
                    "この十字は天の川の真ん中を飛んでいます。",
            ),
        ),
    )

    /** 2026-08-26 21:00 JST。**「いつ作ったか」を絵に焼くので固定する** */
    private const val CREATED_AT = 1_787_832_000_000L
}
