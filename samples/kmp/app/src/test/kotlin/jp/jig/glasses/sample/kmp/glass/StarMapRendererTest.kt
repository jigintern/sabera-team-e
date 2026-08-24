package jp.jig.glasses.sample.kmp.glass

import jp.jig.glasses.sample.kmp.catalog.Asterism
import jp.jig.glasses.sample.kmp.catalog.Constellation
import jp.jig.glasses.sample.kmp.catalog.ConstellationFigure
import jp.jig.glasses.sample.kmp.catalog.Star
import jp.jig.glasses.sample.kmp.catalog.StarCatalog
import jp.jig.glasses.sample.kmp.satellite.SkyMotion
import jp.jig.glasses.sample.kmp.satellite.SkyTrack
import jp.jig.glasses.sample.kmp.sky.Look
import jp.jig.glasses.sample.kmp.sky.ObservationMode
import jp.jig.glasses.sample.kmp.sky.Site
import jp.jig.glasses.sample.kmp.sky.SkyCommand
import jp.jig.glasses.sample.kmp.sky.SkyCommandParser
import jp.jig.glasses.sample.kmp.sky.SkyCommandResult
import jp.jig.glasses.sample.kmp.sky.SkyDensity
import jp.jig.glasses.sample.kmp.sky.daysFromJ2000
import jp.jig.glasses.sample.kmp.sky.localSiderealDeg
import jp.jig.glasses.sample.kmp.sky.snapshot
import jp.jig.glasses.sample.kmp.sky.toApparentAltAz
import jp.jig.glasses.sample.kmp.sky.toRaDec
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
        return StarCatalog(
            stars,
            cons,
            emptyMap(),
            figures = figures(),
            asterisms = asterisms(),
            milkyWay = milkyWay(),
        )
    }

    private fun skyGuides(): JSONObject = JSONObject(File(dataDir, "asterisms.json").readText())

    private fun asterisms(): List<Asterism> = skyGuides().getJSONArray("asterisms").let { arr ->
        (0 until arr.length()).map { i ->
            val a = arr.getJSONObject(i)
            val hips = a.getJSONArray("hips")
            Asterism(a.getString("nameJa"), (0 until hips.length()).map { hips.getInt(it) }, a.getBoolean("closed"))
        }
    }

    private fun milkyWay(): List<List<DoubleArray>> = skyGuides().getJSONArray("milkyWay").let { arr ->
        (0 until arr.length()).map { i ->
            val edge = arr.getJSONArray(i)
            (0 until edge.length()).map { j ->
                val p = edge.getJSONArray(j)
                doubleArrayOf(p.getDouble(0), p.getDouble(1))
            }
        }
    }

    /** 星座絵は data/ の生成物。**アプリと同じものを読む**（ここで形を変えると実機とずれる） */
    private fun figures(): Map<String, ConstellationFigure> {
        val file = File(dataDir, "constellation-figures.json")
        if (!file.exists()) return emptyMap()
        val obj = JSONObject(file.readText()).getJSONObject("figures")
        return obj.keys().asSequence().associateWith { abbr ->
            val strokes = obj.getJSONArray(abbr)
            (0 until strokes.length()).map { i ->
                val stroke = strokes.getJSONArray(i)
                (0 until stroke.length()).map { j ->
                    val p = stroke.getJSONArray(j)
                    doubleArrayOf(p.getDouble(0), p.getDouble(1))
                }
            }
        }
    }

    /** 2026-01-01 21:00 JST の鯖江 */
    private val site = Site(35.9432, 136.1846)
    private val epoch = 1767268800000L

    @Test
    fun `シドニーの音声指定から同じ場所と時刻の星図を作れる`() {
        val parsed = SkyCommandParser.parse(
            "シドニーの2026年8月24日20時30分の夜空を見せて",
            0L,
        ) as SkyCommandResult.Accepted
        val command = parsed.command as SkyCommand.ShowSky
        val observation = ObservationMode.Simulation.fromCity(command.city, command.epochMillis).snapshot(site, 0L)
        val renderer = StarMapRenderer(catalog())
        val target = renderer.visibleConstellations(observation.site, observation.epochMillis).first()

        val map = renderer.render(
            site = observation.site,
            epochMillis = observation.epochMillis,
            look = Look(target.azDeg, target.altDeg),
            fovDeg = 35.0,
            limitMagnitude = 5.0,
            maxLabels = CANVAS_TEXT_SLOTS - 1,
        ).withStatusLabel(observation.shortLabel())

        assertTrue("シドニーの星図が真っ黒", map.gray.any { (it.toInt() and 0xFF) > 0 })
        assertTrue("星座名が無い", map.constellationNames().isNotEmpty())
        assertEquals(LabelKind.STATUS, map.labels.first().kind)
        assertEquals("シミュレーション シドニー 8/24 20:30", map.labels.first().text)
    }

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
    fun `衛星だけを描くときは星も星座名も混ざらない`() {
        // #36 で衛星は星座に重ねる形になったが、**衛星だけを描く経路そのものは残す**
        // （星と衛星の点は同じ緑 8 階調なので、混ぜる量を選べる状態にしておく）
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
            drawFigureArt = false,
            drawAsterisms = false,
            drawMilkyWay = false,
            drawGuides = false,
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
            drawFigureArt = false,
            drawAsterisms = false,
            drawMilkyWay = false,
            drawGuides = false,
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
    fun `名前つきと群れを描き分け、動いているものに矢印を付ける`() {
        // 同じ大きさの点を並べると「点々」にしか見えない。3 つ描き分けているかを見る
        val renderer = StarMapRenderer(catalog())
        val look = Look(180.0, 45.0)
        fun lit(vararg tracks: SkyTrack): Int {
            val map = renderer.render(
                site = site, epochMillis = epoch, look = look, fovDeg = 35.0, limitMagnitude = 5.0,
                drawLines = false, tracks = tracks.toList(), drawStars = false, drawFigures = false,
            )
            return map.gray.count { (it.toInt() and 0xFF) > 0 }
        }

        val crowd = lit(SkyTrack("STARLINK-1", 180.0, 45.0, sunlit = true, labelled = false))
        val stationary = lit(
            SkyTrack(
                "ひまわり8", 180.0, 45.0, sunlit = true, labelled = true,
                motion = SkyMotion(closestInMinutes = null, rising = false, stationary = true),
            ),
        )
        val moving = lit(
            SkyTrack(
                "ISS", 180.0, 45.0, sunlit = true, labelled = true,
                motion = SkyMotion(
                    closestInMinutes = 3.0, rising = true, stationary = false,
                    nextAzDeg = 180.6, nextAltDeg = 45.3,
                ),
            ),
        )
        println("画素数 群れ $crowd / 静止 $stationary / 動いている $moving")
        assertTrue("群れの点が名前つきと同じ大きさ", stationary > crowd * 2)
        assertTrue("動いていても矢印が出ていない", moving > stationary + 20)
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
    fun `月と惑星を渡すと絵と名前に出る`() {
        val renderer = StarMapRenderer(catalog())
        val look = Look(180.0, 40.0)
        val plain = renderer.render(
            site = site,
            epochMillis = epoch,
            look = look,
            fovDeg = 35.0,
            limitMagnitude = 5.0,
            width = STAR_MAP_WIDTH,
            height = STAR_MAP_HEIGHT,
        )
        val withBodies = renderer.render(
            site = site,
            epochMillis = epoch,
            look = look,
            fovDeg = 35.0,
            limitMagnitude = 5.0,
            width = STAR_MAP_WIDTH,
            height = STAR_MAP_HEIGHT,
            bodies = listOf(
                SkyBodyMark("月", look.azDeg, look.altDeg, magnitude = -10.0, moon = true),
                SkyBodyMark("木星", look.azDeg + 8.0, look.altDeg, magnitude = -2.2),
            ),
        )
        val before = plain.gray.count { it != 0.toByte() }
        val after = withBodies.gray.count { it != 0.toByte() }
        println("月と惑星で光った画素: $before → $after")
        assertTrue("月の輪と惑星の点で画素が増える", after > before)
        assertTrue("名前が出る", withBodies.labels.map { it.text }.containsAll(listOf("月", "木星")))
        // **星座名より先に置く。** 点だけでは恒星と区別が付かない
        assertEquals("月", withBodies.labels.first().text)

        // 衛星モードでは空のものを描かない（星を描かないのと同じ理由）
        val satelliteMode = renderer.render(
            site = site,
            epochMillis = epoch,
            look = look,
            fovDeg = 35.0,
            limitMagnitude = 5.0,
            drawStars = false,
            bodies = listOf(SkyBodyMark("月", look.azDeg, look.altDeg, -10.0, moon = true)),
        )
        assertTrue("衛星モードに月は出さない", satelliteMode.labels.none { it.text == "月" })
        // **AI 解説の主役に月や惑星が混ざらない。** 表示は月が先でも、主役は星座から選ぶ
        assertTrue("主役の候補に月が混ざった", withBodies.constellationNames().none { it == "月" })
        assertTrue("主役の候補に惑星が混ざった", withBodies.constellationNames().none { it == "木星" })
        assertEquals(
            "星座のラベルが主役の候補と食い違う",
            withBodies.labels.filter { it.kind == LabelKind.CONSTELLATION }.map { it.text },
            withBodies.constellationNames(),
        )
    }

    /**
     * **地平線と方位の文字は画像に焼く。**
     *
     * キャンバスのテキスト枠は 8 つしかなく星座名で埋まるので、文字も線で描く。
     * 空と地面の境目と北東南西が出ると、**星図が「空の絵」として読める**。
     */
    @Test
    fun `低い空では地平線と方位の文字が出る`() {
        val renderer = StarMapRenderer(catalog())
        // 高度 6°。視野の縦は 22° なので地平線が画面に入る
        val look = Look(178.0, 6.0)
        fun render(guides: Boolean) = renderer.render(
            site = site,
            epochMillis = epoch,
            look = look,
            fovDeg = 35.0,
            limitMagnitude = 4.2,
            width = STAR_MAP_WIDTH,
            height = STAR_MAP_HEIGHT,
            drawFigureArt = false,
            drawAsterisms = false,
            drawMilkyWay = false,
            drawGuides = guides,
        )
        val without = render(false).gray
        val withGuides = render(true).gray
        val added = withGuides.indices.count { withGuides[it] != without[it] }
        println("目印で増えた画素 $added")
        assertTrue("地平線と方位が描かれていない: $added", added > 200)

        // 下半分（地平線のあたり）に増えていること
        val lowerHalf = (STAR_MAP_HEIGHT / 2 * STAR_MAP_WIDTH until withGuides.size)
            .count { withGuides[it] != without[it] }
        assertTrue("地平線が下半分に無い: $lowerHalf", lowerHalf > 100)

        // 天頂近くでは地平線が入らないので、増えるのは視野中心の印だけ
        val zenith = renderer.render(
            site = site,
            epochMillis = epoch,
            look = Look(178.0, 70.0),
            fovDeg = 35.0,
            limitMagnitude = 4.2,
            width = STAR_MAP_WIDTH,
            height = STAR_MAP_HEIGHT,
            drawFigureArt = false,
            drawAsterisms = false,
            drawMilkyWay = false,
        )
        assertTrue("視野中心の印が無い", zenith.gray.isNotEmpty())
    }

    /**
     * **空の濃さで、見えない星と星座が落ちる。**
     *
     * 街の明かりの下で 5 等まで描くと、**目に見えていない星まで星図に写る**ので、
     * 見ている人は対応が取れない。かといって絞りすぎるとスカスカになるので、
     * 段階の間で「星も星座も減るが、消えはしない」ことを押さえる。
     */
    @Test
    fun `空の濃さを上げると星と星座が増える`() {
        val renderer = StarMapRenderer(catalog())
        // オリオン座のあたり（明るい星と暗い星座が混じっている）
        val look = Look(180.0, 45.0)
        fun render(density: SkyDensity) = renderer.render(
            site = site,
            epochMillis = epoch,
            look = look,
            fovDeg = 35.0,
            limitMagnitude = density.limitMagnitude,
            width = STAR_MAP_WIDTH,
            height = STAR_MAP_HEIGHT,
            constellationMagnitude = density.constellationMagnitude,
        )

        val town = render(SkyDensity.TOWN)
        val standard = render(SkyDensity.STANDARD)
        val all = render(SkyDensity.ALL)
        val counts = listOf(town, standard, all).map { map -> map.gray.count { it != 0.toByte() } }
        val names = listOf(town, standard, all).map { it.constellationNames().size }
        println("空の濃さ 画素 $counts / 星座名 $names")

        assertTrue("濃さを上げても画素が増えていない: $counts", counts[0] < counts[1] && counts[1] < counts[2])
        assertTrue("街の空で星座が全部消えている", names[0] > 0)
        assertTrue("濃さを上げても星座が増えていない: $names", names[0] <= names[1] && names[1] <= names[2])
        assertTrue("既定でスカスカ（星座が 2 個未満）: ${names[1]}", names[1] >= 2)
    }

    /**
     * **大三角は星の位置そのものに引く。**
     *
     * 星座絵と違って結びは HIP で星を指すので、**線の端が星の上に来る**。
     * 名前も出す（初心者が空で最初に見つけるのは星座名より大三角）。
     */
    @Test
    fun `大三角は星の位置に破線で引かれ、名前が出る`() {
        val catalog = catalog()
        val triangle = catalog.asterisms.first { it.nameJa == "冬の大三角" }
        val renderer = StarMapRenderer(catalog)
        // 3 つの星の重心を向く。冬の大三角が視野に収まる時刻を選んである
        val d = daysFromJ2000(epoch)
        val lst = localSiderealDeg(d, site.lonDeg)
        val positions = triangle.hips.map { hip ->
            val star = catalog.stars.first { it.hip == hip }
            toApparentAltAz(star.raDeg, star.decDeg, lst, site.latDeg)
        }
        val look = Look(positions.sumOf { it[0] } / 3.0, positions.sumOf { it[1] } / 3.0)

        fun render(guides: Boolean) = renderer.render(
            site = site,
            epochMillis = epoch,
            look = look,
            fovDeg = 60.0,
            limitMagnitude = 5.0,
            width = STAR_MAP_WIDTH,
            height = STAR_MAP_HEIGHT,
            drawAsterisms = guides,
        )

        val without = render(false)
        val with = render(true)
        println("冬の大三角: なし ${without.gray.count { it != 0.toByte() }} → あり ${with.gray.count { it != 0.toByte() }}")
        assertTrue("結びで画素が増えていない", with.gray.count { it != 0.toByte() } > without.gray.count { it != 0.toByte() })
        assertTrue(
            "結びの名前が出ていない: ${with.labels.map { it.text }}",
            with.labels.any { it.kind == LabelKind.ASTERISM && it.text == "冬の大三角" },
        )
        assertTrue("結びの名前だけになっている", with.labels.any { it.kind == LabelKind.CONSTELLATION })
    }

    /** 天の川は帯として敷く。**星座絵と同じ段**（星より暗い） */
    @Test
    fun `天の川は星より暗い段で敷かれる`() {
        val catalog = catalog()
        val renderer = StarMapRenderer(catalog)
        // **帯のある向きをデータから探す。** 時刻で決め打ちすると、その夜に地平線の下にある
        val lst = localSiderealDeg(daysFromJ2000(epoch), site.lonDeg)
        val onBand = catalog.milkyWay.flatten()
            .map { toApparentAltAz(it[0], it[1], lst, site.latDeg) }
            .firstOrNull { it[1] > 30.0 }
        assertTrue("天の川が空に出ていない時刻を選んでいる", onBand != null)
        val look = Look(onBand!![0], onBand[1])
        fun render(band: Boolean) = renderer.render(
            site = site,
            epochMillis = epoch,
            look = look,
            fovDeg = 35.0,
            limitMagnitude = 5.0,
            width = STAR_MAP_WIDTH,
            height = STAR_MAP_HEIGHT,
            drawFigureArt = false,
            drawAsterisms = false,
            drawMilkyWay = band,
        )
        val without = render(false).gray.count { it != 0.toByte() }
        val withBand = render(true)
        println("天の川: なし $without → あり ${withBand.gray.count { it != 0.toByte() }}")
        assertTrue("天の川で画素が増えていない", withBand.gray.count { it != 0.toByte() } > without)
        val levels = withBand.gray.map { (it.toInt() and 0xFF) ushr 5 }.toSet()
        assertTrue("天の川の段が見当たらない", 2 in levels)
    }

    /**
     * **星座絵は星座線の外接矩形に敷く。**
     *
     * 絵を持っている星座（`data/constellation-figures.json`）を視野に入れて、
     * **切ったときより画素が増える**ことと、**星より暗い段に収まっている**ことを見る。
     * 明るさが星と同じ段まで上がると、絵が主役になって星の位置が読めなくなる。
     */
    @Test
    fun `星座絵は星より暗い段で敷かれ、切ると消える`() {
        val catalog = catalog()
        assertTrue("星座絵のデータが読めていない", catalog.figures.isNotEmpty())
        val renderer = StarMapRenderer(catalog)
        // 絵を持っている星座のうち、この時刻に空へ出ているものへ向ける
        val target = renderer.visibleConstellations(site, epoch)
            .firstOrNull { aimed -> catalog.constellations.any { it.nameJa == aimed.nameJa && it.abbr in catalog.figures } }
        assertTrue("絵を持つ星座が空に無い", target != null)
        val look = Look(target!!.azDeg, target.altDeg)

        fun render(art: Boolean) = renderer.render(
            site = site,
            epochMillis = epoch,
            look = look,
            fovDeg = 35.0,
            limitMagnitude = 5.0,
            width = STAR_MAP_WIDTH,
            height = STAR_MAP_HEIGHT,
            drawFigureArt = art,
        )

        val without = render(false).gray.count { it != 0.toByte() }
        val withArt = render(true)
        val lit = withArt.gray.count { it != 0.toByte() }
        println("${target.nameJa}: 星座絵なし $without → あり $lit 画素")
        assertTrue("星座絵で画素が増えていない", lit > without)

        // 絵だけの段（3bit で 2）が、線や星の段を超えていないこと
        val levels = withArt.gray.map { (it.toInt() and 0xFF) ushr 5 }.toSet()
        assertTrue("星座絵の段が見当たらない", 2 in levels)
        assertTrue("線より明るい段に描いている", levels.max() >= 4)
    }

    /**
     * **主役は「見えているうち視野中心に近い星座」。**
     *
     * 重心で順位を付けていたときは、視野を横切っている大きな星座が、重心がたまたま近い
     * 小さな星座に負けた。グラスに出るラベルの順も、AI 解説の主役もこの順で決まるので、
     * 見ている人にとって近い「見えている部分」で測る。
     */
    @Test
    fun `視野を横切る星座が、重心の近い小さな星座より先に並ぶ`() {
        val lst = localSiderealDeg(daysFromJ2000(epoch), site.lonDeg)
        fun at(azDeg: Double, altDeg: Double): DoubleArray = toRaDec(azDeg, altDeg, lst, site.latDeg)

        val look = Look(180.0, 45.0)
        // 視線をそのまま通る長い星座線。重心は視線から 10° 以上離れる
        val across = Constellation("XCr", "よこぎり座", listOf(listOf(at(180.0, 45.0), at(210.0, 45.0))))
        // 視線から 7° ほどの小さな星座。重心も同じ場所にある
        val small = Constellation("XSm", "こつぶ座", listOf(listOf(at(190.0, 45.0), at(190.5, 45.0))))
        // 重心だけで測ると、先に入れた「こつぶ座」が勝ってしまう並び
        val renderer = StarMapRenderer(StarCatalog(emptyList(), listOf(small, across), emptyMap()))
        val map = renderer.render(
            site = site,
            epochMillis = epoch,
            look = look,
            fovDeg = 35.0,
            limitMagnitude = 5.0,
            width = STAR_MAP_WIDTH,
            height = STAR_MAP_HEIGHT,
        )
        assertEquals(listOf("よこぎり座", "こつぶ座"), map.constellationNames())
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
