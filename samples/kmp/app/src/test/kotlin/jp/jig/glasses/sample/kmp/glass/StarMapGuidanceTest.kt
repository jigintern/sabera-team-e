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

    private fun map() = StarMap(width = 100, height = 80, gray = ByteArray(8_000), labels = emptyList())

    /**
     * ガイド中は**声で言った方角を文字にも残す**。
     * 騒がしい場所では文字が主役なので、聞き逃すと向く先が分からなくなっていた。
     */
    @Test
    fun `ガイド中は方角も出す`() {
        val label = map().withGuidanceLabel("目標座", arrived = false, where = "南南西 高いところ")

        assertEquals("目標座 南南西 高いところ", label.labels.single().text)
        // グラスのパネル（576px）に収まる長さであること
        assertTrue(label.labels.single().text.length * LABEL_CHAR_WIDTH <= PANEL_WIDTH)
    }

    /** 着いたら方角はもう要らない。**探す言葉を残すと、まだ探すのかと思わせる** */
    @Test
    fun `到着したら方角ではなくこのあたりと出す`() {
        val label = map().withGuidanceLabel("目標座", arrived = true, where = "南南西 高いところ")

        assertEquals("目標座 このあたり", label.labels.single().text)
    }

    /** 声で頼んだ案内（#46）は今までどおり。**自分で名前を言った直後なので方角だけが要る** */
    @Test
    fun `声で頼んだ案内は案内中のまま`() {
        assertEquals("目標座 案内中", map().withGuidanceLabel("目標座", arrived = false).labels.single().text)
    }
}
