package jp.jig.glasses.sample.kmp.guide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 打たれた文を、**AI へ渡す前に端末が見る**ところ。
 *
 * `narration/AskGuardTest`（声の質問）と同じ位置づけで、ここが抜けると
 * AI が空に出ていない星座を台本に載せ、再生で全部飛んで「何も起きないガイド」になる。
 */
class GuideAskTest {

    private val known = listOf(
        "オリオン座", "さそり座", "かんむり座", "みなみのかんむり座", "おおいぬ座", "こいぬ座",
    )

    @Test
    fun `打たれた文から星座名を拾う`() {
        assertEquals(listOf("オリオン座"), GuideAsk.namesIn("オリオン座も入れてください", known))
    }

    /**
     * **長いものから見る。**
     *
     * 「かんむり座」で先に取ると、「みなみのかんむり座」と打たれたのに
     * 別の星座を案内することになる。
     */
    @Test
    fun `短い名前を含む長い名前を取り違えない`() {
        assertEquals(listOf("みなみのかんむり座"), GuideAsk.namesIn("みなみのかんむり座を入れて", known))
        assertEquals(listOf("おおいぬ座"), GuideAsk.namesIn("おおいぬ座がいい", known))
    }

    @Test
    fun `2 つ以上あればどちらも拾う`() {
        val found = GuideAsk.namesIn("さそり座とオリオン座を続けて", known)
        assertTrue(found.containsAll(listOf("さそり座", "オリオン座")))
        assertEquals(2, found.size)
    }

    @Test
    fun `名前が無ければ何も拾わない`() {
        assertTrue(GuideAsk.namesIn("もっと短くしてください", known).isEmpty())
    }

    /** **断るだけで終わらせない。** 行き止まりにすると、そこで人が諦める */
    @Test
    fun `断るときは、いつからなら入るかを添える`() {
        val message = GuideAsk.rejection("オリオン座", "21:40")
        assertTrue(message.contains("オリオン座"))
        assertTrue(message.contains("21:40"))
    }

    @Test
    fun `一晩じゅう出ないなら、その旨だけ言う`() {
        val message = GuideAsk.rejection("みなみじゅうじ座", null)
        assertTrue(message.contains("みなみじゅうじ座"))
        assertTrue(message.contains("一晩じゅう"))
    }

    /** 空打ちで通信しない。長すぎる入力は切る（声の質問と同じ 120 字） */
    @Test
    fun `空打ちと長すぎる入力を通信の前に弾く`() {
        assertNull(GuideAsk.sanitizeInput("   "))
        assertNull(GuideAsk.sanitizeInput(""))
        assertEquals(GuideAsk.MAX_INPUT_CHARS, GuideAsk.sanitizeInput("あ".repeat(500))?.length)
        assertEquals("秋の星座で 40 分", GuideAsk.sanitizeInput("  秋の星座で\n40 分  "))
    }
}
