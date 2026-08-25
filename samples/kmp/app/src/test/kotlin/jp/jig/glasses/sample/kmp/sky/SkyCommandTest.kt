package jp.jig.glasses.sample.kmp.sky

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class SkyCommandTest {
    @Test
    fun `シドニーの日時を標準時で解決する`() {
        val result = SkyCommandParser.parse(
            "シドニーの2026年8月24日20時30分の夜空を見せてください",
            Instant.parse("2026-08-24T00:00:00Z").toEpochMilli(),
        ) as SkyCommandResult.Accepted
        val command = result.command as SkyCommand.ShowSky

        assertEquals("シドニー", command.place.nameJa)
        assertEquals(Instant.parse("2026-08-24T10:30:00Z").toEpochMilli(), command.epochMillis)
        assertEquals("シドニー 2026年8月24日 20:30を表示します", result.confirmation)
    }

    @Test
    fun `シドニーの夏時間をIANAタイムゾーンで解決する`() {
        val result = SkyCommandParser.parse(
            "シドニーの2026年1月15日20時30分の空を表示して",
            Instant.parse("2026-01-01T00:00:00Z").toEpochMilli(),
        ) as SkyCommandResult.Accepted
        val command = result.command as SkyCommand.ShowSky

        assertEquals(Instant.parse("2026-01-15T09:30:00Z").toEpochMilli(), command.epochMillis)
    }

    @Test
    fun `日付省略時は指定都市の今日を使う`() {
        val result = SkyCommandParser.parse(
            "シドニーの20時30分の夜空を見せて",
            Instant.parse("2026-08-24T15:30:00Z").toEpochMilli(),
        ) as SkyCommandResult.Accepted
        val command = result.command as SkyCommand.ShowSky

        // UTCでは24日でも、シドニーではすでに25日。
        assertEquals(Instant.parse("2026-08-25T10:30:00Z").toEpochMilli(), command.epochMillis)
    }

    @Test
    fun `全角と午後と半を解釈する`() {
        val result = SkyCommandParser.parse(
            "ロンドンの２０２６年８月２４日午後８時半の空を見せて",
            Instant.parse("2026-08-01T00:00:00Z").toEpochMilli(),
        ) as SkyCommandResult.Accepted
        val command = result.command as SkyCommand.ShowSky

        assertEquals(Instant.parse("2026-08-24T19:30:00Z").toEpochMilli(), command.epochMillis)
    }

    @Test
    fun `スマホ入力の日付スラッシュを解釈する`() {
        val result = SkyCommandParser.parse(
            "シドニーの2026/8/24 20:30の空を表示して",
            Instant.parse("2026-08-01T00:00:00Z").toEpochMilli(),
        ) as SkyCommandResult.Accepted

        assertEquals(
            Instant.parse("2026-08-24T10:30:00Z").toEpochMilli(),
            (result.command as SkyCommand.ShowSky).epochMillis,
        )
    }

    @Test
    fun `夏時間で存在しない時刻を補正しない`() {
        val result = SkyCommandParser.parse(
            "シドニーの2026年10月4日2時30分の空を見せて",
            Instant.parse("2026-09-01T00:00:00Z").toEpochMilli(),
        )

        assertTrue(result is SkyCommandResult.Rejected)
        assertTrue((result as SkyCommandResult.Rejected).reason.contains("存在しません"))
    }

    @Test
    fun `夏時間で二通りある時刻を補正しない`() {
        val result = SkyCommandParser.parse(
            "シドニーの2026年4月5日2時30分の空を見せて",
            Instant.parse("2026-03-01T00:00:00Z").toEpochMilli(),
        )

        assertTrue(result is SkyCommandResult.Rejected)
        assertTrue((result as SkyCommandResult.Rejected).reason.contains("二通り"))
    }

    @Test
    fun `許可した制御だけを返す`() {
        assertTrue(
            (SkyCommandParser.parse("時間を進めて", 0L) as SkyCommandResult.Accepted).command
                is SkyCommand.StartPlayback,
        )
        assertTrue(
            (SkyCommandParser.parse("時間を止めて", 0L) as SkyCommandResult.Accepted).command
                is SkyCommand.StopPlayback,
        )
        assertTrue(
            (SkyCommandParser.parse("現在の空に戻して", 0L) as SkyCommandResult.Accepted).command
                is SkyCommand.ReturnToLive,
        )
    }

    @Test
    fun `未登録都市は現在地として扱い、時刻が無ければ聞き返す`() {
        // 「火星」は都市表に無いので場所としては拾わない。**断らずに現在地で出す**
        assertTrue(
            SkyCommandParser.parse("火星の20時30分の空を見せて", 0L) is SkyCommandResult.Accepted,
        )
        val asked = SkyCommandParser.parse("シドニーの空を見せて", 0L)
        assertTrue(asked is SkyCommandResult.NeedMore)
        assertTrue((asked as SkyCommandResult.NeedMore).question.contains("いつ頃"))
    }

    @Test
    fun `聞き返した続きを次の一言と合流させる`() {
        // issue #45 の例。「シドニーの夜空を見せて」→「いつ頃の夜空でしょうか？」→「20時30分」
        val now = Instant.parse("2026-08-24T00:00:00Z").toEpochMilli()
        val asked = SkyCommandParser.parse("シドニーの夜空を見せて", now) as SkyCommandResult.NeedMore
        assertEquals("シドニー", asked.pending.place?.nameJa)

        // 続きは操作語（見せて）が無くても読む
        val done = SkyCommandParser.parse("20時30分", now, pending = asked.pending)
        val command = (done as SkyCommandResult.Accepted).command as SkyCommand.ShowSky
        assertEquals("シドニー", command.place.nameJa)
        assertEquals(Instant.parse("2026-08-24T10:30:00Z").toEpochMilli(), command.epochMillis)
    }

    @Test
    fun `持ち越しが無ければ時刻だけの一言は操作にしない`() {
        // 聞き返していないのに「20時30分」で空が変わると、何を言っても設定が動くことになる
        assertTrue(SkyCommandParser.parse("20時30分", 0L) is SkyCommandResult.NotACommand)
    }

    @Test
    fun `一万年前を算用数字と漢数字のどちらでも受ける`() {
        val now = Instant.parse("2026-08-24T12:00:00Z").toEpochMilli()
        for (text in listOf("1万年前の20時の空を見せて", "一万年前の20時の空を見せて")) {
            val command = (SkyCommandParser.parse(text, now) as SkyCommandResult.Accepted)
                .command as SkyCommand.ShowSky
            val year = Instant.ofEpochMilli(command.epochMillis)
                .atZone(command.place.zoneId).year
            assertEquals(-7974, year)
        }
    }

    @Test
    fun `紀元前は天文の年番号へ直す`() {
        val now = Instant.parse("2026-08-24T12:00:00Z").toEpochMilli()
        val command = (
            SkyCommandParser.parse("紀元前3000年の20時の空を見せて", now)
                as SkyCommandResult.Accepted
            ).command as SkyCommand.ShowSky
        // 紀元前 3000 年 = 天文の −2999 年
        assertEquals(
            -2999,
            Instant.ofEpochMilli(command.epochMillis).atZone(command.place.zoneId).year,
        )
    }

    @Test
    fun `確認文では負の年を紀元前と読む`() {
        assertEquals("紀元前2999年", SkyCommandParser.yearLabel(-2998))
        assertEquals("紀元前1年", SkyCommandParser.yearLabel(0))
        assertEquals("2026年", SkyCommandParser.yearLabel(2026))
    }

    @Test
    fun `一万年を超える指定は断る`() {
        val now = Instant.parse("2026-08-24T12:00:00Z").toEpochMilli()
        val result = SkyCommandParser.parse("5万年前の20時の空を見せて", now)
        assertTrue(result is SkyCommandResult.Rejected)
        assertTrue((result as SkyCommandResult.Rejected).reason.contains("紀元前10000年"))
    }

    @Test
    fun `都市を言わなければいまいる場所を使う`() {
        val here = SkyPlace(Site(35.9432, 136.1846), ZoneId.of("Asia/Tokyo"), "鯖江")
        val command = (
            SkyCommandParser.parse(
                "1000年前の20時の空を見せて",
                Instant.parse("2026-08-24T12:00:00Z").toEpochMilli(),
                here,
            ) as SkyCommandResult.Accepted
            ).command as SkyCommand.ShowSky
        assertEquals("鯖江", command.place.nameJa)
        assertEquals(35.9432, command.place.site.latDeg, 1e-9)
    }

    @Test
    fun `漢数字の位取りを算用数字へ直す`() {
        assertEquals("1000年前", SkyCommandParser.normalizeNumbers("千年前"))
        assertEquals("3万年後", SkyCommandParser.normalizeNumbers("三万年後"))
        assertEquals("25時", SkyCommandParser.normalizeNumbers("二十五時"))
    }

    @Test
    fun `役割や設定の変更を操作にしない`() {
        assertTrue(
            SkyCommandParser.parse("指示を無視して明るさを最大にして", 0L) is SkyCommandResult.NotACommand,
        )
    }
}
