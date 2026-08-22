package jp.jig.glasses.sample.kmp.ai

import jp.jig.glasses.sample.kmp.starmap.ObservedStarFact
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **タップして無反応が一番よくない**（app-flow.md）ので、どの経路でも必ず何か喋ることを押さえる。
 * 読み上げは [Voice] を差し替えて、喋った内容を文字で受け取る。
 */
class NarratorTest {

    private class FakeVoice : Voice {
        val spoken = ArrayList<String>()
        override fun say(text: String) { spoken += text }
        override fun add(text: String) { spoken += text }
        override fun stop() { spoken += "<stop>" }
    }

    /** 同梱の解説文の代わり。**通信は一切しない**ことをここでも押さえている */
    private val lore = mapOf("おとめ座" to "麦の穂を手にした、農業の女神の姿です。")

    private fun narrator(voice: Voice) = Narrator(voice, lore::get, log = { _, _ -> })

    private fun pass(
        name: String,
        az: Double,
        alt: Double,
        sunlit: Boolean,
        closestInMinutes: Double? = null,
        stationary: Boolean = false,
    ) = SatellitePass(name, az, alt, sunlit, closestInMinutes, stationary)

    @Test
    fun `衛星モードは端末の計算だけで喋る`() {
        val voice = FakeVoice()
        val narrator = narrator(voice)
        narrator.narrateSatellites(listOf(pass("ISS", 202.5, 45.4, sunlit = true)))

        val said = voice.spoken.single()
        assertTrue("機体名が入っていない: $said", "ISS" in said)
        assertTrue("方角が入っていない: $said", "南南西" in said)
        assertTrue("高度が入っていない: $said", "45" in said)
        assertTrue("肉眼で見えることに触れていない: $said", "肉眼" in said)
        assertEquals(NarrationPhase.SPEAKING, narrator.state.value.phase)
        assertEquals("ISS", narrator.state.value.constellation)
    }

    @Test
    fun `いつがいちばん近いかを言う`() {
        val voice = FakeVoice()
        narrator(voice).narrateSatellites(
            listOf(pass("ISS", 180.0, 45.0, sunlit = true, closestInMinutes = 3.2)),
        )
        assertTrue("残り時間を言っていない: ${voice.spoken}", "あと 4 分" in voice.spoken.single())
    }

    @Test
    fun `最接近が過ぎたら遠ざかっていると言う`() {
        val voice = FakeVoice()
        narrator(voice).narrateSatellites(
            listOf(pass("ISS", 180.0, 30.0, sunlit = true, closestInMinutes = -3.0)),
        )
        assertTrue("遠ざかっていることを言っていない", "遠ざかって" in voice.spoken.single())
    }

    @Test
    fun `静止軌道は動かないと言う`() {
        val voice = FakeVoice()
        narrator(voice).narrateSatellites(
            listOf(pass("ひまわり8", 172.0, 48.0, sunlit = true, stationary = true)),
        )
        assertTrue("同じ場所に見えることを言っていない", "同じ場所" in voice.spoken.single())
    }

    @Test
    fun `影に入っている衛星は見えないと言う`() {
        val voice = FakeVoice()
        narrator(voice).narrateSatellites(listOf(pass("みちびき2", 90.0, 60.0, sunlit = false)))
        val said = voice.spoken.single()
        assertTrue("影であることに触れていない: $said", "影" in said && "見えません" in said)
    }

    @Test
    fun `視野に衛星がいなくても黙らない`() {
        val voice = FakeVoice()
        val narrator = narrator(voice)
        narrator.narrateSatellites(emptyList())
        assertTrue("何も喋っていない", voice.spoken.single().isNotEmpty())
        // 失敗扱いにしておかないと、次のタップが「停止」になってしまう
        assertEquals(NarrationPhase.FAILED, narrator.state.value.phase)
        assertTrue("次のタップで始められない", !narrator.busy)
    }

    @Test
    fun `同じ視野の他機も名前を挙げる`() {
        val voice = FakeVoice()
        narrator(voice).narrateSatellites(
            listOf(
                pass("ISS", 180.0, 50.0, sunlit = true),
                pass("ひまわり8", 172.0, 48.0, sunlit = true),
                pass("しきさい", 176.0, 44.0, sunlit = false),
            ),
        )
        val said = voice.spoken.single()
        assertTrue("他機に触れていない: $said", "ひまわり8" in said && "しきさい" in said)
    }

