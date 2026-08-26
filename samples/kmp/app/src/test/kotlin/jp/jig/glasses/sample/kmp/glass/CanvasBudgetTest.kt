package jp.jig.glasses.sample.kmp.glass

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 星図と案内矢印の同居判定。**外す条件と送り直す条件を 1 か所に寄せてある**ので、
 * ここが境目を守っていれば「外した矢印が 130ms 後に戻る」点滅は起きない。
 */
class CanvasBudgetTest {
    @Test
    fun `ちょうど収まるなら矢印を残す`() {
        assertTrue(overlayFits(imageBytes = 370_000, overlayBytes = 10_000, limitBytes = 380_000))
    }

    @Test
    fun `1バイト超えたら矢印を外す`() {
        assertFalse(overlayFits(imageBytes = 370_001, overlayBytes = 10_000, limitBytes = 380_000))
    }

    @Test
    fun `矢印が無いときは星図だけを見る`() {
        // 星図そのものが入らないかどうかは呼び出し側が別に見る。ここは同居の判定だけ
        assertTrue(overlayFits(imageBytes = 999_999, overlayBytes = 0, limitBytes = 380_000))
    }

    @Test
    fun `既定の上限はグラスの画像バッファ`() {
        assertTrue(overlayFits(imageBytes = CANVAS_IMAGE_BUFFER_BYTES - 1, overlayBytes = 1))
        assertFalse(overlayFits(imageBytes = CANVAS_IMAGE_BUFFER_BYTES, overlayBytes = 1))
    }
}
