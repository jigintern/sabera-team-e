package jp.jig.glasses.sample.kmp.satellite

import jp.jig.glasses.sample.kmp.starmap.Constellation
import jp.jig.glasses.sample.kmp.starmap.Look
import jp.jig.glasses.sample.kmp.starmap.Site
import jp.jig.glasses.sample.kmp.starmap.Star
import jp.jig.glasses.sample.kmp.starmap.StarCatalog
import jp.jig.glasses.sample.kmp.starmap.StarMapRenderer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 衛星を星図と同じ座標系に乗せるところまでの通し確認。
 *
 * **衛星モードは星を描かない**（星と軌跡が同じ緑 8 階調なので混ざると読めない）。
 * それでも軌跡を焼くと転送量は増えるので、グラスのバッファ（380,000 バイト）に
 * 収まるかどうかもここで見る。
 */
class SatelliteSceneTest {

    private val dataDir: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "data") }
        .first { File(it, "satellites.tle").exists() }

    private val sabae = Site(35.9432, 136.1846)
    private val observer = Observer(sabae.latDeg, sabae.lonDeg)

    private fun scene(): SatelliteScene = SatelliteScene(
        named = Tle.parseAll(File(dataDir, "satellites.tle").readText()).map { Sgp4(it) },
        starlink = Tle.parseAll(File(dataDir, "starlink.tle").readText()).map { Sgp4(it) },
        fetchedAt = File(dataDir, "satellites-fetched.txt").takeIf { it.exists() }?.readText(),
    )

    private fun catalog(): StarCatalog {
        val stars = ArrayList<Star>()
        JSONObject(File(dataDir, "stars.json").readText()).getJSONArray("stars").let { arr ->
            for (i in 0 until arr.length()) {
                val s = arr.getJSONArray(i)
                stars += Star(s.getInt(0), s.getDouble(1), s.getDouble(2), s.getDouble(3))
            }
        }
        val cons = ArrayList<Constellation>()
        JSONObject(File(dataDir, "constellations.json").readText()).getJSONArray("constellations").let { arr ->
            for (i in 0 until arr.length()) {
                val c = arr.getJSONObject(i)
                val lines = c.getJSONArray("lines")
                val polylines = ArrayList<List<DoubleArray>>()
                for (j in 0 until lines.length()) {
                    val seg = lines.getJSONArray(j)
                    val pts = ArrayList<DoubleArray>()
                    for (k in 0 until seg.length()) {
                        val p = seg.getJSONArray(k)
                        pts += doubleArrayOf(p.getDouble(0), p.getDouble(1))
                    }
                    polylines += pts
                }
                cons += Constellation(c.getString("abbr"), c.getString("nameJa"), polylines)
            }
        }
        return StarCatalog(stars, cons, emptyMap())
    }

    private fun compressedBytes(gray: ByteArray, width: Int, height: Int): Int {
        var bytes = 0
        var i = 0
        val count = width * height
        while (i < count) {
            val v = (gray[i].toInt() and 0xFF) ushr 5
            var run = 1
            while (i + run < count && run < 32 && ((gray[i + run].toInt() and 0xFF) ushr 5) == v) run++
            bytes++
            i += run
        }
        return bytes
    }

    @Test
    fun `いちばん高い衛星を向くとその機体が視野に入る`() {
        val scene = scene()
        val now = System.currentTimeMillis()
        val target = scene.aboveHorizon(observer, now).firstOrNull()
        checkNotNull(target) { "名前つきの衛星が 1 機も空に出ていない" }

        val tracks = scene.tracksInView(observer, now, Look(target.azDeg, target.altDeg), fovDeg = 35.0)
        assertTrue("視野に何も入らない", tracks.isNotEmpty())
        val named = tracks.filter { it.labelled }
        assertTrue("狙った衛星が入っていない", named.any { it.name == target.name })
        // 位置がそのまま出てくること。軌跡の線はもう持たない
        assertTrue("地平線より下を拾っている", tracks.all { it.nowAltDeg > 0.0 })
        println(
            "${target.name}（${target.where}）を向くと ${tracks.size} 機" +
                "（名前つき ${named.size} / スターリンク ${tracks.size - named.size}）",
        )
    }

    @Test
    fun `衛星を焼いてもグラスのバッファに収まる`() {
        val scene = scene()
        val renderer = StarMapRenderer(catalog())
        val now = System.currentTimeMillis()
        val target = checkNotNull(scene.aboveHorizon(observer, now).firstOrNull())
        val look = Look(target.azDeg, target.altDeg)
        val tracks = scene.tracksInView(observer, now, look, fovDeg = 35.0)

        val width = 528
        val height = 330
        val map = renderer.render(
            site = sabae, epochMillis = now, look = look, fovDeg = 35.0, limitMagnitude = 5.0,
            width = width, height = height, drawLines = true, maxLabels = 8, tracks = tracks,
        )
        val used = width * height * 2 + compressedBytes(map.gray, width, height)
        println("衛星 ${tracks.size} 機を焼いた 528×330: バッファ使用 $used バイト（上限 380,000）")
        assertTrue("バッファを超える: $used", used <= 380_000)
        assertTrue("衛星の名前が出ていない", map.labels.any { it.text.startsWith("●") || it.text.startsWith("○") })

        // 実際の衛星モードは星も星座線も描かない。上の値は星座モードとの合わせ技での上限で、
        // 送るのはこちら。真っ黒な背景ばかりになるので圧縮後がぐっと縮む
        val satelliteOnly = renderer.render(
            site = sabae, epochMillis = now, look = look, fovDeg = 35.0, limitMagnitude = 5.0,
            width = width, height = height, drawLines = false, maxLabels = 8, tracks = tracks,
            drawStars = false,
        )
        val usedAlone = width * height * 2 + compressedBytes(satelliteOnly.gray, width, height)
        println("衛星だけの 528×330: バッファ使用 $usedAlone バイト")
        assertTrue("衛星だけのほうが重い: $usedAlone >= $used", usedAlone < used)
        assertTrue(
            "星座名が混ざっている",
            satelliteOnly.labels.all { it.text.startsWith("●") || it.text.startsWith("○") },
        )
    }

    @Test
    fun `スターリンクは点だけで名前を出さない`() {
        val scene = scene()
        val now = System.currentTimeMillis()
        // 天頂を向く。スターリンクは全天にいるので、たいてい何本か入る
        val tracks = scene.tracksInView(observer, now, Look(180.0, 70.0), fovDeg = 50.0)
        val starlink = tracks.filterNot { it.labelled }
        assertTrue("スターリンクが名前つきになっている", starlink.none { it.labelled })
        assertTrue(
            "スターリンクの本数が上限を超えている",
            starlink.size <= SatelliteScene.MAX_STARLINK,
        )
    }

    @Test
    fun `視野の外にいる衛星は拾わない`() {
        val scene = scene()
        val now = System.currentTimeMillis()
        val target = checkNotNull(scene.aboveHorizon(observer, now).firstOrNull())

        // 狙った衛星のちょうど反対側を向く。視野 35° なら絶対に入らないはず
        val away = Look((target.azDeg + 180.0) % 360.0, -target.altDeg.coerceAtMost(80.0))
        val tracks = scene.tracksInView(observer, now, away, fovDeg = 35.0)
        assertTrue("反対側を向いたのに狙った衛星が入っている", tracks.none { it.name == target.name })

        // 視野の半分より外にいるものが混ざっていないか。
        // fovDeg は横幅なので、視線からの角度は半分＋余裕までしか入らない
        val forward = jp.jig.glasses.sample.kmp.starmap.enu(away.azDeg, away.altDeg)
        for (t in tracks) {
            val v = jp.jig.glasses.sample.kmp.starmap.enu(t.nowAzDeg, t.nowAltDeg)
            val sep = Math.toDegrees(Math.acos((v dot forward).coerceIn(-1.0, 1.0)))
            assertTrue("${t.name} が視線から $sep° も離れている", sep < 35.0 * 0.5 * 1.18 * 1.3 + 0.1)
        }
    }

    @Test
    fun `静止軌道には最接近を出さない`() {
        // 距離がほとんど変わらないので、数値のゆらぎで「あと 7 分」と出すと嘘になる
        val scene = scene()
        val now = System.currentTimeMillis()
        val himawari = scene.aboveHorizon(observer, now, limit = 30).first { it.name == "ひまわり8" }
        val motion = checkNotNull(himawari.motion) { "動きが出ていない" }
        assertTrue("静止と判定できていない", motion.stationary)
        assertTrue("静止なのに最接近を出している: ${motion.closestInMinutes}", motion.closestInMinutes == null)
        println("ひまわり8: ${himawari.timing}")
    }

    @Test
    fun `低軌道は最接近までの分を出す`() {
        // ISS が空に出ている時刻を探してから見る（いつでも出ているわけではない）
        val issTle = Tle.parseAll(File(dataDir, "satellites.tle").readText()).first { it.name == "ISS" }
        val iss = Sgp4(issTle)
        val start = System.currentTimeMillis()
        var found = -1L
        var step = 0L
        while (step < 24 * 60) {
            val at = start + step * 60_000L
            val state = iss.at(at)
            if (state != null && observer.look(state, at).altDeg > 30.0) {
                found = at
                break
            }
            step++
        }
        assertTrue("24 時間のあいだ ISS が 30° より上に来ない", found > 0)

        val scene = SatelliteScene(named = listOf(iss), starlink = emptyList())
        val sighting = scene.aboveHorizon(observer, found).first { it.name == "ISS" }
        val motion = checkNotNull(sighting.motion)
        assertTrue("静止と誤判定している", !motion.stationary)
        val minutes = checkNotNull(motion.closestInMinutes) { "最接近が出ていない" }
        assertTrue("窓の外を返している: $minutes", minutes in -5.0..20.0)

        // 返した時刻のほうが本当に近いこと
        val nowRange = observer.look(checkNotNull(iss.at(found)), found).rangeKm
        val closestAt = found + (minutes * 60_000).toLong()
        val closestRange = observer.look(checkNotNull(iss.at(closestAt)), closestAt).rangeKm
        assertTrue("最接近のほうが遠い: $closestRange > $nowRange", closestRange <= nowRange + 0.1)
        println(
            "ISS: 高度 ${"%.0f".format(sighting.altDeg)}° / ${sighting.timing} / " +
                "いま ${"%.0f".format(nowRange)}km → 最接近 ${"%.0f".format(closestRange)}km",
        )
    }

    @Test
    fun `TLE の古さを日数で出せる`() {
        val scene = scene()
        // 同梱データには取得日を残してある
        val age = scene.ageDays(System.currentTimeMillis())
        checkNotNull(age) { "取得日が読めない" }
        assertTrue("取得日が未来になっている: $age", age >= -0.1)

        // 位置のずれを決めるのは元期。取得日より必ず古い（落とした時点で数時間前のものが来る）
        val epochAge = scene.elementAgeDays(System.currentTimeMillis())
        checkNotNull(epochAge) { "元期が読めない" }
        assertTrue("元期が未来になっている: $epochAge", epochAge >= -0.1)
        assertTrue("元期が取得日より新しい: 元期 $epochAge / 取得 $age", epochAge >= age - 0.1)
        println("同梱した TLE は 取得 ${"%.2f".format(age)} 日前 / 元期 ${"%.2f".format(epochAge)} 日前")
    }

    @Test
    fun `印は画像を焼いた視線に対して置く`() {
        val scene = scene()
        val renderer = StarMapRenderer(catalog())
        val now = System.currentTimeMillis()
        val target = checkNotNull(scene.aboveHorizon(observer, now).firstOrNull())
        val drawnLook = Look(target.azDeg, target.altDeg)
        val tracks = scene.tracksInView(observer, now, drawnLook, fovDeg = 35.0)

        val map = renderer.render(
            site = sabae, epochMillis = now, look = drawnLook, fovDeg = 35.0, limitMagnitude = 5.0,
            width = 528, height = 330, drawLines = true, maxLabels = 8, tracks = tracks,
        )
        val fromRender = map.labels.filter { it.text.startsWith("●") || it.text.startsWith("○") }
        val fromLabels = renderer.trackLabels(drawnLook, 35.0, 528, 330, tracks)
        assertEquals("描画と印の数が合わない", fromRender.size, fromLabels.size)
        for ((a, b) in fromRender.zip(fromLabels)) {
            assertEquals("名前が違う", a.text, b.text)
            assertEquals("x がずれる", a.x.toDouble(), b.x.toDouble(), 0.0)
            assertEquals("y がずれる", a.y.toDouble(), b.y.toDouble(), 0.0)
        }

        // **別の視線で計算すると当然ずれる。** ここを取り違えると絵と印が食い違う
        val otherLook = Look((drawnLook.azDeg + 10.0) % 360.0, drawnLook.altDeg)
        val shifted = renderer.trackLabels(otherLook, 35.0, 528, 330, tracks)
        if (fromLabels.isNotEmpty() && shifted.isNotEmpty()) {
            assertTrue(
                "視線を変えたのに印が動かない（画像を焼いた視線を使えていない疑い）",
                shifted.first().x != fromLabels.first().x || shifted.first().y != fromLabels.first().y,
            )
        }
    }

    @Test
    fun `印だけ動かすときはスターリンクを回さない`() {
        val scene = scene()
        val now = System.currentTimeMillis()
        val target = checkNotNull(scene.aboveHorizon(observer, now).firstOrNull())
        val look = Look(target.azDeg, target.altDeg)

        val started = System.nanoTime()
        val onlyNamed = scene.tracksInView(observer, now, look, fovDeg = 35.0, maxStarlink = 0)
        val namedMs = (System.nanoTime() - started) / 1e6

        val started2 = System.nanoTime()
        scene.tracksInView(observer, now, look, fovDeg = 35.0)
        val allMs = (System.nanoTime() - started2) / 1e6

        assertTrue("スターリンクが混ざっている", onlyNamed.all { it.labelled })
        println("印だけ ${"%.1f".format(namedMs)}ms / 全部 ${"%.1f".format(allMs)}ms")
        assertTrue("印だけのほうが速くない", namedMs < allMs)
    }

    @Test
    fun `名前を出す数はキャンバスのテキスト枠に収まる`() {
        // キャンバスのテキストは id 0..7 の 8 個。衛星モードは星座名を出さないので枠を全部使えるが、
        // 超えたぶんは黙って落ちる（星座と分け合っていたころは 3 個までだった）
        assertTrue(SatelliteScene.MAX_NAMED <= 8)
    }
}
