package jp.jig.glasses.sample.kmp.support

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ResultMetadataType
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * 台本を QR で運ぶ。**文字ではなく生バイトを運ぶ。**
 *
 * `guide/GuideCodec` が圧縮したバイト列をそのまま焼く。Base64 を挟むと 4 割太り、
 * 1 枚に入る段数が 8〜9 段から 5 段まで落ちる。
 *
 * バイトモードにするために **ISO-8859-1 を経由する**。この文字集合は 0〜255 を
 * 1 バイトへ 1 対 1 で写すので、ZXing は「文字列」として受け取ったものを
 * **そのままのバイト列**として焼く。読むときは焼かれたバイト列
 * （[ResultMetadataType.BYTE_SEGMENTS]）を直に取り出す。
 */
object QrCode {

    /**
     * 誤り訂正は **L（7%）**。
     *
     * 段数を稼ぐためで、`GuideCodec.QR_CAPACITY_BYTES`（2,953）はこの水準の値。
     * 上げると訂正は強くなるが、**入る段数が目に見えて減る**（M で 2,331・Q で 1,663）。
     * 画面に出したものをその場で読ませる使い方なので、汚れや破れは想定しない。
     */
    private val ERROR_CORRECTION = ErrorCorrectionLevel.L

    /** 白い余白。**無いと読めない**（QR の仕様で 4 モジュール以上） */
    private const val QUIET_ZONE_MODULES = 4

    /** バイトを 1 対 1 で写せる唯一の実用的な文字集合 */
    private const val BYTE_CHARSET = "ISO-8859-1"

    /** 台本のバイト列を QR の画像にする。入らなければ null */
    fun encode(bytes: ByteArray, sizePx: Int): Bitmap? = runCatching {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ERROR_CORRECTION,
            EncodeHintType.CHARACTER_SET to BYTE_CHARSET,
            EncodeHintType.MARGIN to QUIET_ZONE_MODULES,
        )
        val matrix = QRCodeWriter().encode(bytes.asLatin1(), BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val width = matrix.width
        val height = matrix.height
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                pixels[row + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
            }
        }
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            .also { it.setPixels(pixels, 0, width, 0, 0, width, height) }
    }.getOrNull()

    /**
     * カメラ 1 コマ（輝度だけ）から QR のバイト列を取り出す。見つからなければ null。
     *
     * [luminance] は YUV の Y 平面をそのまま渡す。**色は要らない**ので、
     * ビットマップへ起こさずに済む（1 コマごとに確保すると詰まる）。
     */
    fun decode(luminance: ByteArray, width: Int, height: Int): ByteArray? = runCatching {
        val source = PlanarYUVLuminanceSource(luminance, width, height, 0, 0, width, height, false)
        val hints = mapOf(DecodeHintType.CHARACTER_SET to BYTE_CHARSET)
        val result = MultiFormatReader().apply { setHints(hints) }
            .decodeWithState(BinaryBitmap(HybridBinarizer(source)))
        // **焼かれたバイト列そのものを取り出す。** text から起こし直すと、
        // ZXing が文字集合を推定した時点でバイトが変わっていることがある
        @Suppress("UNCHECKED_CAST")
        val segments = result.resultMetadata?.get(ResultMetadataType.BYTE_SEGMENTS) as? List<ByteArray>
        segments?.takeIf { it.isNotEmpty() }?.let { parts ->
            val out = java.io.ByteArrayOutputStream()
            for (part in parts) out.write(part)
            out.toByteArray()
        } ?: result.text?.toByteArray(charset(BYTE_CHARSET))
    }.getOrNull()

    private fun ByteArray.asLatin1(): String {
        val chars = CharArray(size) { (this[it].toInt() and 0xFF).toChar() }
        return String(chars)
    }
}
