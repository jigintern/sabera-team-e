package jp.jig.glasses.sample.kmp.ui

import jp.jig.glasses.sample.kmp.satellite.SkyTrack
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 星図に重ねる機体の絞り込み（[notableTracks]）。
 *
 * 焼く側と印を動かす側で**同じ選定を通ること**が肝で、片方だけ素通しになると
 * 描かれていない衛星の名前が印の更新で湧いて出る（#37 と同型の取り違え）。
 */
class OverlayTracksTest {

    private fun track(name: String) = SkyTrack(name, 180.0, 45.0, sunlit = true, labelled = true)

    @Test
    fun `名前が手がかりにならない機体は落とす`() {
        val picked = notableTracks(listOf(track("だいち4"), track("ISS"), track("NOAA 19"), track("ひまわり8")))
        assertEquals(listOf("ISS", "ひまわり8"), picked.map { it.name })
    }

    @Test
    fun `星座の邪魔をしない数までに絞る`() {
        val picked = notableTracks(
            listOf(track("ISS"), track("GPS BIIR-2"), track("みちびき"), track("ガリレオ 5"), track("天宮")),
        )
        assertEquals(3, picked.size)
        // 高い順（tracksInView の並び）を保ったまま先頭から取る
        assertEquals(listOf("ISS", "GPS BIIR-2", "みちびき"), picked.map { it.name })
    }
}
