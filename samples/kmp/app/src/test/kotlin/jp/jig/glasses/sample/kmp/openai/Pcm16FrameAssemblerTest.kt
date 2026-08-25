package jp.jig.glasses.sample.kmp.openai

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.EOFException

class Pcm16FrameAssemblerTest {
    @Test
    fun `奇数長のHTTPチャンクでも16bitサンプルを一切捨てない`() {
        val source = ByteArray(128) { it.toByte() }
        val output = ByteArrayOutputStream()
        val emittedLengths = ArrayList<Int>()
        val assembler = Pcm16FrameAssembler { buffer, length ->
            emittedLengths += length
            output.write(buffer, 0, length)
        }

        var offset = 0
        val chunkSizes = intArrayOf(1, 3, 5, 7, 9, 11, 13, 17, 19, 23, 20)
        for (wanted in chunkSizes) {
            if (offset >= source.size) break
            val length = minOf(wanted, source.size - offset)
            assembler.append(source.copyOfRange(offset, offset + length), length)
            offset += length
        }
        if (offset < source.size) {
            val tail = source.copyOfRange(offset, source.size)
            assembler.append(tail, tail.size)
        }
        assembler.finish()

        assertArrayEquals(source, output.toByteArray())
        assertTrue(emittedLengths.all { it > 0 && it % 2 == 0 })
    }

    @Test
    fun `最後に1バイト残る壊れたPCMを拒否する`() {
        val assembler = Pcm16FrameAssembler { _, _ -> }
        assembler.append(byteArrayOf(1, 2, 3), 3)

        val error = runCatching { assembler.finish() }.exceptionOrNull()

        assertTrue("EOFExceptionではない: $error", error is EOFException)
    }

    @Test
    fun `空または奇数長の完成PCMを拒否する`() {
        assertTrue(runCatching { Pcm16FrameAssembler.requireValid(byteArrayOf()) }.isFailure)
        assertTrue(runCatching { Pcm16FrameAssembler.requireValid(byteArrayOf(1)) }.isFailure)
        Pcm16FrameAssembler.requireValid(byteArrayOf(1, 2))
        assertEquals(2, Pcm16FrameAssembler.BYTES_PER_FRAME)
    }
}
