package jp.jig.glasses.sample.kmp.glass

import jp.jig.glasses.sample.kmp.catalog.StarCatalog
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 解説画面の本文の下に置く星座絵（#127）。
 *
 * **実機でしか分からないのは「屋外で絵が見えるか」だけ。** 88 星座ぶんが
 * バッファに入るか・枠から溢れないか・つぶれて線 1 本になっていないかは、ここで押さえられる。
 * **枠は本文の下端から下**なので、字と重なるかどうかは計算で決まる（最後のテスト）。
 */
class ExplanationArtTest {

    // テストの作業ディレクトリはモジュール直下なので、data/ が見つかるまで遡る
    private val dataDir: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "data") }
        .first { File(it, "stars.json").exists() }

    private val catalog: StarCatalog = StarCatalog.parse { name -> File(dataDir, name).readText() }
    private val renderer = StarMapRenderer(catalog)

    private val value = StarMapInk().value(StarMapLayer.ART)

    private fun art(nameJa: String): StarMap? =
        renderer.explanationArt(nameJa, EXPLANATION_ART_WIDTH, EXPLANATION_ART_HEIGHT, value)

    /** 点灯している画素の外接矩形。無ければ null */
    private fun StarMap.litBounds(): IntArray? {
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var maxY = Int.MIN_VALUE
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (gray[y * width + x] == 0.toByte()) continue
                if (x < minX) minX = x
                if (y < minY) minY = y
                if (x > maxX) maxX = x
                if (y > maxY) maxY = y
            }
        }
        return if (minX > maxX) null else intArrayOf(minX, minY, maxX, maxY)
    }

    @Test
    fun `88星座すべてが画像バッファに収まる`() {
        for (constellation in catalog.constellations) {
            val map = art(constellation.nameJa)
            assertNotNull("${constellation.nameJa}の絵が焼けない", map)
            val used = map!!.canvasBufferUsageBytes()
            assertTrue(
                "${constellation.nameJa} が $used バイトでバッファを超える",
                used <= CANVAS_IMAGE_BUFFER_BYTES,
            )
        }
    }

    @Test
    fun `絵は余白の内側に収まる`() {
        for (constellation in catalog.constellations) {
            val map = art(constellation.nameJa) ?: continue
            val bounds = map.litBounds()
            assertNotNull("${constellation.nameJa}の絵が空", bounds)
            // 線の丸めで 1 画素はみ出しうるので、余白から 1 画素だけ許す
            val slack = EXPLANATION_ART_MARGIN_PX - 1
            val (minX, minY, maxX, maxY) = bounds!!
            assertTrue(
                "${constellation.nameJa}の絵が余白へ出た（$minX,$minY,$maxX,$maxY）",
                minX >= slack && minY >= slack &&
                    maxX <= map.width - slack && maxY <= map.height - slack,
            )
        }
    }

    /**
     * **赤経 0/360 をまたぐ星座がつぶれない。**
     *
     * 基準点で開かずに焼くと、うお座は「359° と 1°」で幅 358° の絵になり、
     * 枠へ収めた結果が**横に伸びた線 1 本**になる（縦の充填率が 2% まで落ちる）。
     */
    @Test
    fun `赤経をまたぐ星座も縦横どちらもつぶれない`() {
        for (constellation in catalog.constellations) {
            val map = art(constellation.nameJa) ?: continue
            val bounds = map.litBounds() ?: continue
            val filledX = (bounds[2] - bounds[0]).toDouble() / (map.width - 2 * EXPLANATION_ART_MARGIN_PX)
            val filledY = (bounds[3] - bounds[1]).toDouble() / (map.height - 2 * EXPLANATION_ART_MARGIN_PX)
            // いちばん細長いとかげ座でも 0.23 は埋まる（88 星座で数えた下限）
            assertTrue(
                "${constellation.nameJa}がつぶれた（横${filledX}・縦${filledY}）",
                filledX >= 0.2 && filledY >= 0.2,
            )
        }
    }

    /**
     * **枠へ引き伸ばさない。**
     *
     * 正方形の枠へ縦横別々に合わせると、空で見える形と違う形の絵になる。
     * 拡大率は縦横の小さいほうで決まるので、**片方は枠に届き、もう片方は余る**。
     */
    @Test
    fun `正方形の枠へ入れても縦横比を崩さない`() {
        val side = 400
        val map = renderer.explanationArt("オリオン座", side, side, value)!!
        val bounds = map.litBounds()!!
        val box = side - 2 * EXPLANATION_ART_MARGIN_PX
        val filledX = (bounds[2] - bounds[0]).toDouble() / box
        val filledY = (bounds[3] - bounds[1]).toDouble() / box
        // 線の丸めぶん 2 画素だけ許す
        assertTrue("枠のどちらにも届いていない（横$filledX・縦$filledY）", maxOf(filledX, filledY) >= 1.0 - 2.0 / box)
        assertTrue("正方形の枠へ引き伸ばした（横$filledX・縦$filledY）", minOf(filledX, filledY) < 0.97)
    }

    /** **絵が無くても解説は出る。** 人工衛星の機体名や声の質問では名前を引き当てられない */
    @Test
    fun `星座でない名前では絵を作らない`() {
        assertNull(art("ISS"))
        assertNull(art("スターリンク"))
        assertNull(art(""))
    }

    /**
     * **枠が本文の 1 行にも重ならない。**
     *
     * 重ねてよいことにすると、3bit 8 階調のパネルでは
     * 「絵が見えないほど暗くする」か「字が読めない」かのどちらかにしかならない。
     * 行を動かしたときにここが落ちるよう、**枠は本文の下端から計算している**。
     */
    @Test
    fun `枠は本文の下端より下にある`() {
        assertTrue(
            "星座絵の枠が本文に重なる（本文の下端 ${GlassTextPage.bodyBottomY}px）",
            EXPLANATION_ART_TOP_PX >= GlassTextPage.bodyBottomY,
        )
        assertTrue(
            "星座絵の枠がパネルからはみ出す",
            EXPLANATION_ART_TOP_PX + EXPLANATION_ART_HEIGHT <= PANEL_HEIGHT,
        )
        assertTrue("星座絵の枠に高さが無い", EXPLANATION_ART_HEIGHT > 2 * EXPLANATION_ART_MARGIN_PX)
        assertTrue("星座絵の枠がパネルより広い", EXPLANATION_ART_WIDTH <= PANEL_WIDTH)
    }
}
