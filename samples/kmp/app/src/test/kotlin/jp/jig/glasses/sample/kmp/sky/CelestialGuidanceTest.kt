package jp.jig.glasses.sample.kmp.sky

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CelestialGuidanceTest {
    private val orion = GuidanceTarget(
        id = "constellation:ori",
        nameJa = "オリオン座",
        kind = GuidanceTargetKind.CONSTELLATION,
        aim = Look(120.0, 40.0),
    )
    private val sirius = GuidanceTarget(
        id = "star:sirius",
        nameJa = "シリウス",
        kind = GuidanceTargetKind.STAR,
        aim = Look(130.0, 35.0),
    )

    @Test
    fun `どこを含む登録名だけを案内として取り出す`() {
        val result = GuidanceRequestParser.parse("オリオンはどこ？", listOf(orion, sirius))
        assertEquals(orion, (result as GuidanceRequest.Start).target)
        assertEquals(
            GuidanceRequest.NotGuidance,
            GuidanceRequestParser.parse("オリオン座ってどんな星座？", listOf(orion)),
        )
    }

    @Test
    fun `カタカナとひらがなを同じ名前として扱う`() {
        val result = GuidanceRequestParser.parse("しりうすをみせて", listOf(sirius))
        assertEquals(sirius, (result as GuidanceRequest.Start).target)
    }

    @Test
    fun `複数対象と不明な対象を勝手に一つへ決めない`() {
        assertEquals(
            GuidanceRequest.MultipleTargets,
            GuidanceRequestParser.parse("オリオン座とシリウスはどこ", listOf(orion, sirius)),
        )
        assertEquals(
            GuidanceRequest.UnknownTarget,
            GuidanceRequestParser.parse("知らない星はどこ", listOf(orion, sirius)),
        )
    }

    @Test
    fun `長い星座名の中にある別の短い星座名を拾わない`() {
        val dragon = orion.copy(id = "constellation:dra", nameJa = "りゅう座")
        val keel = orion.copy(id = "constellation:car", nameJa = "りゅうこつ座")

        val result = GuidanceRequestParser.parse("りゅうこつ座はどこ", listOf(dragon, keel))

        assertEquals(keel, (result as GuidanceRequest.Start).target)
        assertEquals(
            GuidanceRequest.MultipleTargets,
            GuidanceRequestParser.parse("りゅうこつ座とりゅう座はどこ", listOf(dragon, keel)),
        )
    }

    @Test
    fun `上と右を向く矢印の角度を画面基準で返す`() {
        val session = GuidanceSession(orion, 0L)
        val up = session.update(Look(120.0, 20.0), Look(120.0, 40.0), 0.0, 100L)
        val right = session.update(Look(100.0, 40.0), Look(120.0, 40.0), 0.0, 100L)

        assertEquals(0.0, up.frame.arrowClockwiseDeg, 0.1)
        assertTrue(right.frame.arrowClockwiseDeg in 80.0..100.0)
    }

    @Test
    fun `五度以内へ半秒留まると到着する`() {
        var session = GuidanceSession(orion, 0L)
        val first = session.update(Look(116.0, 40.0), orion.aim, 0.0, 100L)
        session = requireNotNull(first.session)
        assertFalse(first.frame.arrived)

        val arrived = session.update(Look(116.0, 40.0), orion.aim, 0.0, 600L)
        assertEquals(GuidanceEvent.ARRIVED, arrived.event)
        assertTrue(arrived.frame.arrived)
    }

    @Test
    fun `到着後八度を超えたら案内へ戻る`() {
        val arrived = GuidanceSession(orion, 0L, withinArrivalSinceMillis = 0L, arrivedAtMillis = 600L)
        // 同じ高度では方位差が球面上で縮むため、12°離して角距離8°超を作る。
        val resumed = arrived.update(Look(108.0, 40.0), orion.aim, 0.0, 700L)

        assertEquals(GuidanceEvent.NONE, resumed.event)
        assertFalse(resumed.frame.arrived)
        assertNull(requireNotNull(resumed.session).arrivedAtMillis)
    }

    @Test
    fun `到着表示は三秒で終わり案内全体は六十秒で終わる`() {
        val arrived = GuidanceSession(orion, 0L, withinArrivalSinceMillis = 0L, arrivedAtMillis = 600L)
        assertEquals(
            GuidanceEvent.COMPLETED,
            arrived.update(orion.aim, orion.aim, 0.0, 3_600L).event,
        )

        val timedOut = GuidanceSession(orion, 0L).update(Look(0.0, 0.0), orion.aim, 0.0, 60_000L)
        assertEquals(GuidanceEvent.TIMED_OUT, timedOut.event)
        assertNull(timedOut.session)
    }
}