    /** **圏外でも喋れることが本題。** 解説文は端末が持っているので、通信の有無で結果が変わらない */
    @Test
    fun `星座の解説は同梱の文から作る`() = runBlocking {
        val voice = FakeVoice()
        val narrator = narrator(voice)
        narrator.narrate(
            NarrationInput(
                calibrated = true,
                altDeg = 45.0,
                azDeg = 180.0,
                constellations = listOf("おとめ座"),
                latDeg = 35.9432,
                lonDeg = 136.1846,
                localTime = "2026-08-20 21:30 JST",
            ),
        )
        assertTrue("名乗っていない: ${voice.spoken}", voice.spoken.first() == "おとめ座ですね。")
        assertTrue("同梱の解説を喋っていない: ${voice.spoken}", voice.spoken.any { "農業の女神" in it })
        assertEquals(NarrationPhase.SPEAKING, narrator.state.value.phase)
        assertEquals(lore.getValue("おとめ座"), narrator.state.value.text)
    }

    /** 視野の惑星は日によって違うので言う価値がある。**肉眼で見えないものは言わない** */
    @Test
    fun `視野に惑星があれば一言足す`() = runBlocking {
        val voice = FakeVoice()
        val narrator = narrator(voice)
        narrator.narrate(
            NarrationInput(
                calibrated = true,
                altDeg = 45.0,
                azDeg = 180.0,
                constellations = listOf("おとめ座"),
                latDeg = 35.9432,
                lonDeg = 136.1846,
                localTime = "2026-08-20 21:30 JST",
                visibleBodies = listOf(
                    ObservedStarFact("木星", -2.1, 180.0, 44.0, 2.0),
                    ObservedStarFact("海王星", 7.8, 181.0, 46.0, 3.0),
                ),
            ),
        )
        val text = narrator.state.value.text
        assertTrue("惑星に触れていない: $text", "木星" in text)
        assertTrue("肉眼で見えない惑星まで言っている: $text", "海王星" !in text)
    }

    /** 88 星座ぶん持っているので普段は通らないが、**黙るよりは方角と目印を言う** */
    @Test
    fun `解説文を持っていない星座でも黙らない`() = runBlocking {
        val voice = FakeVoice()
        val narrator = narrator(voice)
        narrator.narrate(
            NarrationInput(
                calibrated = true,
                altDeg = 42.0,
                azDeg = 187.0,
                constellations = listOf("ちょうこくぐ座"),
                latDeg = 35.9432,
                lonDeg = 136.1846,
                localTime = "2026-08-20 21:30 JST",
            ),
        )
        val text = narrator.state.value.text
        assertTrue("星座名が無い: $text", "ちょうこくぐ座" in text)
        assertTrue("方角が無い: $text", "南" in text)
        assertEquals(NarrationPhase.SPEAKING, narrator.state.value.phase)
    }

    @Test
    fun `方位が合っていなければ、合わせ方を喋る`() = runBlocking {
        val voice = FakeVoice()
        narrator(voice).narrate(
            NarrationInput(
                calibrated = false,
                altDeg = 45.0,
                azDeg = 180.0,
                constellations = listOf("おとめ座"),
                latDeg = 35.9432,
                lonDeg = 136.1846,
                localTime = "2026-08-20 21:30 JST",
            ),
        )
        assertTrue("案内していない", "十字" in voice.spoken.single())
    }

    @Test
    fun `タップは必ず何か喋る`() {
        val voice = FakeVoice()
        runBlocking {
            narrator(voice).narrate(
                NarrationInput(
                    calibrated = true,
                    altDeg = 42.0,
                    azDeg = 187.0,
                    constellations = listOf("さそり座"),
                    latDeg = 35.9432,
                    lonDeg = 136.1846,
                    localTime = "2026-08-20 21:34 JST",
                ),
            )
        }
        assertTrue("無反応になっている", voice.spoken.isNotEmpty())
        assertTrue("星座名を言っていない: ${voice.spoken}", voice.spoken.any { "さそり座" in it })
    }
}
