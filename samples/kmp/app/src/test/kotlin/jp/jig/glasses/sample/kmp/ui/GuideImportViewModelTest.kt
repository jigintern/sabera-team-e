package jp.jig.glasses.sample.kmp.ui

import jp.jig.glasses.sample.kmp.guide.GuideImport
import jp.jig.glasses.sample.kmp.guide.GuideOrigin
import jp.jig.glasses.sample.kmp.guide.GuideStep
import jp.jig.glasses.sample.kmp.guide.GuideStore
import jp.jig.glasses.sample.kmp.guide.StarGuide
import jp.jig.glasses.sample.kmp.sky.GuidanceTargetKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.File

/** 台本の受け取り（[GuideImportViewModel]）。**保存する前に中身を見せる**流れを固定する */
@OptIn(ExperimentalCoroutinesApi::class)
class GuideImportViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun store() = GuideStore(File.createTempFile("guides", "").apply { delete(); mkdirs() })

    private fun guide() = StarGuide(
        id = "guide-1",
        title = "秋の星空ツアー",
        summary = "ペルセウス座をめぐります",
        createdAtMillis = 1_789_000_000_000L,
        origin = GuideOrigin.AUTHORED,
        steps = listOf(
            GuideStep("ペルセウス座", GuidanceTargetKind.CONSTELLATION, "秋の星座です。", "本文", true),
        ),
    )

    @Test
    fun `読めたら中身を見せ、保存はまだしない`() = runTest(dispatcher) {
        val vm = GuideImportViewModel(store(), io = dispatcher)
        vm.scanning = true
        vm.accept(GuideImport.Ok(guide()))
        assertEquals("秋の星空ツアー", vm.pending?.title)
        assertNull(vm.rejected)
        // 読めた時点でカメラは閉じる
        assertFalse(vm.scanning)
    }

    @Test
    fun `断ったときは中身を残さない`() = runTest(dispatcher) {
        val vm = GuideImportViewModel(store(), io = dispatcher)
        vm.accept(GuideImport.Ok(guide()))
        vm.accept(GuideImport.Rejected("形式が違います"))
        // 受理と拒否が両方出ていると読む人が迷う
        assertNull(vm.pending)
        assertEquals("形式が違います", vm.rejected)
    }

    @Test
    fun `カメラを断られたらファイルの口へ誘導する`() {
        val vm = GuideImportViewModel(store(), io = dispatcher)
        vm.onCameraPermission(false)
        assertFalse(vm.scanning)
        assertEquals("カメラを使えないので、ファイルから読み込んでください", vm.rejected)
    }

    @Test
    fun `保存できたときだけ取り込み済みとして返す`() = runTest(dispatcher) {
        val vm = GuideImportViewModel(store(), io = dispatcher)
        val imported = mutableListOf<StarGuide>()
        vm.save(guide()) { imported += it }
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, imported.size)
        assertNull(vm.rejected)
    }
}
