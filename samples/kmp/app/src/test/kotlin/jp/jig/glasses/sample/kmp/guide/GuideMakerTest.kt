package jp.jig.glasses.sample.kmp.guide

import jp.jig.glasses.sample.kmp.sky.GuidanceTarget
import jp.jig.glasses.sample.kmp.sky.GuidanceTargetKind
import jp.jig.glasses.sample.kmp.sky.Look
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * **「作れませんでした」で終わらせない**ことを固定する。
 *
 * 圏外でも鍵が無くても AI が壊れた返事をしても、同梱の 88 星座で 1 本できる。
 * ここが崩れると、**星を見に行く場所ほど電波が届かない**という前提のまま
 * いちばん要るときに何も出せなくなる（AGENTS.md）。
 */
class GuideMakerTest {

    private val lore = mapOf(
        "さそり座" to "狩人オリオンを刺したさそりの姿です。",
        "いて座" to "半人半馬の弓の名手です。",
    )

    private val targets = listOf(
        GuidanceTarget("c:1", "さそり座", GuidanceTargetKind.CONSTELLATION, Look(180.0, 50.0)),
        GuidanceTarget("c:2", "いて座", GuidanceTargetKind.CONSTELLATION, Look(190.0, 45.0)),
    )

    private fun aiGuide() = StarGuide(
        id = "g",
        title = "AI",
        summary = "",
        createdAtMillis = 0L,
        origin = GuideOrigin.IMPROMPTU_AI,
        steps = listOf(GuideStep("さそり座", GuidanceTargetKind.CONSTELLATION, "主役です。", "AI が書いた本文。")),
    )

    @Test
    fun `通信できるなら AI の台本を使う`() = runBlocking {
        val maker = GuideMaker(lore::get, writer = { _, _, _, _ -> aiGuide() })
        val draft = maker.make(GuideTheme.TONIGHT, targets, 0L, "g")

        assertEquals(GuideOrigin.IMPROMPTU_AI, draft?.guide?.origin)
        assertNull(draft?.fellBackReason)
    }

    @Test
    fun `通信で落ちたら同梱で組む`() = runBlocking {
        val maker = GuideMaker(lore::get, writer = { _, _, _, _ -> throw IOException("圏外") })
        val draft = maker.make(GuideTheme.TONIGHT, targets, 0L, "g")

        assertEquals(GuideOrigin.IMPROMPTU_BUNDLED, draft?.guide?.origin)
        // **なぜ落ちたかを残す。** 画面に出して、AI を待ったのに同梱だったことを隠さない
        assertEquals("圏外", draft?.fellBackReason)
        assertEquals(lore["さそり座"], draft?.guide?.steps?.first()?.body)
    }

    /** 返事が来ても検査に落ちれば null。**半分だけ AI の台本にしない** */
    @Test
    fun `AI の返事を使えないときも同梱で組む`() = runBlocking {
        val maker = GuideMaker(lore::get, writer = { _, _, _, _ -> null })
        val draft = maker.make(GuideTheme.TONIGHT, targets, 0L, "g")

        assertEquals(GuideOrigin.IMPROMPTU_BUNDLED, draft?.guide?.origin)
        assertNotNull(draft?.fellBackReason)
    }

    /** 鍵が無い端末では通信そのものをしない */
    @Test
    fun `書き手がいなければ通信せず同梱で組む`() = runBlocking {
        val draft = GuideMaker(lore::get).make(GuideTheme.TONIGHT, targets, 0L, "g")

        assertEquals(GuideOrigin.IMPROMPTU_BUNDLED, draft?.guide?.origin)
        assertTrue(draft!!.guide.steps.size == 2)
    }

    /** 空に何も出ていない昼間。**作れないことは作れないと返す**（空の台本を残さない） */
    @Test
    fun `候補が無ければ作らない`() = runBlocking {
        assertNull(GuideMaker(lore::get).make(GuideTheme.TONIGHT, emptyList(), 0L, "g"))
    }
}
