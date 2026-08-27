package jp.jig.glasses.sample.kmp.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LogoAssetTest {

    /** テストの作業ディレクトリは app/。別の場所から実行してもリポジトリを見つける。 */
    private val repoRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "samples/kmp/app").isDirectory }

    @Test
    fun `ロゴ素材は透過の横長画像`() {
        val image = pngHeader(
            File(repoRoot, "samples/kmp/app/src/main/res/drawable-nodpi/hoshishirube_logo.png"),
        )

        assertEquals(920, image.width)
        assertEquals(422, image.height)
        assertTrue("透過チャンネルが無い", image.hasAlpha)
    }

    @Test
    fun `印は正方形で透過していて、余白を持たない`() {
        val file = File(repoRoot, "samples/kmp/app/src/main/res/drawable-nodpi/hoshishirube_mark.png")
        val image = pngHeader(file)

        assertEquals(432, image.width)
        assertEquals(432, image.height)
        assertTrue("透過チャンネルが無い", image.hasAlpha)
    }

    /**
     * **ランチャーだけ別素材にしてある。** 印に安全域ぶんの余白を焼き込むと、
     * 星図のバーへ置いたときに dp の 6 割しか絵が出ず、指定より小さく見える。
     */
    @Test
    fun `ランチャーの前景は安全域の内側に収まる`() {
        val image = pngHeader(
            File(repoRoot, "samples/kmp/app/src/main/res/drawable-nodpi/ic_launcher_foreground.png"),
        )

        assertEquals(432, image.width)
        assertEquals(432, image.height)
        assertTrue("透過チャンネルが無い", image.hasAlpha)
    }

    /** Android の JVM テストには ImageIO が無いので、PNG の IHDR だけを読む。 */
    private fun pngHeader(file: File): PngHeader {
        val bytes = file.readBytes()
        assertTrue("PNG ではない", bytes.take(PNG_SIGNATURE.size) == PNG_SIGNATURE)
        val width = bytes.intAt(16)
        val height = bytes.intAt(20)
        val colorType = bytes[25].toInt() and 0xFF
        return PngHeader(width, height, colorType == 4 || colorType == 6)
    }

    private fun ByteArray.intAt(offset: Int): Int =
        ((this[offset].toInt() and 0xFF) shl 24) or
            ((this[offset + 1].toInt() and 0xFF) shl 16) or
            ((this[offset + 2].toInt() and 0xFF) shl 8) or
            (this[offset + 3].toInt() and 0xFF)

    private data class PngHeader(val width: Int, val height: Int, val hasAlpha: Boolean)

    private companion object {
        val PNG_SIGNATURE = listOf(137, 80, 78, 71, 13, 10, 26, 10).map(Int::toByte)
    }
}
