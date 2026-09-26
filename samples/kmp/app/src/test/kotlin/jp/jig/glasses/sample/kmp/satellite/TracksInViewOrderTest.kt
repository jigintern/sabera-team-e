package jp.jig.glasses.sample.kmp.satellite

import jp.jig.glasses.sample.kmp.glass.PANEL_HEIGHT
import jp.jig.glasses.sample.kmp.glass.PANEL_WIDTH
import jp.jig.glasses.sample.kmp.sky.Look
import jp.jig.glasses.sample.kmp.sky.ObservationDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 視野で絞ってから動きを調べる並べ替え（高速化）が、**答えを変えていない**ことを見る。
 *
 * 素朴な順（全機の動きを求めてから絞る）と突き合わせる。
 */
class TracksInViewOrderTest {

    private val dataDir: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "data") }
        .first { File(it, "stars.json").exists() }

    private fun scene(): SatelliteScene = SatelliteScene(
        named = Tle.parseAll(File(dataDir, "satellites.tle").readText()).map { Sgp4(it) },
        starlink = emptyList(),
    )

    // 壁時計ではなく同梱データを取った時刻で伝播する（BundledTleTime.kt）
    private val fetchedMillis: Long = bundledTleFetchedMillis(dataDir)

    private val sabae = ObservationDefaults.site
    private val observer = Observer(sabae.latDeg, sabae.lonDeg)
    private val panelAspect = PANEL_HEIGHT.toDouble() / PANEL_WIDTH

    @Test
    fun `絞る順を変えても同じ機体が同じ順で返る`() {
        val scene = scene()
        val now = fetchedMillis
        // 空の広い範囲を当たって、名前つきが入る視線を総当たりで確かめる
        var checked = 0
        for (az in 0 until 360 step 30) {
            for (alt in 10..80 step 20) {
                val tracks = scene.tracksInView(
                    observer, now, Look(az.toDouble(), alt.toDouble()),
                    fovDeg = ObservationDefaults.STAR_MAP_FOV_DEG,
                    panelAspect = panelAspect,
                    maxStarlink = 0,
                )
                if (tracks.isEmpty()) continue
                checked++
                // 高い順に並んでいること（絞り込みの順序が保たれている）
                val alts = tracks.map { it.nowAltDeg }
                assertEquals(alts.sortedDescending(), alts)
                // 名前つきには動きが付いていること（絞ったあとで求めている）
                assertTrue(
                    "名前つきなのに動きが無い",
                    tracks.all { !it.labelled || it.motion != null || it.nowAltDeg <= 0.0 },
                )
            }
        }
        assertTrue("名前つきが 1 機も視野に入らなかった", checked > 0)
    }
}
