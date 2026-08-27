package jp.jig.glasses.sample.kmp.alignment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TiltGaugeTest {
    @Test
    fun `差が0なら水平線は中心に来る`() {
        val geometry = tiltGaugeGeometry(0.0)

        assertEquals(0f, geometry.horizonOffset, 1e-6f)
        assertFalse(geometry.pegged)
        assertTrue(geometry.within)
    }

    @Test
    fun `スマホが上を向いていると水平線は下に出る`() {
        // 見たまま手を動かせば 0 に近づく向き。**ここが逆だと直すたびに悪くなる**
        assertTrue(tiltGaugeGeometry(5.0).horizonOffset > 0f)
        assertTrue(tiltGaugeGeometry(-5.0).horizonOffset < 0f)
    }

    @Test
    fun `負の側も符号だけが違う`() {
        assertEquals(
            -tiltGaugeGeometry(5.0).horizonOffset,
            tiltGaugeGeometry(-5.0).horizonOffset,
            1e-6f,
        )
    }

    @Test
    fun `許容の3度は端まで行かない`() {
        val geometry = tiltGaugeGeometry(3.0)

        assertEquals(0.2f, geometry.horizonOffset, 1e-6f)
        assertFalse(geometry.pegged)
        assertTrue(geometry.within)
    }

    @Test
    fun `3度を超えたら許容から外れる`() {
        assertFalse(tiltGaugeGeometry(3.1).within)
        assertFalse(tiltGaugeGeometry(-3.1).within)
    }

    @Test
    fun `振り切れても端で止まる`() {
        val geometry = tiltGaugeGeometry(40.0)

        assertEquals(1f, geometry.horizonOffset, 1e-6f)
        assertTrue(geometry.pegged)
        assertEquals(-1f, tiltGaugeGeometry(-40.0).horizonOffset, 1e-6f)
    }
}
