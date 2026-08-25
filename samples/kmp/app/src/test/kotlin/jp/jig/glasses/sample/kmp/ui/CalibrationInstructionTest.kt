package jp.jig.glasses.sample.kmp.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 方位合わせで出す 1 行。
 *
 * **かざしている人が読むのはここだけ**なので、順番と文面を画面を動かさずに固定する。
 */
class CalibrationInstructionTest {

    private fun instruction(
        imuFresh: Boolean = true,
        headingReady: Boolean = true,
        compassReady: Boolean = true,
        facingReady: Boolean = true,
        stabilityReady: Boolean = true,
    ) = calibrationInstruction(imuFresh, headingReady, compassReady, facingReady, stabilityReady)

    /** **優先順は直す順。** 上を直さないと下は直せないので、下から先に言わない */
    @Test
    fun `直せないことより先に、直せることを言う`() {
        assertEquals(
            "グラスの6DoFを待っています",
            instruction(imuFresh = false, headingReady = false, compassReady = false, facingReady = false),
        )
        assertEquals(
            "スマホの向きを待っています",
            instruction(headingReady = false, compassReady = false, facingReady = false),
        )
        assertEquals("スマホを8の字に動かしてください", instruction(compassReady = false, facingReady = false))
        assertEquals("スマホを視線に正対させてください", instruction(facingReady = false))
        assertEquals("そのまま1秒ほど止めてください", instruction(stabilityReady = false))
    }

    /**
     * **判定していない動作を指示しない**（#79）。
     *
     * `headingReady` は回転ベクトルが 1 件来たかだけを見ていて、姿勢は見ていない。
     * 背面の向きは画面法線まわりの回転に不変なので、**横に寝かせても方位も仰角も変わらない**。
     * 「立ててください」と言われた人は、立てても何も変わらない。
     */
    @Test
    fun `立てろとは言わない`() {
        assertFalse("立て" in instruction(headingReady = false))
    }

    /** 全部揃ったあとも呼ばれうる（画面は文を出さないが、空文字を返さない） */
    @Test
    fun `揃っていても文は返す`() {
        assertEquals("センサーを確認しています", instruction())
    }
}
