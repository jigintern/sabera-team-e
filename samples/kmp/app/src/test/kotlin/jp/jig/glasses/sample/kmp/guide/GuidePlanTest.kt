package jp.jig.glasses.sample.kmp.guide

import jp.jig.glasses.sample.kmp.sky.GuidanceTarget
import jp.jig.glasses.sample.kmp.sky.GuidanceTargetKind
import jp.jig.glasses.sample.kmp.sky.Look
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuidePlanTest {

    private fun target(name: String, altDeg: Double, azDeg: Double = 180.0) = GuidanceTarget(
        id = "constellation:$name",
        nameJa = name,
        kind = GuidanceTargetKind.CONSTELLATION,
        aim = Look(azDeg, altDeg),
    )

    private fun guide(vararg names: String) = StarGuide(
        id = "g",
        title = "t",
        summary = "s",
        createdAtMillis = 0L,
        origin = GuideOrigin.IMPROMPTU_BUNDLED,
        steps = names.map { GuideStep(it, GuidanceTargetKind.CONSTELLATION, "", "$it の解説") },
    )

    /** **台本の順番は変えない。** 高いものから並べ替えると、作った人の意図が消える */
    @Test
    fun `台本の順番のまま解決する`() {
        val targets = listOf(target("いて座", 30.0), target("さそり座", 60.0))
        val resolved = GuidePlan.resolveSteps(guide("さそり座", "いて座"), targets)

        assertEquals(listOf("さそり座", "いて座"), resolved.map { it.step.targetName })
        assertTrue(resolved.all { it.playable })
    }

    /** 台本は別の日にも再生される。**その夜に出ていない星座は飛ばす** */
    @Test
    fun `空に出ていない星座は飛ばす`() {
        val resolved = GuidePlan.resolveSteps(guide("さそり座", "オリオン座"), listOf(target("さそり座", 50.0)))

        assertNotNull(resolved[0].target)
        assertNull(resolved[1].target)
        // **理由を持たせる。** 黙って消えると台本が壊れたように見える
        assertTrue("オリオン座" in resolved[1].skipReason.orEmpty())
        assertEquals(1, GuidePlan.playable(resolved).size)
    }

    /** 建物と木で見えない高さは、探させるだけ無駄になる */
    @Test
    fun `低すぎる星座は飛ばす`() {
        val resolved = GuidePlan.resolveSteps(guide("さそり座"), listOf(target("さそり座", 5.0)))

        assertNull(resolved.single().target)
        assertTrue("低すぎ" in resolved.single().skipReason.orEmpty())
    }

    /** **1 段も残らないことがある**（冬の台本を夏に再生した）。呼ぶ側が断れるよう空で返す */
    @Test
    fun `全部飛んだら空になる`() {
        val resolved = GuidePlan.resolveSteps(guide("オリオン座", "おおいぬ座"), emptyList())

        assertTrue(GuidePlan.playable(resolved).isEmpty())
    }

    /** 種別が食い違っていても、名前が合えば拾う（手で書いた台本は種別を書き落とす） */
    @Test
    fun `種別が合わなくても名前で拾う`() {
        val star = GuidanceTarget("star:vega", "ベガ", GuidanceTargetKind.STAR, Look(90.0, 70.0))
        val resolved = GuidePlan.resolveSteps(guide("ベガ"), listOf(star))

        assertEquals(star, resolved.single().target)
    }
}
