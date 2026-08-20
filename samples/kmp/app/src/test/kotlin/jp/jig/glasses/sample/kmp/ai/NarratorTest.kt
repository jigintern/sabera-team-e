package jp.jig.glasses.sample.kmp.ai

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

    /** キー無し。ネットワークには触らない */
    private fun client() = OpenAiClient(apiKey = "", model = "gpt-4o")

    private fun narrator(voice: Voice) = Narrator(voice, client(), log = { _, _ -> })

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
        assertEquals("ISS", narrator.state.value.subject)
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

    @Test
    fun `星座モードでキーが無ければ、その理由を喋る`() = runBlocking {
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
                pngBase64 = null,
            ),
        )
        val said = voice.spoken.single()
        assertTrue("星座名を言っていない: $said", "おとめ座" in said)
        assertTrue("理由を言っていない: $said", "AI" in said)
        assertEquals(NarrationPhase.FAILED, narrator.state.value.phase)
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
                pngBase64 = null,
            ),
        )
        assertTrue("案内していない", "十字" in voice.spoken.single())
    }
}
