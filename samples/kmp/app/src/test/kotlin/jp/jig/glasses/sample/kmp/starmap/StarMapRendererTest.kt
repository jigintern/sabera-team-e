package jp.jig.glasses.sample.kmp.starmap

import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 実機で何も出ないときに「絵が空だったのか、送信より先の問題なのか」を分けるためのテスト。
 * StarCatalog.load は Context を要るので、ここでは data/ を直接読んで組み立てる。
 */
class StarMapRendererTest {

    // テストの作業ディレクトリはモジュール直下なので、data/ が見つかるまで遡る
    private val dataDir: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "data") }
        .first { File(it, "stars.json").exists() }

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

    /** 2026-01-01 21:00 JST の鯖江 */
    private val site = Site(35.9432, 136.1846)
    private val epoch = 1767268800000L

    @Test
    fun `選んだ星座を向くと画素が光る`() {
        val renderer = StarMapRenderer(catalog())
        val visible = renderer.visibleConstellations(site, epoch)
        assertTrue("この時刻に空へ出ている星座が無い", visible.isNotEmpty())

        val target = visible.first()
        val map = renderer.render(
            site = site,
            epochMillis = epoch,
            look = Look(target.azDeg, target.altDeg),
            fovDeg = 35.0,
            limitMagnitude = 5.0,
        )
        val lit = map.gray.count { (it.toInt() and 0xFF) > 0 }
        println("${target.nameJa}（${target.where}）: 光った画素 $lit / ${map.gray.size}、ラベル ${map.labels.size} 個")
        assertTrue("画像が真っ黒（$lit 画素）", lit > 500)
        assertTrue("ラベルが 1 つも出ていない", map.labels.isNotEmpty())
    }

    @Test
    fun `送るバイト数は画素数ちょうど`() {
        // 長さが足りないとファーム側で弾かれる
        val map = StarMapRenderer(catalog()).render(
            site = site,
            epochMillis = epoch,
            look = Look(180.0, 45.0),
            fovDeg = 35.0,
            limitMagnitude = 5.0,
        )
        assertTrue(map.gray.size == PANEL_WIDTH * PANEL_HEIGHT)
        assertTrue("ラベルは 8 個まで", map.labels.size <= 8)
    }

    @Test
    fun `衛星モードには星も星座名も混ざらない`() {
        // 星と軌跡は同じ緑 8 階調なので、混ざると「どれが衛星か」が読めなくなる
        val renderer = StarMapRenderer(catalog())
        val look = Look(180.0, 45.0)
        val empty = renderer.render(
            site = site,
            epochMillis = epoch,
            look = look,
            fovDeg = 35.0,
            limitMagnitude = 5.0,
            drawLines = false,
            drawStars = false,
        )
        assertTrue("衛星が居ないのに光っている", empty.gray.all { (it.toInt() and 0xFF) == 0 })
        assertTrue("星座名が残っている", empty.labels.isEmpty())

        val track = SkyTrack(
            name = "テスト衛星",
            points = (-5..5).map { doubleArrayOf(180.0 + it, 45.0) },
            nowAzDeg = 180.0,
            nowAltDeg = 45.0,
            sunlit = true,
            labelled = true,
        )
        val withTrack = renderer.render(
            site = site,
            epochMillis = epoch,
            look = look,
            fovDeg = 35.0,
            limitMagnitude = 5.0,
            drawLines = false,
            tracks = listOf(track),
            drawStars = false,
        )
        val lit = withTrack.gray.count { (it.toInt() and 0xFF) > 0 }
        println("衛星モードで光った画素 $lit / ${withTrack.gray.size}、ラベル ${withTrack.labels.map { it.text }}")
        assertTrue("軌跡が描かれていない（$lit 画素）", lit > 100)
        assertTrue("衛星以外の名前が出ている", withTrack.labels.all { it.text.startsWith("●") })
    }

    @Test
    fun `3bitに落としても階調が残る`() {
        // 量子化後に全部 0 になっていたら、実機では真っ黒になる
        val map = StarMapRenderer(catalog()).render(
            site = site,
            epochMillis = epoch,
            look = Look(180.0, 45.0),
            fovDeg = 35.0,
            limitMagnitude = 5.0,
        )
        val levels = map.gray.map { Math.round((it.toInt() and 0xFF) / 255.0 * 7.0).toInt() }.toSet()
        println("3bit に落としたあとの階調: ${levels.sorted()}")
        assertTrue("量子化すると真っ黒になる", levels.any { it > 0 })
    }
}
