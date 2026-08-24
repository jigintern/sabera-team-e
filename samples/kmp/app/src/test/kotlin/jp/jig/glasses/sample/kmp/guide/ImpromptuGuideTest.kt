package jp.jig.glasses.sample.kmp.guide

import jp.jig.glasses.sample.kmp.sky.GuidanceTarget
import jp.jig.glasses.sample.kmp.sky.GuidanceTargetKind
import jp.jig.glasses.sample.kmp.sky.Look
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImpromptuGuideTest {

    private fun constellation(name: String, azDeg: Double, altDeg: Double) = GuidanceTarget(
        id = "constellation:$name",
        nameJa = name,
        kind = GuidanceTargetKind.CONSTELLATION,
        aim = Look(azDeg, altDeg),
    )

    private val lore = mapOf(
        "さそり座" to "狩人オリオンを刺したさそりの姿です。ギリシャ神話では女神が送ったとされます。",
        "いて座" to "半人半馬のケンタウロス、ケイロンの姿です。神話では弓の名手として知られます。",
        "こと座" to "竪琴の星座です。天の川のほとりで、ひときわ明るいベガが輝きます。",
        "ポンプ座" to "空気ポンプを表した星座です。神話は伝わっていません。",
        "はくちょう座" to "白鳥に姿を変えたゼウスだと伝えられています。",
    )

    private fun lore(name: String): String? = lore[name]

    /** 星図に線と名前が出ない方向を案内しない。**低いものは建物と木で見えない** */
    @Test
    fun `低すぎる星座と星座以外は候補にしない`() {
        val targets = listOf(
            constellation("さそり座", 180.0, 50.0),
            constellation("いて座", 190.0, 5.0),
            GuidanceTarget("star:vega", "ベガ", GuidanceTargetKind.STAR, Look(90.0, 70.0)),
        )
        val picked = ImpromptuGuide.candidates(targets, GuideTheme.TONIGHT, ::lore)

        assertEquals(listOf("さそり座"), picked.map { it.nameJa })
    }

    /** 「明るくて探しやすい」は町の空で形が読めるものだけ */
    @Test
    fun `明るいテーマは暗い星座を落とす`() {
        val targets = listOf(
            constellation("さそり座", 180.0, 50.0),
            constellation("ポンプ座", 200.0, 45.0),
        )
        val picked = ImpromptuGuide.candidates(
            targets,
            GuideTheme.BRIGHT,
            ::lore,
            brightestMagnitude = { if (it == "さそり座") 1.0 else 4.5 },
        )

        assertEquals(listOf("さそり座"), picked.map { it.nameJa })
    }

    /**
     * **「神話」という語の有無では判定できない。** ポンプ座の本文は
     * 「神話は伝わっていません」で、語だけ見ると神話の星座になってしまう。
     */
    @Test
    fun `神話が伝わっていない星座を神話のテーマに入れない`() {
        val targets = listOf(
            constellation("ポンプ座", 200.0, 60.0),
            constellation("さそり座", 180.0, 50.0),
        )
        val picked = ImpromptuGuide.candidates(targets, GuideTheme.MYTH, ::lore)

        assertFalse("ポンプ座" in picked.map { it.nameJa })
        assertTrue("さそり座" in picked.map { it.nameJa })
    }

    /** **テーマで 1 つも残らないなら諦めて空の見やすいほうを返す。** 断るより回れるほうがよい */
    @Test
    fun `テーマに合うものが無ければ見やすいものを返す`() {
        val targets = listOf(constellation("ポンプ座", 200.0, 60.0))
        val picked = ImpromptuGuide.candidates(targets, GuideTheme.MYTH, ::lore)

        assertEquals(listOf("ポンプ座"), picked.map { it.nameJa })
    }

    /**
     * 空じゅうに散らばらせない。**南 → 北 → 南と振り回すと、
     * 方位補正がいちばん苦手な速い首振りが毎回入る。**
     */
    @Test
    fun `次はいちばん近い星座へ送る`() {
        val targets = listOf(
            constellation("こと座", 90.0, 80.0),
            constellation("さそり座", 270.0, 40.0),
            constellation("いて座", 95.0, 75.0),
        )
        val picked = ImpromptuGuide.candidates(targets, GuideTheme.TONIGHT, ::lore)

        // 高い順の先頭はこと座。次は 180° 離れたさそり座ではなく、すぐ隣のいて座
        assertEquals(listOf("こと座", "いて座", "さそり座"), picked.map { it.nameJa })
    }

    /** **方角は台本に焼き込まない。** 別の日・別の場所で再生すると嘘になる */
    @Test
    fun `台本には方角を書かない`() {
        val picked = listOf(constellation("さそり座", 180.0, 50.0))
        val guide = ImpromptuGuide.compose(GuideTheme.TONIGHT, picked, ::lore, 0L, "g")

        assertEquals("", guide?.steps?.single()?.intro)
        assertEquals(lore["さそり座"], guide?.steps?.single()?.body)
        // **同梱だけで組んだことを残す**（あとから「なぜこの文なのか」を辿れる）
        assertEquals(GuideOrigin.IMPROMPTU_BUNDLED, guide?.origin)
    }

    /** 解説文を持っていない星座は、案内できても喋ることが無い */
    @Test
    fun `本文の無い星座だけなら台本を作らない`() {
        val picked = listOf(constellation("けんびきょう座", 180.0, 50.0))

        assertNull(ImpromptuGuide.compose(GuideTheme.TONIGHT, picked, ::lore, 0L, "g"))
    }

    /**
     * **天文の言葉をそのまま喋らせない**（`SkyTipsTest` と同じ検査）。
     * 使う人は初心者で、「高度 45 度」と言われても何をすればよいか分からない。
     */
    @Test
    fun `向く前の一言に専門用語を出さない`() {
        val lines = listOf(80.0, 50.0, 35.0, 22.0).map {
            ImpromptuGuide.intro(constellation("さそり座", 180.0, it))
        }

        for (word in listOf("高度", "方位角", "等級", "度")) {
            assertTrue("専門用語が出ている「$word」", lines.none { word in it })
        }
        assertTrue(lines.all { it.startsWith("次は") && it.endsWith("さそり座です。") })
    }

    /**
     * 探している間、星図のラベルに出す「どちらを向くか」。
     *
     * **喋る文と読む文が食い違わない**ことを固定する。騒がしい場所では文字が主役なので、
     * 聞こえた言葉と読める言葉が違うと、どちらが正しいのか分からなくなる。
     */
    @Test
    fun `ラベルの方角は喋る文と同じ語からできている`() {
        for (altDeg in listOf(80.0, 50.0, 35.0, 22.0)) {
            val target = constellation("さそり座", 202.5, altDeg)
            val where = ImpromptuGuide.where(target)
            val intro = ImpromptuGuide.intro(target)

            // 「南南西 高いところ」の 2 語が、そのまま読み上げ文にも出ている
            for (word in where.split(" ")) {
                assertTrue("「$word」が読み上げ文に無い（$intro）", word in intro)
            }
            for (jargon in listOf("高度", "方位角", "等級", "度")) {
                assertFalse("専門用語が出ている「$jargon」", jargon in where)
            }
        }
    }
}
