package jp.jig.glasses.sample.kmp.satellite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * 同梱した TLE が実際に使えるかを見る。
 *
 * 中身は日々変わるデータなので、値そのものではなく**性質**を確かめる
 * （読めるか、軌道の種類が想定どおりか、どれが深宇宙の分岐に落ちるか）。
 */
class TleCatalogTest {

    private val dataDir: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "data") }
        .first { File(it, "satellites.tle").exists() }

    private fun load(name: String) = Tle.parseAll(File(dataDir, name).readText())

    @Test
    fun `名前つきの衛星を全部読める`() {
        val tles = load("satellites.tle")
        assertEquals(24, tles.size)
        val names = tles.map { it.name }
        assertTrue("ISS が無い", names.any { it == "ISS" })
        // グラスの枠に入る長さか。1 文字 28px 見当で、印を足しても 576px に収まる必要がある
        val longest = names.maxByOrNull { it.length }!!
        assertTrue("名前が長すぎる: $longest", longest.length <= 8)
        assertTrue("みちびきが無い", names.count { it.contains("みちびき") } == 5)
        assertTrue("ひまわりが無い", names.count { it.contains("ひまわり") } == 2)
        // 日本のものばかりだと「有名な衛星」にならないので、世界の定番も入れてある
        for (expected in listOf("ハッブル", "テラ", "アクア", "ランドサット9", "NOAA20", "GPS1", "ガリレオ", "天宮")) {
            assertTrue("$expected が無い", names.any { it == expected })
        }
    }

    @Test
    fun `高い軌道の機体は深宇宙の分岐に落ちる`() {
        val tles = load("satellites.tle")
        val deep = tles.filter { Sgp4(it).deepSpace }.map { it.name }
        // 周期 225 分以上。準天頂・静止・GPS などの中軌道はここに入る
        assertTrue("みちびきが深宇宙扱いでない", deep.count { it.contains("みちびき") } == 5)
        assertTrue("ひまわりが深宇宙扱いでない", deep.count { it.contains("ひまわり") } == 2)
        assertTrue("GPS が深宇宙扱いでない", deep.count { it.startsWith("GPS") } == 2)
        assertTrue("ガリレオが深宇宙扱いでない", deep.contains("ガリレオ"))
        assertEquals("深宇宙は みちびき5 ＋ ひまわり2 ＋ GPS2 ＋ ガリレオ1 のはず", 10, deep.size)
        println("深宇宙の分岐: ${deep.joinToString("、")}")
    }

    @Test
    fun `同梱した 24 機すべてを伝播できる`() {
        val now = System.currentTimeMillis()
        val sabae = Observer(35.9432, 136.1846)
        val results = load("satellites.tle")
            .mapNotNull { tle -> Sgp4(tle).at(now)?.let { tle to it } }
        assertEquals("24 機すべて伝播できるはず", 24, results.size)
        for ((tle, state) in results) {
            val sub = Observer.subPoint(state, now)
            val look = sabae.look(state, now)
            assertTrue("${tle.name} の方位がおかしい", look.azDeg in 0.0..360.0)
            assertTrue("${tle.name} の高度がおかしい: ${sub.altitudeKm}", sub.altitudeKm in 200.0..40000.0)
        }
    }

    @Test
    fun `ひまわりは鯖江から見て南の空の決まった位置にいる`() {
        val sabae = Observer(35.9432, 136.1846)
        val himawari = load("satellites.tle").first { it.name == "ひまわり8" }
        val sgp4 = Sgp4(himawari)

        val now = System.currentTimeMillis()
        val look = sgp4.at(now)?.let { sabae.look(it, now) }
        checkNotNull(look) { "ひまわりを伝播できない" }

        // 東経 140.7° の静止軌道。鯖江からは南よりやや東、空の半ばに見えるはず
        assertTrue("方位が南寄りでない: ${look.azDeg}", look.azDeg in 165.0..180.0)
        assertTrue("高度がおかしい: ${look.altDeg}", look.altDeg in 40.0..55.0)
        assertTrue("距離が静止軌道でない: ${look.rangeKm}", look.rangeKm in 35000.0..40000.0)

        // 静止軌道なので 6 時間経ってもほとんど動かない（低軌道なら地平線を何周もする）
        val later = now + 6 * 3600 * 1000L
        val moved = sgp4.at(later)?.let { sabae.look(it, later) }
        checkNotNull(moved)
        assertTrue("静止軌道なのに動きすぎ: ${moved.azDeg} vs ${look.azDeg}", abs(moved.azDeg - look.azDeg) < 2.0)
        assertTrue("静止軌道なのに動きすぎ: ${moved.altDeg} vs ${look.altDeg}", abs(moved.altDeg - look.altDeg) < 2.0)

        val sub = Observer.subPoint(checkNotNull(sgp4.at(now)), now)
        assertEquals("真下の点が東経 140.7° 付近にない", 140.7, sub.lonDeg, 1.0)
        assertEquals("赤道上にない", 0.0, sub.latDeg, 1.0)
        println("ひまわり8号: 方位 ${"%.1f".format(look.azDeg)}° / 高度 ${"%.1f".format(look.altDeg)}° / ${"%.0f".format(look.rangeKm)} km")
    }

    @Test
    fun `みちびきは日本の上空に長くとどまる`() {
        val sabae = Observer(35.9432, 136.1846)
        val michibiki = load("satellites.tle").filter { it.name.contains("みちびき") }
            .map { it to Sgp4(it) }
        val now = System.currentTimeMillis()

        // 準天頂軌道の 3 機は日本の上空を分け合うので、いつでもどれかが高い位置にいる
        val highest = michibiki.mapNotNull { (tle, s) -> s.at(now)?.let { tle.name to sabae.look(it, now) } }
            .maxByOrNull { it.second.altDeg }
        checkNotNull(highest)
        println("いちばん高いみちびき: ${highest.first} 高度 ${"%.1f".format(highest.second.altDeg)}° / 方位 ${"%.1f".format(highest.second.azDeg)}°")
        assertTrue("どのみちびきも空に出ていない: ${highest.second.altDeg}", highest.second.altDeg > 30.0)
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
