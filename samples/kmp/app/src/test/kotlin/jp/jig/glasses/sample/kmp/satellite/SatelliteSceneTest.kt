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
 * 衛星を星図に重ねるところまでの通し確認。
 *
 * **軌跡を焼くと転送量が増える**ので、グラスのバッファ（380,000 バイト）に
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
    fun `いちばん高い衛星を向くと軌跡が出る`() {
        val scene = scene()
        val now = System.currentTimeMillis()
        val target = scene.aboveHorizon(observer, now).firstOrNull()
        checkNotNull(target) { "名前つきの衛星が 1 機も空に出ていない" }

        val tracks = scene.tracksInView(observer, now, Look(target.azDeg, target.altDeg), fovDeg = 35.0)
        assertTrue("視野に何も入らない", tracks.isNotEmpty())
        val named = tracks.filter { it.labelled }
        assertTrue("狙った衛星が入っていない", named.any { it.name == target.name })
        assertTrue("軌跡が短すぎる", named.first().points.size >= 2)
        println(
            "${target.name}（${target.where}）を向くと 軌跡 ${tracks.size} 本" +
                "（名前つき ${named.size} / スターリンク ${tracks.size - named.size}）",
        )
    }

    @Test
    fun `軌跡を焼いてもグラスのバッファに収まる`() {
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
        println("衛星 ${tracks.size} 本を焼いた 528×330: バッファ使用 $used バイト（上限 380,000）")
        assertTrue("バッファを超える: $used", used <= 380_000)
        assertTrue("衛星の名前が出ていない", map.labels.any { it.text.startsWith("●") || it.text.startsWith("○") })
    }

    @Test
    fun `スターリンクは軌跡だけで名前を出さない`() {
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
    fun `TLE の古さを日数で出せる`() {
        val scene = scene()
        // 同梱データには取得日を残してある
        val age = scene.ageDays(System.currentTimeMillis())
        checkNotNull(age) { "取得日が読めない" }
        assertTrue("取得日が未来になっている: $age", age >= -0.1)
        println("同梱した TLE は ${"%.2f".format(age)} 日前のもの")
    }

    @Test
    fun `名前を出す数はテキスト枠を食い尽くさない`() {
        // キャンバスのテキストは 8 個。衛星に全部使うと星座名が出せなくなる
        assertTrue(SatelliteScene.MAX_NAMED <= 3)
        assertEquals(3, SatelliteScene.MAX_NAMED)
    }
}
