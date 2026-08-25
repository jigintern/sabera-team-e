package jp.jig.glasses.sample.kmp.ui

import jp.jig.glasses.sample.kmp.guide.GuideOrigin
import jp.jig.glasses.sample.kmp.guide.GuideStep
import jp.jig.glasses.sample.kmp.guide.StarGuide
import jp.jig.glasses.sample.kmp.sky.GuidanceTargetKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 配る前の選び直し（[GuideShareViewModel]）。**原本は変えない** */
class GuideShareViewModelTest {

    private fun step(name: String, enabled: Boolean = true) =
        GuideStep(name, GuidanceTargetKind.CONSTELLATION, "はじめに。", "本文", enabled)

    private fun guide() = StarGuide(
        id = "guide-1",
        title = "秋の星空ツアー",
        summary = "3 星座をめぐります",
        createdAtMillis = 1_789_000_000_000L,
        origin = GuideOrigin.AUTHORED,
        steps = listOf(step("ペルセウス座"), step("アンドロメダ座"), step("カシオペヤ座")),
    )

    @Test
    fun `外した段は配る中身から落ちるが、原本には残る`() {
        val original = guide()
        val vm = GuideShareViewModel(original)
        vm.toggleStep(1, false)

        assertEquals(listOf(true, false, true), vm.enabled)
        assertFalse(vm.shared.steps[1].enabled)
        // 原本（渡した台本）は書き換えない
        assertTrue(original.steps[1].enabled)
    }

    @Test
    fun `編集させない指定は配る中身に乗る`() {
        val vm = GuideShareViewModel(guide())
        assertFalse(vm.shared.locked)
        vm.locked = true
        assertTrue(vm.shared.locked)
    }
}
