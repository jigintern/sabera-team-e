package jp.jig.glasses.sample.kmp.sky

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * 選択肢の検算。
 *
 * **いちばん効くのは「組み立てた一文が本当に通るか」。** 選んだものを日本語へ組み立てて
 * [SkyCommandParser] へ渡す作りなので、ここが通らないと**押しても何も起きない選択肢**ができる。
 */
class SkyPresetsTest {

    private val now = Instant.parse("2026-08-24T11:30:00Z").toEpochMilli()
    private val here = SkyPlace(Site(35.9432, 136.1846), ZoneId.of("Asia/Tokyo"), "鯖江")

    @Test
    fun `どの組み合わせでも空を出せる`() {
        // 押しても何も起きない選択肢を残さない。組み合わせは全部通す
        for (place in SkyPresets.places) {
            for (era in SkyPresets.eras) {
                for (time in SkyPresets.times) {
                    val phrase = SkyPresets.phraseOf(place, era, time)
                    val result = SkyCommandParser.parse(phrase, now, here)
                    assertTrue(
                        "「$phrase」が ${result::class.simpleName}",
                        result is SkyCommandResult.Accepted,
                    )
                    assertTrue(
                        "「$phrase」が空の切り替えにならない",
                        (result as SkyCommandResult.Accepted).command is SkyCommand.ShowSky,
                    )
                }
            }
        }
    }

    @Test
    fun `既定の組み合わせは1万年前の夜になる`() {
        val phrase = SkyPresets.phraseOf(
            SkyPresets.defaultPlace,
            SkyPresets.defaultEra,
            SkyPresets.defaultTime,
        )
        val command = (SkyCommandParser.parse(phrase, now, here) as SkyCommandResult.Accepted)
            .command as SkyCommand.ShowSky
        val local = Instant.ofEpochMilli(command.epochMillis).atZone(here.zoneId)
        assertEquals(-7974, local.year)
        assertEquals(21, local.hour)
        assertEquals("鯖江", command.place.nameJa)
    }

    @Test
    fun `場所を選ぶとその都市になる`() {
        val sydney = SkyPresets.places.first { it.label == "シドニー" }
        val phrase = SkyPresets.phraseOf(sydney, SkyPresets.eras.first(), SkyPresets.defaultTime)
        val command = (SkyCommandParser.parse(phrase, now, here) as SkyCommandResult.Accepted)
            .command as SkyCommand.ShowSky
        assertEquals("シドニー", command.place.nameJa)
    }

    @Test
    fun `今夜を選ぶと年が変わらない`() {
        val tonight = SkyPresets.eras.first { it.label == "今夜" }
        val phrase = SkyPresets.phraseOf(SkyPresets.defaultPlace, tonight, SkyPresets.defaultTime)
        val command = (SkyCommandParser.parse(phrase, now, here) as SkyCommandResult.Accepted)
            .command as SkyCommand.ShowSky
        assertEquals(2026, Instant.ofEpochMilli(command.epochMillis).atZone(here.zoneId).year)
    }

    @Test
    fun `時代の刻みは見分けられる幅になっている`() {
        // 歳差は 25,772 年で 1 周。100 年で 1.4° なので、これより細かい刻みを足しても見分けられない
        val years = listOf(100, 1000, 5000, 10_000)
        for (y in years) {
            assertTrue(SkyPresets.eras.any { it.phrase.startsWith(shortYears(y)) })
        }
    }

    private fun shortYears(years: Int): String =
        if (years >= 10_000) "1万" else years.toString()
}
