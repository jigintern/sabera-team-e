package jp.jig.glasses.sample.kmp.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 月と惑星を根拠に足したので、**視野に無い天体を語る文**も落とせるようにした。
 * 「月」は日付にも出る字なので、そこで誤爆しないことをここで押さえる。
 */
class ExplanationGuardTest {

    private fun guard(
        visibleStars: Set<String> = emptySet(),
        visibleBodies: Set<String> = emptySet(),
    ) = ExplanationGuard(
        visibleStarNames = visibleStars,
        knownStarNames = setOf("ベガ", "シリウス", "アルタイル"),
        visibleBodyNames = visibleBodies,
    )

    @Test
    fun `視野外の星名は落とす`() {
        assertEquals("視野外の星名:シリウス", guard().rejectionReason("シリウスが輝いています。"))
        assertNull(guard(visibleStars = setOf("シリウス")).rejectionReason("シリウスが輝いています。"))
    }

    @Test
    fun `視野に無い惑星を語る文は落とす`() {
        assertEquals("視野外の天体:木星", guard().rejectionReason("すぐ隣に木星が見えています。"))
        assertNull(guard(visibleBodies = setOf("木星")).rejectionReason("すぐ隣に木星が見えています。"))
    }

    @Test
    fun `視野に無い月を語る文は落とす`() {
        assertEquals("視野外の天体:月", guard().rejectionReason("月が明るく照らしています。"))
        assertEquals("視野外の天体:月", guard().rejectionReason("満月の光で星が見えにくいです。"))
        assertNull(guard(visibleBodies = setOf("月")).rejectionReason("月がすぐ横にあります。"))
    }

    @Test
    fun `日付や今月は月の話ではない`() {
        assertNull(guard().rejectionReason("8月の宵に高く昇ります。"))
        assertNull(guard().rejectionReason("12月にはもっと早い時間に見えます。"))
        assertNull(guard().rejectionReason("今月は西の空に低いです。"))
        assertNull(guard().rejectionReason("あと3か月ほど見えます。"))
    }

    @Test
    fun `未提供の外部知識は落とす`() {
        assertTrue(guard().rejectionReason("この星までの距離は 25 光年です。")!!.startsWith("未提供の外部知識"))
        assertTrue(guard().rejectionReason("ギリシャ神話では狩人です。")!!.startsWith("未提供の外部知識"))
    }
}
