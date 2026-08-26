package jp.jig.glasses.sample.kmp.ui.starmap

import jp.jig.glasses.sample.kmp.sky.SkyPreset
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 再現フォームの文の組み立て（[simulationPhrase]）。
 *
 * **スマホも声とまったく同じ 1 文を通す。** ここが崩れると
 * 「スマホでは出せるのに声では出せない」が起きる。
 */
class SimulationFormTest {

    private val place = SkyPreset("シドニー", "シドニー")
    private val era = SkyPreset("1万年前", "1万年前")
    private val time = SkyPreset("20:30", "20:30")

    private fun phrase(
        detailed: Boolean = true,
        city: String = "",
        eraText: String = "",
        date: String = "",
        timeText: String = "",
    ) = simulationPhrase(place, era, time, detailed, city, eraText, date, timeText)

    @Test
    fun `打った都市が選んだ都市より優先される`() {
        // わざわざ開いて入れた指定を、選んだものが黙って上書きしない
        assertEquals("パリの1万年前 21:00の空を表示して", phrase(city = "パリ", timeText = "21:00"))
    }

    @Test
    fun `空欄は選んだもので埋める`() {
        // 日付も時代も空なら、選んだ時代が入る
        assertEquals("シドニーの1万年前 20:30の空を表示して", phrase())
    }

    @Test
    fun `時代を入れたら日付より優先する`() {
        // パーサが「何年前」を先に見るので、両方あると日付が効かない
        assertEquals("シドニーの5千年前 20:30の空を表示して", phrase(eraText = "5千年前", date = "2026/8/24"))
    }

    @Test
    fun `日付だけなら選んだ時代を混ぜない`() {
        // 日付を打った人に「1万年前の 8/24」を出さない
        assertEquals("シドニーの2026/8/24 20:30の空を表示して", phrase(date = "2026/8/24"))
    }

    @Test
    fun `細かく指定していないときは選んだものだけで組む`() {
        // 開いていない欄に打ち残しがあっても混ざらない
        assertEquals(
            simulationPhrase(place, era, time, detailed = false, "", "", "", ""),
            phrase(detailed = false, city = "パリ", date = "2026/8/24"),
        )
    }
}
