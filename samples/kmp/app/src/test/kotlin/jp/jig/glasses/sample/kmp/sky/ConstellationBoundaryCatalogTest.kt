package jp.jig.glasses.sample.kmp.sky

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class ConstellationBoundaryCatalogTest {
    private val dataFile: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "data/constellation-boundaries.json") }
        .first { it.exists() }

    private val json = JSONObject(dataFile.readText())

    private fun boundaries(): List<ConstellationBoundary> {
        val rows = json.getJSONArray("boundaries")
        return buildList {
            for (index in 0 until rows.length()) {
                val row = rows.getJSONArray(index)
                add(ConstellationBoundary(row.getDouble(0), row.getDouble(1), row.getDouble(2), row.getString(3)))
            }
        }
    }

    private fun catalog(): ConstellationBoundaryCatalog {
        val namesJson = json.getJSONObject("namesJa")
        val names = namesJson.keys().asSequence().associateWith(namesJson::getString)
        return ConstellationBoundaryCatalog(boundaries(), names)
    }

    @Test
    fun `Roman論文の例と同じ星座を返す`() {
        val catalog = catalog()
        assertEquals("みなみのかんむり座", catalog.nameAtB1875(19.0 * 15.0, -40.0))
        assertEquals("テーブルさん座", catalog.nameAtB1875(6.2222 * 15.0, -81.1234))
    }

    @Test
    fun `北極と南極も一意に決まる`() {
        val catalog = catalog()
        assertEquals("こぐま座", catalog.nameAtB1875(0.0, 90.0))
        assertEquals("はちぶんぎ座", catalog.nameAtB1875(123.0, -90.0))
    }

    /**
     * **表の外は例外ではなく null。** 星図を描いている最中に落ちる価値が無いので、
     * 呼び出し側（境界表を同梱していないときの null）と同じ扱いに合流させる。
     */
    @Test
    fun `表の外は例外を投げず null を返す`() {
        val catalog = catalog()
        // 上限は開いている（`dec >= decLow` だけを見る）ので、外れるのは南極より下だけ
        assertNull(catalog.nameAtB1875(10.0, -90.5))
    }

    /**
     * **天球に隙間が無いことを表そのもので押さえる。** 該当行が変わるのは decLow の境目だけなので、
     * すべての decLow とその直上で赤経 0〜24h が覆われていれば、どの視線でも星座名が決まる
     * （生成し直した表に穴が空いたら、ここが最初に気づく）。
     */
    @Test
    fun `どの赤緯でも赤経を覆っていて名前が決まる`() {
        val catalog = catalog()
        val decLows = boundaries().map { it.decLowDeg }.distinct().sorted()
        for (decLow in decLows) {
            for (dec in listOf(decLow, decLow + 0.001)) {
                if (dec > 90.0) continue
                for (step in 0 until 240) {
                    val raDeg = step * 0.1 * 15.0
                    assertNotNull("赤緯 $dec°・赤経 $raDeg° で星座が決まらない", catalog.nameAtB1875(raDeg, dec))
                }
            }
        }
    }
}
