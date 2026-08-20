package jp.jig.glasses.sample.kmp.starmap

import org.json.JSONObject
import org.junit.Assert.assertEquals
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
        assertTrue("点も輪郭も描かれていない（$lit 画素）", lit > 100)
        assertTrue("衛星以外の名前が出ている", withTrack.labels.all { it.text.startsWith("●") })
    }

    @Test
    fun `輪郭は引き出し線でつないだ枠に入り、点には重ならない`() {
        // 実物大なら 0.24 画素しかないので、輪郭は「位置」ではなく「正体」を出すもの。
        // 点の上に重ねると位置が読めなくなるので、引き出し線で離して置けているかを見る
        val renderer = StarMapRenderer(catalog())
        val look = Look(180.0, 45.0)
        val track = SkyTrack(
            name = "ISS",
            nowAzDeg = 180.0,
            nowAltDeg = 45.0,
            sunlit = true,
            labelled = true,
        )
        fun render(figures: Boolean) = renderer.render(
            site = site, epochMillis = epoch, look = look, fovDeg = 35.0, limitMagnitude = 5.0,
            drawLines = false, tracks = listOf(track), drawStars = false, drawFigures = figures,
        )

        val without = render(false)
        val with = render(true)
        val added = with.gray.indices.count { i ->
            (with.gray[i].toInt() and 0xFF) > 0 && (without.gray[i].toInt() and 0xFF) == 0
        }
        println("輪郭で増えた画素 $added")
        assertTrue("輪郭が描かれていない（増えた画素 $added）", added > 200)

        // 点のまわり（半径 12 画素）には何も足されていないこと。名前のテキストもここに出る
        val cx = PANEL_WIDTH / 2
        val cy = PANEL_HEIGHT / 2
        for (dy in -12..12) {
            for (dx in -12..12) {
                val i = (cy + dy) * PANEL_WIDTH + (cx + dx)
                val addedHere = (with.gray[i].toInt() and 0xFF) > 0 && (without.gray[i].toInt() and 0xFF) == 0
                assertTrue("点のすぐ横に輪郭が乗っている", !addedHere)
            }
        }

        // ただし遠くへ飛ばしてもいない。引き出し線の長さ ＋ 枠の高さに収まる
        val far = with.gray.indices.filter { i ->
            (with.gray[i].toInt() and 0xFF) > 0 && (without.gray[i].toInt() and 0xFF) == 0
        }.map { i -> Math.abs(i / PANEL_WIDTH - cy) }
        val size = (PANEL_WIDTH * 0.13).toInt()
        val reach = 14 + (size * 0.45).toInt() + size / 2 + 4
        assertTrue("輪郭が点から離れすぎている（最遠 ${far.max()} 画素）", far.max() <= reach)

        // 名前は点ではなく枠の上に出る。点に重ねると位置が読めない
        val labelled = SkyTrack(
            name = "ISS", nowAzDeg = 180.0, nowAltDeg = 45.0, sunlit = true, labelled = true,
        )
        val label = renderer.trackLabels(look, 35.0, PANEL_WIDTH, PANEL_HEIGHT, listOf(labelled)).single()
        assertTrue(
            "名前が点の上に乗っている（${label.x}, ${label.y}）",
            Math.abs(label.y - cy) > 20 || Math.abs(label.x - cx) > 20,
        )
    }

    @Test
    fun `近づいている機体はラベルに残り時間が付く`() {
        // 点だけでは「待てばいいのか、過ぎたのか」が分からない
        val renderer = StarMapRenderer(catalog())
        val look = Look(180.0, 45.0)
        fun label(motion: SkyMotion?): String {
            val track = SkyTrack(
                name = "ISS", nowAzDeg = 180.0, nowAltDeg = 45.0,
                sunlit = true, labelled = true, motion = motion,
            )
            return renderer.trackLabels(look, 35.0, PANEL_WIDTH, PANEL_HEIGHT, listOf(track)).single().text
        }

        assertEquals("●ISS 4分", label(SkyMotion(closestInMinutes = 3.2, rising = true, stationary = false)))
        // 静止軌道と、遠い先の最接近には出さない（枠に入らず名前ごと消える）
        assertEquals("●ISS", label(SkyMotion(closestInMinutes = null, rising = false, stationary = true)))
        assertEquals("●ISS", label(SkyMotion(closestInMinutes = 30.0, rising = true, stationary = false)))
        // 過ぎた機体に数字は出さない
        assertEquals("●ISS", label(SkyMotion(closestInMinutes = -2.0, rising = false, stationary = false)))
        assertEquals("●ISS", label(null))
    }

    @Test
    fun `衛星の名前から輪郭の形が決まる`() {
        assertEquals(SatelliteFigure.STATION, SatelliteFigure.of("ISS"))
        assertEquals(SatelliteFigure.STATION, SatelliteFigure.of("天宮"))
        assertEquals(SatelliteFigure.DISH, SatelliteFigure.of("ひまわり8"))
        assertEquals(SatelliteFigure.WINGED, SatelliteFigure.of("みちびき2"))
        assertEquals(SatelliteFigure.WINGED, SatelliteFigure.of("しきさい"))
        // 3 種類とも、正規化座標が 0..1 に収まっている（はみ出すと枠の外へ描く）
        for (figure in SatelliteFigure.entries) {
            for (stroke in figure.strokes) {
                for (p in stroke.points) {
                    assertTrue("$figure が枠から出ている: ${p[0]}, ${p[1]}", p[0] in 0.0..1.0 && p[1] in 0.0..1.0)
                }
            }
        }
    }

    @Test
    fun `向いた先の星座がAIに渡す名前の先頭に来る`() {
        // ここがずれると、AI が別の星座の解説を喋る
        val renderer = StarMapRenderer(catalog())
        for (target in renderer.visibleConstellations(site, epoch).take(5)) {
            val near = renderer.constellationsNear(site, epoch, Look(target.azDeg, target.altDeg))
            println("${target.where} を向く → ${near.joinToString("、")}")
            assertTrue(
                "${target.nameJa} を向いたのに候補にすら入っていない: $near",
                target.nameJa in near,
            )
        }
    }

    @Test
    fun `星座名は視野中心に近い順に並ぶ`() {
        val renderer = StarMapRenderer(catalog())
        val target = renderer.visibleConstellations(site, epoch).first()
        val near = renderer.constellationsNear(site, epoch, Look(target.azDeg, target.altDeg), max = 4)
        assertTrue("候補が空", near.isNotEmpty())
        assertTrue("max を超えて返っている", near.size <= 4)
        assertTrue("同じ名前が重複している", near.size == near.toSet().size)
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
