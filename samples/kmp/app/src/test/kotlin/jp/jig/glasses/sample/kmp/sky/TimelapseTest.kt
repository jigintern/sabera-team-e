package jp.jig.glasses.sample.kmp.sky

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs

/**
 * 時代を送る途中の空の検算。
 *
 * **いちばん効くのは「年だけ動かす」の検査。** 経過ミリ秒を等分すると途中の枚が
 * 1 日のどこかの時刻になり、日周回転（1 日 1 回転）が歳差を飲み込んで雑音になる。
 */
class TimelapseTest {

    private val tokyo = ZoneId.of("Asia/Tokyo")
    private val now = Instant.parse("2026-08-24T11:30:00Z").toEpochMilli()

    /** 2026-08-24 20:30 JST の 1 万年前（＝紀元前 7974 年の同じ月日・同じ時刻） */
    private fun target(year: Int): Long =
        java.time.LocalDateTime.of(year, 8, 24, 20, 30).atZone(tokyo).toInstant().toEpochMilli()

    @Test
    fun `途中の枚も同じ時刻になる`() {
        // ここが崩れると、途中の枚がばらばらの時刻になって日周回転で流れが見えなくなる
        val frames = Timelapse.frames(now, target(-7974), tokyo)
        assertTrue(frames.isNotEmpty())
        for (frame in frames) {
            val local = Instant.ofEpochMilli(frame.epochMillis).atZone(tokyo)
            assertEquals("${frame.year}年の時", 20, local.hour)
            assertEquals("${frame.year}年の分", 30, local.minute)
            assertEquals("${frame.year}年の月", 8, local.monthValue)
        }
    }

    @Test
    fun `最後の1枚は目的の時刻ちょうど`() {
        // 演出の着地とそのあとに出す星図がずれると、同じ条件のはずの空が食い違う
        val to = target(-7974)
        val frames = Timelapse.frames(now, to, tokyo)
        assertEquals(to, frames.last().epochMillis)
        assertEquals(-7974, frames.last().year)
    }

    @Test
    fun `年は単調に遡る`() {
        val frames = Timelapse.frames(now, target(-7974), tokyo)
        for (i in 1 until frames.size) {
            assertTrue("${frames[i - 1].year} → ${frames[i].year}", frames[i].year <= frames[i - 1].year)
        }
        assertTrue(frames.first().year < 2026)
    }

    @Test
    fun `未来へも送れる`() {
        val frames = Timelapse.frames(now, target(9000), tokyo)
        assertEquals(9000, frames.last().year)
        for (i in 1 until frames.size) {
            assertTrue(frames[i].year >= frames[i - 1].year)
        }
    }

    @Test
    fun `出だしと着地が緩む`() {
        // 等速だと始まった瞬間が最高速で、動き出しが飛んだように見える。
        // 真ん中がいちばん速いことを、年の刻み幅で見る
        val frames = Timelapse.frames(now, target(-7974), tokyo)
        val steps = (1 until frames.size).map { abs(frames[it].year - frames[it - 1].year) }
        val middle = steps[steps.size / 2]
        assertTrue("出だし $steps", steps.first() < middle)
        // 最後の 1 枚は目的地ちょうどへ寄せるので、その手前で比べる
        assertTrue("着地 $steps", steps[steps.size - 2] < middle)
    }

    @Test
    fun `数年しか動かないなら流さない`() {
        // 「シドニーの20時30分」のような場所替えで、見えない演出に待たされないこと
        assertTrue(Timelapse.frames(now, target(2026), tokyo).isEmpty())
        assertTrue(Timelapse.frames(now, target(2029), tokyo).isEmpty())
        assertTrue(Timelapse.frames(now, target(2040), tokyo).isNotEmpty())
    }

    @Test
    fun `1万年でも1枚あたりの回り込みが飛ばない`() {
        // 空全体の回り込みは歳差の 1 周（25,772 年）ぶんなので、1 万年で約 140°。
        // ease で真ん中がいちばん速くなるぶんも込みで、1 枚 6° を超えると飛んで見える
        val frames = Timelapse.frames(now, target(-7974), tokyo)
        val perFrameYears = (1 until frames.size)
            .maxOf { abs(frames[it].year - frames[it - 1].year) }
        val turnDeg = 360.0 * perFrameYears / 25_772.0
        println("1枚あたり $turnDeg°（$perFrameYears 年）")
        assertTrue("1枚 $turnDeg°（$perFrameYears 年）", turnDeg < 6.0)
    }

    @Test
    fun `うるう日でも枚が欠けない`() {
        // 目的地はうるう年だが、途中の年はほとんどうるう年ではない。
        // withYear が 2/28 へ寄せるので枚は欠けない
        val leap = java.time.LocalDateTime.of(2028, 2, 29, 20, 30)
            .atZone(tokyo).toInstant().toEpochMilli()
        val to = java.time.LocalDateTime.of(-7972, 2, 29, 20, 30)
            .atZone(tokyo).toInstant().toEpochMilli()
        val frames = Timelapse.frames(leap, to, tokyo)
        assertEquals(Timelapse.FRAMES, frames.size)
    }
}
