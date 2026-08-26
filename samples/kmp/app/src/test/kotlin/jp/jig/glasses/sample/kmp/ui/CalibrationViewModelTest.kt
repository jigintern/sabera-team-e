package jp.jig.glasses.sample.kmp.ui

import jp.jig.glasses.sample.kmp.alignment.CalibrationEstimate
import jp.jig.glasses.sample.kmp.alignment.CalibrationResult
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

/** 方位合わせの確定と立ち直り（[CalibrationViewModel]） */
@OptIn(ExperimentalCoroutinesApi::class)
class CalibrationViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun estimate() = CalibrationEstimate(
        headingOffsetDeg = 12.0,
        pitchOffsetDeg = -3.0,
        headingStdDeg = 1.2,
        pitchStdDeg = 0.8,
        sampleCount = 40,
        stable = true,
        phoneMotionStdDeg = 0.4,
        glassMotionStdDeg = 0.5,
        spanMs = 1_500L,
    )

    @Test
    fun `確定は一度だけ渡す`() {
        val vm = CalibrationViewModel()
        val results = mutableListOf<CalibrationResult>()
        vm.commit(estimate(), 1_000L) { results += it }
        // 画面が切り替わったあとに合成がもう 1 回走ることがある
        vm.commit(estimate(), 2_000L) { results += it }

        assertEquals(1, results.size)
        assertEquals(12.0, results.first().headingOffsetDeg, 0.0)
        assertEquals(1_000L, results.first().calibratedAt)
        assertTrue(vm.committed)
    }

    @Test
    fun `進み具合は2秒で満ちる`() {
        val vm = CalibrationViewModel()
        vm.advanceHold(0L)
        assertEquals(0f, vm.holdProgress, 0.001f)
        vm.advanceHold(AUTO_CONFIRM_MS / 2)
        assertEquals(0.5f, vm.holdProgress, 0.001f)
        // 条件が崩れたら 0 に戻る（「あと少し」が手の動きとして分かる）
        vm.resetHold()
        assertEquals(0f, vm.holdProgress, 0.001f)
    }

    @Test
    fun `立ち直りの操作は二重に走らせない`() = runTest(dispatcher) {
        val vm = CalibrationViewModel()
        var sent = 0
        repeat(3) {
            vm.recover("送信中", "送りました", { "失敗（$it）" }) { sent++ }
        }
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("同じ電文が BLE に並んだ", 1, sent)
        assertEquals("送りました", vm.recoveryNote)
        assertFalse(vm.recovering)
    }

    @Test
    fun `送れなかった理由はそのまま出す`() = runTest(dispatcher) {
        val vm = CalibrationViewModel()
        vm.recover("送信中", "送りました", { "失敗（$it）" }) { throw IllegalStateException("切断") }
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals("失敗（切断）", vm.recoveryNote)
    }

    @Test
    fun `1つでも欠けたら進まない`() {
        assertTrue(calibrationReady(true, true, true, true, true))
        assertFalse(calibrationReady(false, true, true, true, true))
        assertFalse(calibrationReady(true, false, true, true, true))
        assertFalse(calibrationReady(true, true, false, true, true))
        assertFalse(calibrationReady(true, true, true, false, true))
        assertFalse(calibrationReady(true, true, true, true, false))
    }
}
