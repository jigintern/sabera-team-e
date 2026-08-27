package jp.jig.glasses.sample.kmp.ui

import jp.jig.glasses.sample.kmp.guide.AuthoredGuide
import jp.jig.glasses.sample.kmp.guide.GuideSchedule
import jp.jig.glasses.sample.kmp.guide.GuideStore
import jp.jig.glasses.sample.kmp.guide.GuideWindow
import jp.jig.glasses.sample.kmp.sky.GuidanceTarget
import jp.jig.glasses.sample.kmp.sky.GuidanceTargetKind
import jp.jig.glasses.sample.kmp.sky.Look
import jp.jig.glasses.sample.kmp.sky.ObservationDefaults
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 詳細ガイド編集の進行（[AuthoredGuideViewModel]）。
 *
 * 肝は**候補外の名前は通信させずに端末が断る**こと（渡すと AI が台本に載せ、
 * 再生で全部飛んで「何も起きないガイド」になる）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AuthoredGuideViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val start = 1_789_000_000_000L

    private fun target(name: String, altDeg: Double = 40.0) = GuidanceTarget(
        id = "constellation:$name",
        nameJa = name,
        kind = GuidanceTargetKind.CONSTELLATION,
        aim = Look(180.0, altDeg),
    )

    private fun vm(
        // はくちょう座は空にはあるが低すぎて候補に入らない（GuidePlan.MIN_ALTITUDE_DEG = 20°）
        sky: List<GuidanceTarget> = listOf(target("オリオン座"), target("ふたご座"), target("はくちょう座", altDeg = 5.0)),
        askAi: suspend (String, List<GuidanceTarget>, List<jp.jig.glasses.sample.kmp.guide.GuideStep>, List<jp.jig.glasses.sample.kmp.openai.GuideChatTurn>) -> GuideChatReply? =
            { _, _, _, _ -> GuideChatReply("わかりました", null, 0) },
    ): AuthoredGuideViewModel {
        val dir = File.createTempFile("guides", "").apply { delete(); mkdirs() }
        val window = GuideWindow(start, GuideSchedule.DEFAULT_MINUTES)
        return AuthoredGuideViewModel(
            initialDraft = AuthoredGuide.empty("guide-1", start, window, ObservationDefaults.site),
            store = GuideStore(dir),
            targetsAtFactory = { { _ -> sky } },
            loreOf = { "同梱の解説" },
            askAi = askAi,
            io = dispatcher,
            worker = dispatcher,
        )
    }

    @Test
    fun `候補外の名前は通信せずに断る`() = runTest(dispatcher) {
        var asked = false
        val vm = vm(askAi = { _, _, _, _ -> asked = true; GuideChatReply("はい", null, 0) })
        vm.reloadCandidates()
        dispatcher.scheduler.advanceUntilIdle()

        assertFalse("低いはくちょう座が候補に混ざっている", vm.candidates.any { it.nameJa == "はくちょう座" })

        vm.chatInput = "はくちょう座を入れてください"
        vm.send { "21:00" }
        dispatcher.scheduler.advanceUntilIdle()

        assertFalse("候補外なのに通信した", asked)
        assertEquals(2, vm.chat.size)
        assertTrue(vm.chat.last().text.contains("はくちょう座"))
        assertFalse(vm.chatBusy)
    }

    @Test
    fun `候補にある名前なら AI に渡す`() = runTest(dispatcher) {
        var asked = false
        val vm = vm(askAi = { _, _, _, _ -> asked = true; GuideChatReply("入れました", null, 0) })
        vm.reloadCandidates()
        dispatcher.scheduler.advanceUntilIdle()

        vm.chatInput = "オリオン座を入れてください"
        vm.send { "21:00" }
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue("候補にあるのに通信しなかった", asked)
        assertEquals("入れました", vm.chat.last().text)
    }

    @Test
    fun `外した段の数は返事に添える`() = runTest(dispatcher) {
        val vm = vm(askAi = { _, _, _, _ -> GuideChatReply("組みました", emptyList(), 2) })
        vm.reloadCandidates()
        dispatcher.scheduler.advanceUntilIdle()

        vm.chatInput = "オリオン座で組んで"
        vm.send { "21:00" }
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("組みました（2 件は空に無いので外しました）", vm.chat.last().text)
    }

    @Test
    fun `通信できなくても台本は残す`() = runTest(dispatcher) {
        val vm = vm(askAi = { _, _, _, _ -> throw java.io.IOException("圏外") })
        vm.reloadCandidates()
        dispatcher.scheduler.advanceUntilIdle()

        vm.chatInput = "オリオン座を入れて"
        vm.send { "21:00" }
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(vm.chat.last().text.startsWith("通信できませんでした"))
        assertFalse(vm.chatBusy)
    }

    @Test
    fun `段を足すと同梱の解説が初期値に入る`() = runTest(dispatcher) {
        val vm = vm()
        vm.addTarget(target("オリオン座"))
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, vm.draft.steps.size)
        assertEquals("同梱の解説", vm.draft.steps.first().body)

        // もう一度押すと外れる
        vm.toggleTarget(target("オリオン座"))
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.draft.steps.isEmpty())
    }

    @Test
    fun `画面を離れた中断では断りを積まない`() = runTest(dispatcher) {
        // viewModelScope のキャンセルは CancellationException として askAi から抜けてくる。
        // これを「通信の失敗」として扱うと、履歴に「通信できませんでした: null」が残る
        val vm = vm(askAi = { _, _, _, _ -> throw CancellationException("画面を離れた") })
        vm.reloadCandidates()
        dispatcher.scheduler.advanceUntilIdle()

        vm.chatInput = "オリオン座を入れてください"
        vm.send { "21:00" }
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(
            "中断なのに断りが積まれた: ${vm.chat.map { it.text }}",
            vm.chat.none { !it.fromUser && it.text.startsWith("通信できませんでした") },
        )
        assertFalse("chatBusy が下りていない", vm.chatBusy)
    }
}
