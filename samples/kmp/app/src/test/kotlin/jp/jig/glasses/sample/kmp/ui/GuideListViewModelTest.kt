package jp.jig.glasses.sample.kmp.ui

import jp.jig.glasses.sample.kmp.guide.GuideDraft
import jp.jig.glasses.sample.kmp.guide.GuideOrigin
import jp.jig.glasses.sample.kmp.guide.GuideStep
import jp.jig.glasses.sample.kmp.guide.GuideStore
import jp.jig.glasses.sample.kmp.guide.GuideTheme
import jp.jig.glasses.sample.kmp.guide.StarGuide
import jp.jig.glasses.sample.kmp.sky.GuidanceTargetKind
import kotlinx.coroutines.CancellationException
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

/** 台本を 1 本作る進行と文言（[GuideListViewModel]） */
@OptIn(ExperimentalCoroutinesApi::class)
class GuideListViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun store(): GuideStore {
        val dir = File.createTempFile("guides", "").apply { delete(); mkdirs() }
        return GuideStore(dir)
    }

    @Test
    fun `空に星座が無ければ理由を言って作らない`() = runTest(dispatcher) {
        val vm = GuideListViewModel(store(), makeDraft = { null }, io = dispatcher)
        vm.make(GuideTheme.TONIGHT)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals("いまの空には案内できる星座がありません。日が暮れてから試してください", vm.notice)
        assertFalse(vm.making)
    }

    @Test
    fun `同梱で組んだときは退路の理由ごと伝える`() = runTest(dispatcher) {
        val s = store()
        val guide = StarGuide(
            id = "guide-1",
            title = "秋の星空ツアー",
            summary = "ペルセウス座をめぐります",
            createdAtMillis = 1_789_000_000_000L,
            origin = GuideOrigin.IMPROMPTU_BUNDLED,
            steps = listOf(
                GuideStep(
                    targetName = "ペルセウス座",
                    kind = GuidanceTargetKind.CONSTELLATION,
                    intro = "秋の星座です。",
                    body = "本文",
                    enabled = true,
                ),
            ),
        )
        val vm = GuideListViewModel(s, makeDraft = { GuideDraft(guide, "通信を使わない設定です") }, io = dispatcher)
        vm.make(GuideTheme.TONIGHT)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(
            "「${guide.title}」を作りました",
            vm.notice,
        )
        assertEquals(1, vm.guides.size)
    }

    @Test
    fun `画面を離れた中断では作れなかったと言わない`() = runTest(dispatcher) {
        // viewModelScope のキャンセルを「作れなかった」として扱うと、
        // 何も失敗していないのに通知が出る
        val vm = GuideListViewModel(
            store(),
            makeDraft = { throw CancellationException("画面を離れた") },
            io = dispatcher,
        )
        vm.make(GuideTheme.TONIGHT)
        dispatcher.scheduler.advanceUntilIdle()

        assertNull("中断なのに通知が出た: ${vm.notice}", vm.notice)
        assertFalse("making が下りていない", vm.making)
    }
}
