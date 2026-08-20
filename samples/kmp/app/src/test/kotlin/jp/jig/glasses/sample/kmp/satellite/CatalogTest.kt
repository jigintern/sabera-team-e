package jp.jig.glasses.sample.kmp.satellite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 同梱した TLE が実際に使えるかを見る。
 *
 * 中身は日々変わるデータなので、値そのものではなく**性質**を確かめる
 * （読めるか、軌道の種類が想定どおりか、どれが深宇宙の分岐に落ちるか）。
 */
class CatalogTest {

    private val dataDir: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "data") }
        .first { File(it, "satellites.tle").exists() }

    private fun load(name: String) = Tle.parseAll(File(dataDir, name).readText())

    @Test
    fun `名前つきの衛星を全部読める`() {
        val tles = load("satellites.tle")
        assertEquals(16, tles.size)
        val names = tles.map { it.name }
        assertTrue("きぼうが無い", names.any { it.contains("きぼう") })
        assertTrue("みちびきが無い", names.count { it.contains("みちびき") } == 5)
        assertTrue("ひまわりが無い", names.count { it.contains("ひまわり") } == 2)
    }

    @Test
    fun `みちびきとひまわりは深宇宙の分岐に落ちる`() {
        val tles = load("satellites.tle")
        val deep = tles.filter { Sgp4(it).deepSpace }.map { it.name }
        // 周期 225 分以上。準天頂と静止はここに入るので、SDP4 を書くまで出せない
        assertTrue("みちびきが深宇宙扱いでない", deep.count { it.contains("みちびき") } == 5)
        assertTrue("ひまわりが深宇宙扱いでない", deep.count { it.contains("ひまわり") } == 2)
        assertEquals("深宇宙はみちびき 5 とひまわり 2 だけのはず", 7, deep.size)
    }

    @Test
    fun `低軌道の衛星はいまの実装で伝播できる`() {
        val now = System.currentTimeMillis()
        val sabae = Observer(35.9432, 136.1846)
        val results = load("satellites.tle")
            .map { it to Sgp4(it) }
            .filterNot { (_, s) -> s.deepSpace }
            .mapNotNull { (tle, s) -> s.at(now)?.let { tle to it } }
        assertEquals("低軌道の 9 機すべてを伝播できるはず", 9, results.size)
        for ((tle, state) in results) {
            val sub = Observer.subPoint(state, now)
            assertTrue("${tle.name} の高度がおかしい: ${sub.altitudeKm}", sub.altitudeKm in 200.0..1200.0)
            val look = sabae.look(state, now)
            assertTrue("${tle.name} の方位がおかしい", look.azDeg in 0.0..360.0)
            assertTrue("${tle.name} の距離がおかしい: ${look.rangeKm}", look.rangeKm in 200.0..14000.0)
        }
    }

    @Test
    fun `スターリンクを全部読んで、いま頭上に何機いるか数える`() {
        val text = File(dataDir, "starlink.tle")
        if (!text.exists()) return
        val started = System.currentTimeMillis()
        val tles = Tle.parseAll(text.readText())
        val parsed = System.currentTimeMillis() - started
        assertTrue("スターリンクが少なすぎる: ${tles.size}", tles.size > 5000)

        val now = System.currentTimeMillis()
        val sabae = Observer(35.9432, 136.1846)
        val propagateStarted = System.currentTimeMillis()
        val sgp4 = tles.map { Sgp4(it) }
        val initialized = System.currentTimeMillis() - propagateStarted

        val lookStarted = System.currentTimeMillis()
        var aboveHorizon = 0
        var failed = 0
        for (s in sgp4) {
            val state = s.at(now)
            if (state == null) {
                failed++
                continue
            }
            if (sabae.look(state, now).altDeg > 0.0) aboveHorizon++
        }
        val looked = System.currentTimeMillis() - lookStarted

        println(
            "スターリンク ${tles.size} 機 / 読み込み ${parsed}ms / 初期化 ${initialized}ms / " +
                "全機の伝播と視線計算 ${looked}ms / 地平線より上 $aboveHorizon 機 / 伝播できず $failed 機",
        )
        // 調査で見積もった「地平線より上に 400 機ほど」と桁が合っているか
        assertTrue("頭上の機数が見積りと違いすぎる: $aboveHorizon", aboveHorizon in 100..900)
    }
}
