package jp.jig.glasses.sample.kmp.starmap

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「初心者に星を探させない」の要は、**アプリの側が合わせ先を決められること**。
 * ここが破れると「あの星に合わせて」と言えなくなるので、実データで固定する。
 */
class AlignmentTargetsTest {

    private val dataDir: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "data") }
        .first { File(it, "stars.json").exists() }

    private fun catalog(): StarCatalog {
        val stars = ArrayList<Star>()
        JSONObject(File(dataDir, "stars.json").readText()).getJSONArray("stars").let { arr ->
            for (i in 0 until arr.length()) {
                val s = arr.getJSONArray(i)
                stars += Star(s.getInt(0), s.getDouble(1), s.getDouble(2), s.getDouble(3))
            }
        }
        val names = HashMap<Int, String>()
        JSONObject(File(dataDir, "bright-stars.json").readText()).getJSONArray("stars").let { arr ->
            for (i in 0 until arr.length()) {
                val s = arr.getJSONObject(i)
                names[s.getInt("hip")] = s.getString("nameJa")
            }
        }
        return StarCatalog(stars, emptyList(), names)
    }

    private val site = Site(35.9432, 136.1846)

    /** 2026-01-01 21:00 JST の鯖江。冬の宵で 1 等星が 6 個、高度 15〜60° に入っている */
    private val epoch = 1767268800000L

    @Test
    fun `合わせ先の候補は明るくて高すぎない星だけになる`() {
        val targets = AlignmentTargets(catalog())
        val candidates = targets.candidates(site, epoch)

        assertEquals(6, candidates.size)
        for (candidate in candidates) {
            assertTrue(candidate.nameJa.isNotBlank())
            assertTrue("1.6 等より暗い星は候補にしない", candidate.magnitude <= AlignmentTargets.LIMIT_MAGNITUDE)
            assertTrue(
                "誤差の伝播が 1/cos h なので高すぎる天体は使わない: ${candidate.nameJa}",
                candidate.altDeg in AlignmentTargets.MIN_ALTITUDE_DEG..AlignmentTargets.MAX_ALTITUDE_DEG,
            )
        }
        // 明るさと高度で並ぶので、この空ならシリウスが先頭に来る
        assertEquals("シリウス", candidates.first().nameJa)
    }

    @Test
    fun `視線からどちらへ何度振ればよいかを言える`() {
        val targets = AlignmentTargets(catalog())
        val candidates = targets.candidates(site, epoch)
        val sirius = candidates.first { it.nameJa == "シリウス" }

        // 目標より 30° 左を向いている → 右へ振ってもらう
        val fromLeft = targets.guidanceFor(sirius, candidates, Look(sirius.azDeg - 30.0, sirius.altDeg))
        assertEquals(30.0, fromLeft.turnDeg, 1e-6)
        assertFalse("30° 離れていれば視野の外", fromLeft.inView)
        assertTrue(glassGuidanceText(fromLeft, null).startsWith("▶▶ 右へ"))
        assertTrue(phoneGuidanceText(fromLeft, null).contains("右"))

        val fromRight = targets.guidanceFor(sirius, candidates, Look(sirius.azDeg + 30.0, sirius.altDeg))
        assertEquals(-30.0, fromRight.turnDeg, 1e-6)
        assertTrue(glassGuidanceText(fromRight, null).startsWith("◀◀ 左へ"))
    }

    @Test
    fun `視野に入れば重ねる案内に変わり、まぎれる星は無い`() {
        val targets = AlignmentTargets(catalog())
        val candidates = targets.candidates(site, epoch)
        val sirius = candidates.first { it.nameJa == "シリウス" }

        val centered = targets.guidanceFor(sirius, candidates, Look(sirius.azDeg, sirius.altDeg))
        assertTrue(centered.inView)
        assertEquals(0.0, centered.distanceDeg, 1e-6)
        assertFalse("視野 35° に他の 1 等星は入らない", centered.ambiguous)
        assertEquals("＋ に重ねて止める", glassGuidanceText(centered, null))

        val inView = targets.inView(candidates, Look(sirius.azDeg, sirius.altDeg))
        assertEquals(listOf("シリウス"), inView.map { it.nameJa })
    }

    @Test
    fun `2点目は1点目から離れた星を選ぶ`() {
        val targets = AlignmentTargets(catalog())
        val candidates = targets.candidates(site, epoch)
        val first = candidates.first()

        val second = targets.guide(
            candidates = candidates,
            look = Look(first.azDeg, first.altDeg),
            exclude = setOf(first.hip),
            separateFrom = first,
        )
        assertTrue("離れた 2 点目が要る", second != null)
        assertEquals("ポルックス", second!!.target.nameJa)
        assertTrue(
            "SkyAlign も SPAAM も『点は広く散らせ』",
            angleBetweenDeg(
                enu(first.azDeg, first.altDeg),
                enu(second.target.azDeg, second.target.altDeg),
            ) >= AlignmentTargets.MIN_SEPARATION_DEG,
        )
    }

    @Test
    fun `静止しているあいだは残り秒数を出す`() {
        val hold = AlignmentHold()
        var state = hold.add(0L, 10.0, 30.0)
        var at = 0L
        repeat(15) {
            at += 100L
            state = hold.add(at, 10.0, 30.0)
        }
        assertTrue(state.steady)
        assertTrue(phoneGuidanceText(guidance = null, holdState = state).isNotBlank())
    }

    @Test
    fun `空に候補が無ければ案内せずにそう言う`() {
        val targets = AlignmentTargets(catalog())
        // 昼の 12 時。1 等星は空にあっても見えないが、高度の条件では残るので
        // ここでは「候補が空でも文言が出る」ことだけを固定する
        assertTrue(phoneGuidanceText(null, null).contains("ありません"))
        assertTrue(glassGuidanceText(null, null).contains("見つかりません"))
        assertTrue(targets.guide(emptyList(), Look(0.0, 30.0)) == null)
    }
}
