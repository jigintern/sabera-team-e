package jp.jig.glasses.sample.kmp.glass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StarMapGuidanceTest {
    @Test
    fun `案内中は周囲の星座名と結びの名前を隠す`() {
        val map = StarMap(
            width = 100,
            height = 80,
            gray = ByteArray(8_000),
            labels = listOf(
                Label("周囲座", 10, 10, LabelKind.CONSTELLATION),
                Label("冬の大三角", 20, 20, LabelKind.ASTERISM),
                Label("シリウス", 30, 30, LabelKind.STAR_NAME),
            ),
        ).withGuidanceLabel("目標座", arrived = false)

        assertEquals("目標座 案内中", map.labels.first().text)
        assertTrue(map.labels.none { it.kind == LabelKind.CONSTELLATION || it.kind == LabelKind.ASTERISM })
        assertTrue(map.labels.any { it.text == "シリウス" })
    }
}
