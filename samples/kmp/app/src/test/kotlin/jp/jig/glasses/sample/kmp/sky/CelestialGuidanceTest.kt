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
    fun `土星の漢字と読み仮名と別名を同じ案内対象として扱う`() {
        val site = Site(35.9432, 136.1846)
        val targets = bodyGuidanceTargets(site, 0L)

        for (question in listOf("土星を案内して", "どせいはどこ", "サターンを見せて")) {
            val result = GuidanceRequestParser.parse(question, targets)
            assertEquals("土星", (result as GuidanceRequest.Start).target.nameJa)
        }

        // 同じ候補生成・表記ゆれ・案内判定を通し、沈んでいるときの理由まで一続きで確認する。
        val belowTargets = (0..3).map { sixHours ->
            bodyGuidanceTargets(site, sixHours * 6L * 60L * 60L * 1_000L)
        }.minBy { candidates ->
            candidates.single { it.nameJa == "土星" }.aim.altDeg
        }
        val saturn = (GuidanceRequestParser.parse("どせいはどこ", belowTargets) as GuidanceRequest.Start).target
        assertTrue(saturn.aim.altDeg < 0.0)
        assertEquals("土星は、いま地平線の下にあります。", guidanceUnavailableMessage(saturn))
    }

    @Test
    fun `場所を聞けば案内し特徴を聞けば解説へ渡す`() {
        assertTrue(
            GuidanceRequestParser.parse("オリオン座の場所を教えて", listOf(orion)) is
                GuidanceRequest.Start,
        )
        assertEquals(
            GuidanceRequest.NotGuidance,
            GuidanceRequestParser.parse("オリオン座について教えて", listOf(orion)),
        )
        assertEquals(
            GuidanceRequest.NotGuidance,
            GuidanceRequestParser.parse("オリオン座の神話を解説して", listOf(orion)),
        )
    }

    @Test
    fun `対象だけか案内と解説を同時に頼まれたら聞き返す`() {
        val targetOnly = GuidanceRequestParser.parse("オリオン座", listOf(orion))
        assertEquals(listOf(orion), (targetOnly as GuidanceRequest.ClarifyIntent).targets)

        val both = GuidanceRequestParser.parse("オリオン座を案内して特徴も解説して", listOf(orion))
        assertEquals(listOf(orion), (both as GuidanceRequest.ClarifyIntent).targets)

        // 案内を明示しない普通の質問まで、聞き返しや案内へ変えない。
        assertEquals(
            GuidanceRequest.NotGuidance,
            GuidanceRequestParser.parse("オリオン座は冬に見える？", listOf(orion)),
        )
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

    @Test
    fun `地平線の下の土星は理由を返し矢印を始めない`() {
        val saturn = GuidanceTarget(
            id = "body:SATURN",
            nameJa = "土星",
            kind = GuidanceTargetKind.BODY,
            aim = Look(240.0, -12.0),
        )

        assertEquals("土星は、いま地平線の下にあります。", guidanceUnavailableMessage(saturn))
        assertNull(guidanceUnavailableMessage(saturn.copy(aim = Look(240.0, 12.0))))
    }
}
