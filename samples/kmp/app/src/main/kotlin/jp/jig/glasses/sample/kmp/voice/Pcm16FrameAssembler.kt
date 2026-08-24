package jp.jig.glasses.sample.kmp.voice

import java.io.EOFException

/**
 * 16bit PCMをAudioTrackへ渡せる2バイト境界へ揃える。
 *
 * HTTPのread境界はサンプル境界とは無関係で、奇数バイトになることがある。末尾1バイトを
 * 捨てると、それ以降の上位・下位バイトが反転して大音量ノイズになるため、次のreadへ持ち越す。
 */
internal class Pcm16FrameAssembler(
    private val onFrames: (ByteArray, Int) -> Unit,
) {
    private var pending: Byte? = null

    fun append(buffer: ByteArray, length: Int) {
        require(length in 0..buffer.size) { "PCMチャンク長が不正: $length/${buffer.size}" }
        if (length == 0) return

        val carry = pending
        val joined = ByteArray(length + if (carry == null) 0 else 1)
        var offset = 0
        if (carry != null) {
            joined[0] = carry
            offset = 1
        }
        buffer.copyInto(joined, destinationOffset = offset, endIndex = length)

        val alignedLength = joined.size - joined.size % BYTES_PER_FRAME
        if (alignedLength > 0) onFrames(joined, alignedLength)
        pending = if (alignedLength < joined.size) joined.last() else null
    }

    /** 1バイト残る応答は壊れたPCMなので、鳴らさずフォールバックさせる。 */
    fun finish() {
        if (pending != null) throw EOFException("AI音声が16bit PCMの途中で切れた")
    }

    companion object {
        const val BYTES_PER_FRAME = 2

        fun requireValid(pcm: ByteArray) {
            require(pcm.isNotEmpty()) { "AI音声が空" }
            require(pcm.size % BYTES_PER_FRAME == 0) {
                "AI音声が16bit境界ではない: ${pcm.size} bytes"
            }
        }
    }
}
