package jp.jig.glasses.sample.kmp.glass

import jp.jig.glasses.sample.kmp.sky.GuidanceDirection
import jp.jig.glasses.sample.kmp.sky.GuidanceFrame
import jp.jig.glasses.sample.kmp.sky.GuidanceStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        ).withGuidanceLabel(searching())

        assertEquals("目標座 案内中", map.labels.first().text)
        assertTrue(map.labels.none { it.kind == LabelKind.CONSTELLATION || it.kind == LabelKind.ASTERISM })
        assertTrue(map.labels.any { it.text == "シリウス" })
    }

    /**
     * 案内中でも、再現中のラベルだけは先頭に残す（#45）。
     *
     * 並びは優先順位で、8 枠と 190 バイトの両方で打ち切られる。ここで押し出すと
     * **作った空を本物と信じたまま実際の空を探す**ことになる。
     */
    @Test
    fun `再現中のラベルは案内名より前に残る`() {
        val map = StarMap(
            width = 100,
            height = 80,
            gray = ByteArray(8_000),
            labels = listOf(
                Label("シドニー 8/24 20:30", 50, 70, LabelKind.STATUS),
                Label("周囲座", 10, 10, LabelKind.CONSTELLATION),
            ),
        ).withGuidanceLabel(searching())

        assertEquals(LabelKind.STATUS, map.labels.first().kind)
        assertEquals(LabelKind.GUIDANCE, map.labels[1].kind)
    }

    private fun map() = StarMap(width = 100, height = 80, gray = ByteArray(8_000), labels = emptyList())

    /**
     * ガイド中は**声で言った方角を文字にも残す**。
     * 騒がしい場所では文字が主役なので、聞き逃すと向く先が分からなくなっていた。
     */
    @Test
    fun `ガイド中は方角も出す`() {
        val labels = map().withGuidanceLabel(searching(), where = "南南西 高いところ").labels

        assertEquals("目標座 南南西 高いところ", labels.first().text)
        // グラスのパネル（576px）に収まる長さであること
        assertTrue(labels.first().text.length * LABEL_CHAR_WIDTH <= PANEL_WIDTH)
    }

    /** 着いたら方角はもう要らない。**探す言葉を残すと、まだ探すのかと思わせる** */
    @Test
    fun `到着したら方角ではなくこのあたりと出す`() {
        val labels = map().withGuidanceLabel(arrived(), where = "南南西 高いところ").labels

        assertEquals("目標座 このあたり", labels.single().text)
    }

    /** 声で頼んだ案内（#46）は今までどおり。**自分で名前を言った直後なので方角だけが要る** */
    @Test
    fun `声で頼んだ案内は案内中のまま`() {
        assertEquals("目標座 案内中", map().withGuidanceLabel(searching()).labels.first().text)
    }

    /**
     * **矢印だけでは向く先が読めなかった**（2026-08-24 実機）。
     * 同じことを文字でも言い、いまの段で詰める差を度で出す。
     */
    @Test
    fun `どちらへどれだけ首を振るかを2行目に出す`() {
        val labels = map().withGuidanceLabel(searching()).labels

        assertEquals(2, labels.count { it.kind == LabelKind.GUIDANCE })
        assertEquals("左へ 32°", labels[1].text)
        // 1 行目と重ならない位置に置く（重なると toCanvasElements が落とす）
        assertTrue(labels[1].y > labels[0].y + CANVAS_LABEL_HEIGHT / 2)
    }

    @Test
    fun `上下の段では上下差を出す`() {
        val frame = searching().copy(
            stage = GuidanceStage.VERTICAL,
            direction = GuidanceDirection.UP,
            horizontalErrorDeg = 2.0,
            verticalErrorDeg = 11.4,
        )

        assertEquals("上へ 11°", guidanceTurnText(frame))
    }

    /** 着いたら首振りの指示は消す。**残すとまだ動かすのかと思わせる** */
    @Test
    fun `到着したら首振りの指示は出さない`() {
        assertNull(guidanceTurnText(arrived()))
        assertEquals(1, map().withGuidanceLabel(arrived()).labels.size)
    }

    private fun searching() = GuidanceFrame(
        targetName = "目標座",
        distanceDeg = 32.0,
        stage = GuidanceStage.HORIZONTAL,
        direction = GuidanceDirection.LEFT,
        horizontalErrorDeg = -31.6,
        verticalErrorDeg = 5.0,
        near = false,
    )

    private fun arrived() = searching().copy(
        distanceDeg = 3.0,
        stage = GuidanceStage.ARRIVED,
        direction = null,
        horizontalErrorDeg = 1.0,
        verticalErrorDeg = 1.0,
        near = true,
    )
}
