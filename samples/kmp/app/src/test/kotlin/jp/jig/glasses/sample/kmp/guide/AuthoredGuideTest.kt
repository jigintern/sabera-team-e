package jp.jig.glasses.sample.kmp.guide

import jp.jig.glasses.sample.kmp.sky.GuidanceTargetKind
import jp.jig.glasses.sample.kmp.sky.Site
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 編集中の台本。**並べ替えも上限の判定もここで完結する**ので、画面を動かさずに固定できる */
class AuthoredGuideTest {

    private val window = GuideWindow(1_789_000_000_000L, 45)
    private val site = Site(36.106, 137.626)

    private fun step(name: String, body: String = "本文です。") =
        GuideStep(name, GuidanceTargetKind.CONSTELLATION, "", body)

    private fun draft(vararg names: String) = AuthoredGuide
        .empty("guide-1", 1_789_000_000_000L, window, site)
        .let { base -> names.fold(base) { acc, name -> acc.plus(step(name)) } }

    @Test
    fun `掴んで動かすと順番が変わる`() {
        val moved = draft("A座", "B座", "C座").moved(0, 2)
        assertEquals(listOf("B座", "C座", "A座"), moved.steps.map { it.targetName })
    }

    /** ドラッグは端で行き過ぎる。**範囲の外は黙って何もしない** */
    @Test
    fun `範囲の外へ動かしても壊れない`() {
        val original = draft("A座", "B座")
        assertEquals(original, original.moved(0, -1))
        assertEquals(original, original.moved(1, 9))
        assertEquals(original, original.moved(0, 0))
    }

    /** 配る直前の ON/OFF。**段は消さない**ので翌週そのまま戻せる */
    @Test
    fun `外した段は残るが、配るぶんには入らない`() {
        val off = draft("A座", "B座", "C座").toggledAt(1)
        assertEquals(3, off.steps.size)
        assertFalse(off.steps[1].enabled)
        assertEquals(listOf("A座", "C座"), off.toGuide().enabledSteps.map { it.targetName })
    }

    /** **黙って切らない。** 長すぎる段は名指しして、配るのを止める */
    @Test
    fun `グラスに入らない長さの段を名指しする`() {
        val long = "あ".repeat(GuideCodec.MAX_BODY_CHARS + 1)
        val edited = draft("A座", "B座").replacedAt(1, step("B座", long))
        assertEquals(listOf(1), edited.tooLongSteps)
        assertFalse(edited.shareable())
    }

    @Test
    fun `段が 1 つも無ければ配れない`() {
        assertFalse(draft().shareable())
        assertTrue(draft("A座").shareable())
    }

    @Test
    fun `全部外したら配れない`() {
        assertFalse(draft("A座").toggledAt(0).shareable())
    }

    /** 想定した空は台本に残る（**再生には使わないが、開き直したときに要る**） */
    @Test
    fun `想定した日時と場所が台本に入る`() {
        val guide = draft("A座").toGuide()
        assertEquals(window.startMillis, guide.plannedAtMillis)
        assertEquals(45, guide.plannedMinutes)
        assertEquals(36.106, guide.plannedLatDeg!!, 1e-9)
    }

    /** 配るときだけ立てる。**既定は編集できる** */
    @Test
    fun `編集させないのは配るときに決める`() {
        assertFalse(draft("A座").toGuide().locked)
        assertTrue(draft("A座").toGuide(locked = true).locked)
    }

    /**
     * 受け取った台本に手を入れたら、**「受け取った」はもう本当ではない**。
     * ただし何を元にしたかを消すと辿れなくなる。
     */
    @Test
    fun `受け取った台本を直すと出どころが変わり、元が残る`() {
        val received = draft("A座").toGuide().copy(
            id = "guide-9",
            title = "乗鞍・秋のツアー",
            origin = GuideOrigin.RECEIVED,
        )
        val edited = AuthoredGuide.edit(received, window, site)
        assertEquals(GuideOrigin.AUTHORED, edited.origin)
        assertEquals(GuideSource("guide-9", "乗鞍・秋のツアー"), edited.derivedFrom)
    }

    /** すでに手で書いたものを開き直しても、**元の記録を自分自身に書き換えない** */
    @Test
    fun `手で書いた台本を開き直しても元の記録は変わらない`() {
        val original = draft("A座").toGuide().copy(
            origin = GuideOrigin.AUTHORED,
            derivedFrom = GuideSource("guide-0", "もとのツアー"),
        )
        assertEquals(GuideSource("guide-0", "もとのツアー"), AuthoredGuide.edit(original, window, site).derivedFrom)
    }

    /** 想定した空を持たない台本（toC の即興ガイド）は、渡した既定で開く */
    @Test
    fun `想定を持たない台本は既定の空で開く`() {
        val impromptu = StarGuide(
            id = "guide-2",
            title = "今夜のおすすめ",
            summary = "",
            createdAtMillis = 1L,
            origin = GuideOrigin.IMPROMPTU_BUNDLED,
            steps = listOf(step("A座")),
        )
        assertNull(impromptu.plannedAtMillis)
        val edited = AuthoredGuide.edit(impromptu, window, site)
        assertEquals(window, edited.window)
        assertEquals(site, edited.site)
        assertEquals(GuideOrigin.AUTHORED, edited.origin)
    }

    @Test
    fun `名前を付けずに保存しても見出しが空にならない`() {
        assertEquals(AuthoredGuide.UNTITLED, draft("A座").toGuide().title)
    }
}
