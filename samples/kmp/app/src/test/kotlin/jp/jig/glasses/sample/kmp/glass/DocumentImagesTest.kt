package jp.jig.glasses.sample.kmp.glass

import app.jigglass.glass.CommandManager
import jp.jig.glasses.sample.kmp.catalog.StarCatalog
import jp.jig.glasses.sample.kmp.sky.Basis
import jp.jig.glasses.sample.kmp.sky.Look
import jp.jig.glasses.sample.kmp.sky.ObservationDefaults
import jp.jig.glasses.sample.kmp.sky.Site
import jp.jig.glasses.sample.kmp.sky.SkyDensity
import jp.jig.glasses.sample.kmp.sky.daysFromJ2000
import jp.jig.glasses.sample.kmp.sky.enu
import jp.jig.glasses.sample.kmp.sky.localSiderealDeg
import jp.jig.glasses.sample.kmp.sky.precess
import jp.jig.glasses.sample.kmp.sky.project
import jp.jig.glasses.sample.kmp.sky.projectionScale
import jp.jig.glasses.sample.kmp.sky.Vec3
import jp.jig.glasses.sample.kmp.sky.toApparentAltAz
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Base64
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * ドキュメントに載せる「グラスに出ている絵」の**中身**を書き出す。
 *
 * **アプリが送るのと同じ経路で作る**（[StarMapRenderer] → 3bit の緑、[GlassTextPage] → テキスト枠）
 * ので、見本と実物がずれない。絵にするのは `tools/compose-glass-images.java`
 * （文字を焼くには AWT が要るが、Android のユニットテストからは触れない）。
 *
 * 重ねる先の空も**同じ星表・同じ向き**から描いて一緒に書き出す（`sky`）。
 * **これは写真ではない。** パネルのにじみ・明るさ・実機のフォントは出ない。
 * グラスに画面キャプチャの口は無く、スマホ側も BLE がつながらないと星図まで進まないので、
 * 「実物の見え方」が要るときは実機で撮る。
 */
class DocumentImagesTest {

    /** かけている人の視界（**広さは見た目で置いた値**。実測はしていない） */
    private val SCENE_WIDTH = 880
    private val SCENE_HEIGHT = 550
    private val SCENE_FOV_DEG = 92.0

    /** パネルは正面より少し上に浮いている */
    private val PANEL_ABOVE_GAZE_DEG = 11.0

