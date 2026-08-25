package jp.jig.glasses.sample.kmp.sky

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CityCatalogTest {
    @Test
    fun `都市の座標とタイムゾーンが有効`() {
        assertEquals(18, CityCatalog.cities.size)
        assertEquals(CityCatalog.cities.size, CityCatalog.cities.map { it.id }.toSet().size)
        for (city in CityCatalog.cities) {
            assertTrue("${city.nameJa}の緯度", city.site.latDeg in -90.0..90.0)
            assertTrue("${city.nameJa}の経度", city.site.lonDeg in -180.0..180.0)
            assertTrue("${city.nameJa}の別名が無い", city.aliases.isNotEmpty())
            assertTrue("固定オフセットを使っている", '/' in city.zoneId.id)
        }
    }

    @Test
    fun `日本語と英語からシドニーを引ける`() {
        assertEquals("sydney", CityCatalog.findIn("シドニーの空")?.id)
        assertEquals("sydney", CityCatalog.findIn("show the sky in Sydney")?.id)
    }

    @Test
    fun `全角数字と空白を正規化する`() {
        assertEquals("20:30", CityCatalog.normalize(" ２０：３０ "))
    }
}
