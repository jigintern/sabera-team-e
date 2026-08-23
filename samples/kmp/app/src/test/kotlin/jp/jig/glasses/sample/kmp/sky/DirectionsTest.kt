package jp.jig.glasses.sample.kmp.sky

import org.junit.Assert.assertEquals
import org.junit.Test

class DirectionsTest {
    @Test
    fun `方位は360度をまたいで正規化される`() {
        assertEquals("北", cardinalDirection16(0.0))
        assertEquals("北北西", cardinalDirection16(-22.5))
        assertEquals("北東", cardinalDirection8(405.0))
    }
}
