package jp.jig.glasses.sample.kmp.glass

import jp.jig.glasses.sample.kmp.catalog.StarCatalog
import jp.jig.glasses.sample.kmp.sky.Look
import jp.jig.glasses.sample.kmp.sky.Site
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * タイムラプスの窓が「チカチカしない速さ」に収まっているかの検算。
 *
 * **転送時間の下限は面積 ÷ 32**（RLE の連長上限が 32 なので真っ黒でもかかる）。
 * ここが崩れると、精度をどれだけ落としても点滅にしかならない。
 */
class TimelapseSenderTest {

    private val site = Site(35.9432, 136.1846)
    private val epoch = 1767268800000L

    // テストの作業ディレクトリはモジュール直下なので、data/ が見つかるまで遡る
    private val dataDir: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "data") }
        .first { File(it, "stars.json").exists() }

    private fun catalog(): StarCatalog = StarCatalog.parse { name ->
        File(dataDir, name).readText()
    }

    @Test
    fun `窓2枚と星図はバッファに同居できない`() {
        // ここが同居できるなら星図を消さずに済むが、実際は超える。
        // だから「流している間は星図を消す」は好みではなく容量の必然
        val starMap = STAR_MAP_WIDTH * STAR_MAP_HEIGHT * 2
        assertTrue(
            "星図 $starMap + 窓 ${timelapseBufferUsageBytes()}",
            starMap + timelapseBufferUsageBytes() > CANVAS_IMAGE_BUFFER_BYTES,
        )
    }

    @Test
    fun `窓2枚だけならバッファに収まる`() {
        assertTrue(timelapseBufferUsageBytes() <= CANVAS_IMAGE_BUFFER_BYTES)
    }

    @Test
    fun `窓は全画面より一桁近く速い`() {
        val renderer = StarMapRenderer(catalog())
        fun frameAt(width: Int, height: Int, limit: Double) = renderer.render(
            site = site,
            epochMillis = epoch,
            look = Look(180.0, 45.0),
            fovDeg = 35.0,
            limitMagnitude = limit,
            width = width,
            height = height,
            maxLabels = 0,
            drawFigures = false,
            drawFigureArt = false,
            drawAsterisms = false,
            drawMilkyWay = false,
        )

        val window = frameAt(
            TimelapseWindow.WIDTH,
            TimelapseWindow.HEIGHT,
            TimelapseWindow.LIMIT_MAGNITUDE,
        ).transferMillis()
        val full = frameAt(STAR_MAP_WIDTH, STAR_MAP_HEIGHT, 5.0).transferMillis()

        println("窓 ${window}ms / 全画面 ${full}ms")
        // 10fps（100ms）を切れないと「流れている」には見えない
        assertTrue("窓が ${window}ms かかる", window <= 100)
        assertTrue("全画面 ${full}ms に対して窓 ${window}ms", full > window * 3)
    }

    @Test
    fun `星を減らしても背景ぶんは減らない`() {
        // 「精度を落とせば滑らかになる」が成り立たないことの検算。
        // 全画面は星を 1 個も描かなくても面積 ÷ 32 ぶんかかる
        val renderer = StarMapRenderer(catalog())
        val empty = renderer.render(
            site = site,
            epochMillis = epoch,
            look = Look(180.0, 45.0),
            fovDeg = 35.0,
            limitMagnitude = -2.0,
            width = STAR_MAP_WIDTH,
            height = STAR_MAP_HEIGHT,
            maxLabels = 0,
            drawStars = false,
            drawLines = false,
            drawFigures = false,
            drawFigureArt = false,
            drawAsterisms = false,
            drawMilkyWay = false,
        )
        val floorBytes = STAR_MAP_WIDTH * STAR_MAP_HEIGHT / 32
        println("全画面の下限 ${empty.compressedSizeBytes()}B（面積÷32 = ${floorBytes}B）")
        assertTrue(empty.compressedSizeBytes() >= floorBytes)
        // 何も描かない全画面ですら 10fps に届かない
        assertTrue("下限でも ${empty.transferMillis()}ms", empty.transferMillis() > 100)
    }
}
