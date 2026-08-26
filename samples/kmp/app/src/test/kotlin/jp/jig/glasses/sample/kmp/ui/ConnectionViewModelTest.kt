package jp.jig.glasses.sample.kmp.ui

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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 接続確認の進行（[ConnectionViewModel]）と失敗の言い換え */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `選ばれなかったら選び直しを促す`() = runTest(dispatcher) {
        val vm = ConnectionViewModel()
        vm.connect { null }
        assertTrue(vm.scanning)
        dispatcher.scheduler.advanceUntilIdle()
        assertFalse(vm.scanning)
        assertEquals("SABERAが選択されませんでした。もう一度接続をお試しください。", vm.error)
    }

    @Test
    fun `つながったら失敗表示を下げる`() = runTest(dispatcher) {
        val vm = ConnectionViewModel()
        vm.connect { throw RuntimeException("timeout") }
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.error!!.contains("時間切れ"))
        vm.onConnected()
        assertNull(vm.error)
    }

    @Test
    fun `失敗の言い換えは原因ごとに次の手を言う`() {
        assertTrue(connectionErrorMessage(SecurityException("x")).contains("付近のデバイス"))
        assertTrue(connectionErrorMessage(RuntimeException("bluetooth is off")).contains("オン"))
        assertTrue(connectionErrorMessage(RuntimeException("bonding failed")).contains("ペアリング"))
        assertTrue(connectionErrorMessage(RuntimeException("??")).contains("電源とBluetooth"))
    }
}
