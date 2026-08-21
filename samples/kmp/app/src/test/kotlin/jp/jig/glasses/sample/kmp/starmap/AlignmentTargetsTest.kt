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

    /** 2026-01-01 21:00 JST の鯖江。冬の宵で 1 等星が 6 個、木星と土星も出ている */
    private val epoch = 1767268800000L

    @Test
    fun `合わせ先の候補は明るくて高すぎないものだけになる`() {
        val targets = AlignmentTargets(catalog())
        val candidates = targets.candidates(site, epoch)

        assertEquals(8, candidates.size)
        assertEquals("恒星は 6 個", 6, candidates.count { it.id > 0 })
        assertEquals("惑星は木星と土星", listOf("土星", "木星"), candidates.filter { it.id < 0 }.map { it.nameJa }.sorted())
        for (candidate in candidates) {
            assertTrue(candidate.nameJa.isNotBlank())
            assertTrue("1.6 等より暗いものは候補にしない", candidate.magnitude <= AlignmentTargets.LIMIT_MAGNITUDE)
            assertTrue(
                "誤差の伝播が 1/cos h なので高すぎる天体は使わない: ${candidate.nameJa}",
                candidate.altDeg in AlignmentTargets.MIN_ALTITUDE_DEG..AlignmentTargets.MAX_ALTITUDE_DEG,
            )
        }
        // 明るさと高度で並ぶ。この空では −2.7 等の木星が恒星より先に来る
        assertEquals("木星", candidates.first().nameJa)
    }

    @Test
    fun `月が出ていれば最優先の合わせ先になる`() {
        val targets = AlignmentTargets(catalog())
        // 月は 1 か月で満ち欠けするので、条件を満たす夜を探して確かめる
        val found = (0 until 24 * 30).map { epoch + it * 3_600_000L }.firstOrNull { at ->
            sunAltitudeDeg(site, at) < AlignmentTargets.VISIBLE_SUN_ALTITUDE_DEG &&
                targets.candidates(site, at).any { it.nameJa == "月" }
        }
        assertTrue("1 か月あれば月が高度 15〜60° に来る夜がある", found != null)
        val candidates = targets.candidates(site, found!!)
        assertEquals("−10 等の月が先頭に来る", "月", candidates.first().nameJa)
        assertTrue(candidates.first().magnitude < -5.0)
    }

    @Test
    fun `空が明るいあいだは月しか案内しない`() {
        val targets = AlignmentTargets(catalog())
        val daylight = (0 until 24 * 30).map { epoch + it * 3_600_000L }.firstOrNull { at ->
            sunAltitudeDeg(site, at) > 10.0 && targets.candidates(site, at).isNotEmpty()
        }
        assertTrue("昼に月が出ている時間帯はある", daylight != null)
        val candidates = targets.candidates(site, daylight!!)
        assertEquals("昼は星も惑星も見えない", listOf("月"), candidates.map { it.nameJa })
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
    fun `視野に入れば重ねる案内に変わり、まぎれるものは無い`() {
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
            exclude = setOf(first.id),
            separateFrom = first,
        )
        assertTrue("離れた 2 点目が要る", second != null)
        assertTrue(
            "1 点目とは別のものが選ばれる",
            second!!.target.id != first.id,
        )
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
        assertTrue(glassGuidanceText(null, null).contains("ありません"))
        assertTrue(targets.guide(emptyList(), Look(0.0, 30.0)) == null)
    }
}
