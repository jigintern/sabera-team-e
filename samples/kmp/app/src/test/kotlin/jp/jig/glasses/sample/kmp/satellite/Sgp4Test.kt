package jp.jig.glasses.sample.kmp.satellite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Vallado の参照実装（CelesTrak 配布の `SGP4.cpp`）の出力と突き合わせる。
 *
 * 期待値 `expected.txt` は参照実装をそのままビルドして作った。1 行が
 * `NORAD 経過分 深宇宙か x y z vx vy vz エラー番号`。
 * **検証用 TLE は 33 件あり、うち 24 件が深宇宙**（みちびき・ひまわりと同じ側）。
 */
class Sgp4Test {

    private class Expected(
        val norad: Int,
        val minutes: Double,
        val deepSpace: Boolean,
        val x: Double,
        val y: Double,
        val z: Double,
        val vx: Double,
        val vy: Double,
        val vz: Double,
        val error: Int,
    )

    private fun resource(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("sgp4/$name")) { "$name が無い" }
            .bufferedReader().readText()

    private fun expectations(): List<Expected> = resource("expected.txt").lineSequence()
        .filter { it.isNotBlank() }
        .map { line ->
            val f = line.trim().split(Regex("\\s+"))
            Expected(
                norad = f[0].toInt(), minutes = f[1].toDouble(), deepSpace = f[2] == "1",
                x = f[3].toDouble(), y = f[4].toDouble(), z = f[5].toDouble(),
                vx = f[6].toDouble(), vy = f[7].toDouble(), vz = f[8].toDouble(),
                error = f[9].toInt(),
            )
        }.toList()

    private fun propagators(): Map<Int, Sgp4> =
        Tle.parseAll(resource("SGP4-VER.TLE")).associate { it.noradId to Sgp4(it) }

    @Test
    fun `検証用 TLE を全部読める`() {
        val tles = Tle.parseAll(resource("SGP4-VER.TLE"))
        // 参照実装が 33 件として扱っているので、こちらも同じ数だけ読めていないと比較にならない
        assertEquals(33, tles.size)
        val iss = tles.first { it.noradId == 5 }
        assertEquals(34.2682, Math.toDegrees(iss.inclo), 1e-4)
        assertEquals(0.1859667, iss.ecco, 1e-9)
    }

    @Test
    fun `深宇宙かどうかの判定が参照実装と一致する`() {
        val sgp4 = propagators()
        for (e in expectations()) {
            val s = sgp4[e.norad] ?: continue
            assertEquals("NORAD ${e.norad} の深宇宙判定", e.deepSpace, s.deepSpace)
        }
    }

    @Test
    fun `位置と速度が参照実装と一致する`() {
        // 深宇宙は共鳴を時間積分するので、**呼ぶ順番で結果が変わる**。
        // 期待値を作ったときと同じ順（元期 → 指定時刻）で回す
        val sgp4 = propagators()
        var checked = 0
        var deepChecked = 0
        var worstPos = 0.0
        var worstVel = 0.0
        for (e in expectations()) {
            val s = sgp4[e.norad] ?: continue
            val state = s.propagate(e.minutes)
            if (e.error != 0) {
                // 参照実装が投げ出した点は、こちらも出せないはず
                assertTrue("NORAD ${e.norad} ${e.minutes}分 は伝播できないはず", state == null)
                continue
            }
            checkNotNull(state) { "NORAD ${e.norad} ${e.minutes}分 が伝播できない" }
            val dp = sqrt(
                (state.x - e.x) * (state.x - e.x) +
                    (state.y - e.y) * (state.y - e.y) +
                    (state.z - e.z) * (state.z - e.z),
            )
            val dv = sqrt(
                (state.vx - e.vx) * (state.vx - e.vx) +
                    (state.vy - e.vy) * (state.vy - e.vy) +
                    (state.vz - e.vz) * (state.vz - e.vz),
            )
            worstPos = maxOf(worstPos, dp)
            worstVel = maxOf(worstVel, dv)
            // 参照実装の出力は小数 8 桁なので、そこまでは合わせられる
            assertTrue("NORAD ${e.norad} ${e.minutes}分 で位置が ${dp}km ずれた", dp < 1e-5)
            assertTrue("NORAD ${e.norad} ${e.minutes}分 で速度が ${dv}km/s ずれた", dv < 1e-8)
            checked++
            if (e.deepSpace) deepChecked++
        }
        println(
            "$checked 点を検算した（うち深宇宙 $deepChecked 点）。" +
                "位置の最大差 ${"%.2e".format(worstPos)} km / 速度 ${"%.2e".format(worstVel)} km/s",
        )
        assertTrue("検算した点が少なすぎる", checked >= 100)
        assertTrue("深宇宙を検算していない", deepChecked >= 80)
    }

    @Test
    fun `TLE の指数表記を読める`() {
        val line1 = "1 06251U 62025E   06176.82412014  .00008885  00000-0  12808-3 0  3985"
        val line2 = "2 06251  58.0579  54.0425 0030035 139.1568 221.1854 15.56387291  6774"
        val tle = checkNotNull(Tle.parse("DELTA 1 DEB", line1, line2))
        // bstar は 0.12808e-3
        assertEquals(0.12808e-3, tle.bstar, 1e-12)
        assertEquals(0.0, tle.nddot, 1e-20)
        assertEquals(0.0030035, tle.ecco, 1e-9)
        assertTrue("平均運動が rad/分 になっていない", abs(tle.noKozai - 15.56387291 * 2 * Math.PI / 1440.0) < 1e-12)
    }
}