    /** テストの作業ディレクトリはモジュール直下なので、data/ が見つかるまで遡る */
    private val repoRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "data/stars.json").exists() }

    @Test
    fun `グラスの星図と解説画面を書き出す`() {
        val catalog = StarCatalog.parse { File(repoRoot, "data/$it").readText() }
        val density = SkyDensity.default

        // 2026-01-01 の夜、鯖江からオリオン座を見た向き。
        // **高度 15° あたりに来る時刻を選ぶ**のは、街の明かりと地平線を絵に入れるため
        // （見上げた空だけだと、何と重なっているのかが伝わらない）。
        val renderer = StarMapRenderer(catalog)
        val site = ObservationDefaults.site
        var epoch = 0L
        var target = renderer.visibleConstellations(site, 1767268800000L).first()
        for (step in 0..36) {
            val candidate = 1767258000000L + step * 600_000L // 2026-01-01 18:00 JST から 10 分ごと
            val orion = renderer.visibleConstellations(site, candidate)
                .firstOrNull { it.nameJa == "オリオン座" } ?: continue
            if (epoch == 0L || abs(orion.altDeg - 15.0) < abs(target.altDeg - 15.0)) {
                epoch = candidate
                target = orion
            }
        }
        // パネルは星座を正面に見る向き。**視界の正面はもっと下**（[PANEL_ABOVE_GAZE_DEG]）なので、
        // 街と地平線は絵の下のほうに入る
        val look = Look(azDeg = target.azDeg, altDeg = target.altDeg)
        val map = renderer.render(
            site = site,
            epochMillis = epoch,
            look = look,
            fovDeg = ObservationDefaults.STAR_MAP_FOV_DEG,
            limitMagnitude = density.limitMagnitude,
            width = STAR_MAP_WIDTH,
            height = STAR_MAP_HEIGHT,
            constellationMagnitude = density.constellationMagnitude,
        )

        // 解説画面は 1 枚目（**見出しに星座名と方角が出ている状態**）。
        // 主役は**グラスに出したラベルの先頭**から取る ― アプリと同じ決め方にしておく（#37）
        val headline = map.constellationNames().firstOrNull()?.let { name ->
            renderer.visibleConstellations(site, epoch).firstOrNull { it.nameJa == name }
        } ?: target
        val pages = GlassTextPage.pages("${headline.nameJa} ${headline.where}", lore(headline.nameJa))
        val caption = pages.first()

        val out = StringBuilder()
        out.append(scene(catalog, site, epoch, look))
        out.appendScreen("glass-star-map", map.width, map.height, map.gray, map.toCanvasElements())
        out.appendScreen("glass-caption", 0, 0, null, caption.elements)

        val file = File(repoRoot, "samples/kmp/app/build/doc-images/panels.txt")
        file.parentFile.mkdirs()
        file.writeText(out.toString())
        val shownAt = java.time.Instant.ofEpochMilli(epoch).atZone(java.time.ZoneId.of("Asia/Tokyo"))
        println("${file.path}: $shownAt の ${headline.nameJa}（${headline.where}）のラベル ${map.labels.map { it.text }}、解説は ${pages.size} 枚")

        assertTrue("星図が真っ黒", map.gray.any { (it.toInt() and 0xFF) > 0 })
        assertTrue("解説画面が空", caption.elements.any { it.text.isNotEmpty() })
    }

    /**
     * 1 画面ぶんを行区切りで書く。**読む側（JDK 単体実行）に JSON を持ち込まないための形式。**
     *
     * 視界（先に 1 回）: `scene <幅> <高さ>` / `horizon <y>` / `panel <四隅>` / `star <x> <y> <等級>`
     *
     * 画面ごと: `screen <名前> <幅> <高さ>` / `pixels <base64>` / `label <x> <y> <幅> <高さ> <文字>`
     */
    private fun StringBuilder.appendScreen(
        name: String,
        width: Int,
        height: Int,
        gray: ByteArray?,
        texts: List<CommandManager.CanvasElement>,
    ) {
        appendLine("screen $name $width $height")
        if (gray != null) appendLine("pixels ${Base64.getEncoder().encodeToString(gray)}")
        for (element in texts) {
            if (element.text.isEmpty()) continue
            appendLine("label ${element.x} ${element.y} ${element.width} ${element.height} ${element.text}")
        }
    }

    /**
     * 重ねる先の**視界**。星の位置と等級、地平線、そして**パネルが視界のどこを占めるか**を書き出す。
     *
     * **パネルは視野全体ではない。** かけている人には、正面より少し上に小さな画面が浮かんで見えて、
     * その外側は素通しの空と街になる。だから絵も、広い視界（[SCENE_FOV_DEG]）の中に
     * パネルの矩形を置く形で作る。
     *
     * 星は**星図と同じ投影**（[project] / [projectionScale]）で置くので、
     * パネルの中の星座線と、外に見えている空の星がつながって見える。
     *
     * **画角は未実測**（`ObservationDefaults.STAR_MAP_FOV_DEG` が仮の 35°）なので、
     * パネルの大きさもそのぶん仮。視界の広さと、パネルが正面より上にある量も見た目で置いている。
     */
    private fun scene(catalog: StarCatalog, site: Site, epochMillis: Long, panelLook: Look): String {
        val days = daysFromJ2000(epochMillis)
        val lst = localSiderealDeg(days, site.lonDeg)
        // 見ている正面はパネルより下（パネルは視界の中央より上に浮いている）
        val basis = Basis(panelLook.azDeg, panelLook.altDeg - PANEL_ABOVE_GAZE_DEG)
        val k = projectionScale(SCENE_WIDTH, SCENE_FOV_DEG)

        val out = StringBuilder()
        out.appendLine("scene $SCENE_WIDTH $SCENE_HEIGHT")
        project(enu(panelLook.azDeg, 0.0), basis, k, SCENE_WIDTH, SCENE_HEIGHT)?.let {
            out.appendLine("horizon ${"%.1f".format(it[1])}")
        }

        // パネルの四隅が視界のどこに来るか。パネル座標 → 向き → 視界の座標
        val panelBasis = Basis(panelLook.azDeg, panelLook.altDeg)
        val panelK = projectionScale(STAR_MAP_WIDTH, ObservationDefaults.STAR_MAP_FOV_DEG)
        val corners = listOf(0 to 0, PANEL_WIDTH to 0, PANEL_WIDTH to PANEL_HEIGHT, 0 to PANEL_HEIGHT)
            .mapNotNull { (x, y) -> project(panelDirection(panelBasis, panelK, x, y), basis, k, SCENE_WIDTH, SCENE_HEIGHT) }
        if (corners.size == 4) {
            out.appendLine("panel " + corners.joinToString(" ") { "%.1f %.1f".format(it[0], it[1]) })
        }

        for (star in catalog.stars) {
            val position = precess(star.raDeg, star.decDeg, days)
            val altAz = toApparentAltAz(position[0], position[1], lst, site.latDeg)
            if (altAz[1] < 0.0) continue
            val point = project(enu(altAz[0], altAz[1]), basis, k, SCENE_WIDTH, SCENE_HEIGHT) ?: continue
            if (point[0] < -20 || point[0] > SCENE_WIDTH + 20) continue
            if (point[1] < -20 || point[1] > SCENE_HEIGHT + 20) continue
            out.appendLine("star ${"%.1f".format(point[0])} ${"%.1f".format(point[1])} ${"%.2f".format(star.magnitude)}")
        }
        return out.toString()
    }

    /**
     * パネルの 1 点が向いている方向。
     *
     * ステレオ投影の逆をかける（画面の中心からの距離 r が、視線からの角度 2·atan(r / 2k) になる）。
     */
    private fun panelDirection(basis: Basis, k: Double, x: Int, y: Int): Vec3 {
        val dx = x - PANEL_WIDTH / 2.0
        val dy = PANEL_HEIGHT / 2.0 - y
        val r = hypot(dx, dy)
        if (r < 1e-9) return basis.forward
        val theta = 2.0 * atan(r / (2.0 * k))
        val side = basis.right * (dx / r) + basis.up * (dy / r)
        return (basis.forward * cos(theta) + side * sin(theta)).normalized()
    }

    /** 88 星座ぶんの解説文は `data/` の生成物。**アプリと同じ文を出す** */
    private fun lore(nameJa: String): String {
        val arr = JSONObject(File(repoRoot, "data/constellation-lore.json").readText()).getJSONArray("lore")
        for (i in 0 until arr.length()) {
            val row = arr.getJSONObject(i)
            if (row.getString("nameJa") == nameJa) return row.getString("text")
        }
        error("$nameJa の解説文が無い")
    }
}
