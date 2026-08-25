package jp.jig.glasses.sample.kmp.sound

import jp.jig.glasses.sample.kmp.sky.SkyDarkness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * 曲の割り当てと選び方。
 *
 * `MediaPlayer` を触る [Bgm] は JVM で動かないので、**ここで見るのは選ぶところだけ**。
 * 鳴り方（クロスフェード・絞り）は実機でしか確かめられない。
 */
class BgmPlaylistTest {

    /** **どの場面にも曲がある。** 空の場面があると、そこへ入ったときに鳴らす曲が無くなる */
    @Test
    fun everySceneHasATrack() {
        for (scene in BgmScene.entries) {
            assertTrue("${scene.label} に曲が無い", scene.tracks.isNotEmpty())
        }
    }

    /** 同じ音源を 2 つの曲が指していないか（コピペで res を取り違えると気づけない） */
    @Test
    fun tracksDoNotShareAudio() {
        val res = BgmTrack.entries.map { it.res }
        assertEquals(res.size, res.distinct().size)
    }

    /** 曲名も重ならない。**帰属の表示にそのまま出る**ので、重複は表示の誤りになる */
    @Test
    fun titlesAreDistinct() {
        val titles = BgmTrack.entries.map { it.title }
        assertEquals(titles.size, titles.distinct().size)
    }

    /** 空の暗さから場面を引く。**夜だけが夜**で、昼と市民薄明は薄暮の曲 */
    @Test
    fun sceneFollowsDarkness() {
        assertEquals(BgmScene.TWILIGHT, BgmScene.of(SkyDarkness.DAY))
        assertEquals(BgmScene.TWILIGHT, BgmScene.of(SkyDarkness.CIVIL))
        assertEquals(BgmScene.NIGHT, BgmScene.of(SkyDarkness.NIGHT))
    }

    /** 選ばれた曲は必ずその場面のもの */
    @Test
    fun nextStaysInScene() {
        for (scene in BgmScene.entries) {
            repeat(REPEATS) { seed ->
                val next = BgmPlaylist.next(scene, playing = null, random = Random(seed))
                assertNotNull(next)
                assertTrue(next in scene.tracks)
            }
        }
    }

    /** **同じ曲を続けて選ばない。** 続くと曲を渡り歩いている意味が無くなる */
    @Test
    fun nextAvoidsRepeatingTheSameTrack() {
        for (scene in BgmScene.entries.filter { it.tracks.size >= 2 }) {
            for (playing in scene.tracks) {
                repeat(REPEATS) { seed ->
                    val next = BgmPlaylist.next(scene, playing, Random(seed))
                    assertFalse("${scene.label} で ${playing.title} が続いた", next == playing)
                }
            }
        }
    }

    /** 場面に 1 曲しか無ければ、その曲を返す（null を返して黙らせない） */
    @Test
    fun nextRepeatsWhenSceneHasOnlyOneTrack() {
        val lonely = BgmScene.entries.filter { it.tracks.size == 1 }
        for (scene in lonely) {
            val only = scene.tracks.single()
            assertEquals(only, BgmPlaylist.next(scene, playing = only, random = Random(0)))
        }
    }

    /** 曲を渡り歩けば、その場面の曲がひととおり出てくる（1 曲に偏らない） */
    @Test
    fun walkingCoversTheWholeScene() {
        for (scene in BgmScene.entries.filter { it.tracks.size >= 2 }) {
            val random = Random(1)
            var playing = scene.tracks.first()
            val seen = mutableSetOf(playing)
            repeat(REPEATS) {
                playing = BgmPlaylist.next(scene, playing, random)!!
                seen += playing
            }
            assertEquals(scene.tracks.toSet(), seen)
        }
    }

    /** 覚えてある名前から引く。**知らない名前はおまかせ**（null）に落として落ちない */
    @Test
    fun byNameFallsBackToAuto() {
        for (track in BgmTrack.entries) {
            assertEquals(track, BgmTrack.byName(track.name))
        }
        assertEquals(null, BgmTrack.byName("BGM_THAT_WAS_REMOVED"))
        assertEquals(null, BgmTrack.byName(null))
        assertEquals(null, BgmTrack.byName(""))
    }

    /** 帰属には**同梱した曲がすべて**出る（CC BY 4.0 の条件） */
    @Test
    fun creditNamesEveryTrack() {
        for (track in BgmTrack.entries) {
            assertTrue("${track.title} が帰属に無い", BgmTrack.credit.contains(track.title))
        }
        assertTrue(BgmTrack.credit.contains("Kevin MacLeod"))
        assertTrue(BgmTrack.credit.contains("CC BY 4.0"))
    }

    private companion object {
        /** 乱数で選ぶので、種を変えて何度も引く */
        const val REPEATS = 50
    }
}
