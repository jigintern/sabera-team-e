package jp.jig.glasses.sample.kmp.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class SentenceAccumulatorTest {
    @Test
    fun `SSEの途中で分かれた文を句点までまとめる`() {
        val accumulator = SentenceAccumulator()

        assertEquals(emptyList<String>(), accumulator.append("明るい星を"))
        assertEquals(listOf("明るい星を探してください。"), accumulator.append("探してください。次は"))
        assertEquals(listOf("次は南です！"), accumulator.append("南です！"))
        assertEquals("", accumulator.flush())
    }

    @Test
    fun `正常終了なら句点のない末尾も返す`() {
        val accumulator = SentenceAccumulator()
        accumulator.append("これで解説を終わります")

        assertEquals("これで解説を終わります", accumulator.flush())
    }

    @Test
    fun `通信断なら不完全な末尾を捨てられる`() {
        val accumulator = SentenceAccumulator()
        accumulator.append("この光は途中で")

        accumulator.discard()
        assertEquals("", accumulator.flush())
    }
}
