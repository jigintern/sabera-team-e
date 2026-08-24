package jp.jig.glasses.sample.kmp.satellite

import jp.jig.glasses.sample.kmp.sky.ObservationDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * パス予報（これから見えるもの）。
 *
 * **「いま空に出ている」だけでは待つ選択ができない。** 真っ暗な方角でも 10 分後に
 * ISS が通るなら待つ価値があり、次が 1 時間先なら待たずに星座を見に行ける。
 *
 * ここで固定するのは「出す条件」。**出かけても見えないものを並べないこと**が要件で、
 * 具体的な時刻は同梱 TLE と実行日で変わるので比べない。
 */
class SatellitePassTest {

    private val dataDir: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "data") }
        .first { File(it, "satellites.tle").exists() }

    private val sabae = ObservationDefaults.site
    private val observer = Observer(sabae.latDeg, sabae.lonDeg)

    private fun scene(): SatelliteScene = SatelliteScene(
        named = Tle.parseAll(File(dataDir, "satellites.tle").readText()).map { Sgp4(it) },
        starlink = emptyList(),
    )

    @Test
    fun `これから90分のパスだけを近い順に返す`() {
        val now = System.currentTimeMillis()
        val passes = scene().nextPasses(observer, now)

        assertTrue("パスが多すぎる: ${passes.size}", passes.size <= 4)
        var previous = now
        for (pass in passes) {
            assertTrue("過去のパスが混ざった: ${pass.name}", pass.risesAtMillis >= now)
            assertTrue(
                "90 分より先が混ざった: ${pass.name}",
                pass.risesAtMillis <= now + 91 * 60_000L,
            )
            assertTrue("近い順に並んでいない", pass.risesAtMillis >= previous)
            previous = pass.risesAtMillis
            // いちばん高くなるのは上がってきたあと
            assertTrue("最大高度が出る前に上がっている", pass.peakAtMillis >= pass.risesAtMillis)
        }
    }

    @Test
    fun `建物と木で見えない低いパスは出さない`() {
        val passes = scene().nextPasses(observer, System.currentTimeMillis())
        for (pass in passes) {
            assertTrue(
                "低いパスを並べている: ${pass.name} が ${pass.peakAltDeg}°",
                pass.peakAltDeg >= 20.0,
            )
            assertTrue("高度が 90° を超えた: ${pass.peakAltDeg}", pass.peakAltDeg <= 90.0)
        }
    }

    @Test
    fun `静止衛星と測位衛星はパスとして出さない`() {
        val passes = scene().nextPasses(observer, System.currentTimeMillis())
        // **上がってきても肉眼では点にもならない**ので、「次はいつ」に意味が無い。
        // ひまわりは静止軌道でそもそも上がってこない
        val farAway = listOf("GPS", "みちびき", "ガリレオ", "ひまわり")
        for (pass in passes) {
            assertTrue(
                "遠い機体をパスに出した: ${pass.name}",
                farAway.none { pass.name.startsWith(it) },
            )
        }
    }

    @Test
    fun `同じ機体を何本も並べない`() {
        val passes = scene().nextPasses(observer, System.currentTimeMillis())
        assertEquals(passes.map { it.name }, passes.map { it.name }.distinct())
    }

    @Test
    fun `名前で絞れる`() {
        val passes = scene().nextPasses(
            observer,
            System.currentTimeMillis(),
            withinMinutes = 24 * 60.0,
        ) { it.startsWith("ISS") }
        assertTrue("ISS 以外が混ざった", passes.all { it.name.startsWith("ISS") })
        // 1 日あれば ISS は必ず高いパスを持つ（周期 92 分・軌道傾斜 51.6°）
        assertTrue("1 日探して ISS のパスが 1 本も無い", passes.isNotEmpty())
    }

    @Test
    fun `出る方角と沈む方角の両方が分かる`() {
        val passes = scene().nextPasses(
            observer,
            System.currentTimeMillis(),
            withinMinutes = 24 * 60.0,
        ) { it.startsWith("ISS") }
        val pass = passes.first()
        for (az in listOf(pass.riseAzDeg, pass.peakAzDeg, pass.setAzDeg)) {
            assertTrue("方位が範囲外: $az", az in 0.0..360.0)
        }
        // 「西 → 南東」。**どちらから来てどちらへ抜けるか**が待つ向きを決める
        assertTrue("経路が読めない: ${pass.path}", pass.path.contains("→"))
        assertTrue("最大高度が読めない: ${pass.peak}", pass.peak.startsWith("最大"))
    }

    @Test
    fun `あと何分かは基準時刻から数える`() {
        val now = System.currentTimeMillis()
        val passes = scene().nextPasses(observer, now, withinMinutes = 24 * 60.0) {
            it.startsWith("ISS")
        }
        val pass = passes.first()
        assertEquals(
            (pass.risesAtMillis - now) / 60_000.0,
            pass.risesInMinutes(now),
            1e-9,
        )
    }
}
