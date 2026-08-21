package jp.jig.glasses.sample.kmp.glass

import org.junit.Assert.assertEquals
import org.junit.Test

class GlassBrightnessTest {
    @Test
    fun `ファームへ送る明るさを5段階に制限する`() {
        assertEquals(0, GlassBrightness.normalize(-1))
        assertEquals(2, GlassBrightness.normalize(2))
        assertEquals(4, GlassBrightness.normalize(5))
    }

    @Test
    fun `段階表示は範囲外でも正規化される`() {
        assertEquals("最も暗い", GlassBrightness.label(-1))
        assertEquals("標準", GlassBrightness.label(2))
        assertEquals("最も明るい", GlassBrightness.label(9))
    }
}
