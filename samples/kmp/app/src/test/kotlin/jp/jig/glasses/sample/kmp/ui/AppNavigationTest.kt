package jp.jig.glasses.sample.kmp.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 戻るキーの遷移表（[backDestination]）。全画面ぶんを固定する */
class AppNavigationTest {

    @Test
    fun `ガイド系はガイド一覧へ、一覧と接続はホームへ戻る`() {
        assertEquals(AppScreen.GUIDES, backDestination(AppScreen.GUIDE_EDITOR))
        assertEquals(AppScreen.GUIDES, backDestination(AppScreen.GUIDE_SHARE))
        assertEquals(AppScreen.GUIDES, backDestination(AppScreen.GUIDE_IMPORT))
        assertEquals(AppScreen.HOME, backDestination(AppScreen.GUIDES))
        assertEquals(AppScreen.HOME, backDestination(AppScreen.CONNECTION))
        assertEquals(AppScreen.CONNECTION, backDestination(AppScreen.CALIBRATION))
    }

    @Test
    fun `ホームと星図は遷移表で戻らない`() {
        // ホームは戻るキーを握らず、星図は確認ダイアログを挟む
        assertNull(backDestination(AppScreen.HOME))
        assertNull(backDestination(AppScreen.STAR_MAP))
    }
}
