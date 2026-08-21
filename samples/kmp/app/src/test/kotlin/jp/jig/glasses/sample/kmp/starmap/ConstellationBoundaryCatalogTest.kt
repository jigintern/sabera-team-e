package jp.jig.glasses.sample.kmp.starmap

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class ConstellationBoundaryCatalogTest {
    private val dataFile: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "data/constellation-boundaries.json") }
        .first { it.exists() }

    private fun catalog(): ConstellationBoundaryCatalog {
        val json = JSONObject(dataFile.readText())
        val rows = json.getJSONArray("boundaries")
        val boundaries = buildList {
            for (index in 0 until rows.length()) {
                val row = rows.getJSONArray(index)
                add(ConstellationBoundary(row.getDouble(0), row.getDouble(1), row.getDouble(2), row.getString(3)))
            }
        }
        val namesJson = json.getJSONObject("namesJa")
        val names = namesJson.keys().asSequence().associateWith(namesJson::getString)
        return ConstellationBoundaryCatalog(boundaries, names)
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
}
