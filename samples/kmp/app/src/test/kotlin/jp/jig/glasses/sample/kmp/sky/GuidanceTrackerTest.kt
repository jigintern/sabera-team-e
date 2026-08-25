package jp.jig.glasses.sample.kmp.sky

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuidanceTrackerTest {
    private val target = GuidanceTarget(
        id = "constellation:ori",
        nameJa = "オリオン座",
        kind = GuidanceTargetKind.CONSTELLATION,
        aim = Look(120.0, 40.0),
    )

    @Test
    fun `最初は左右だけを案内する`() {
        val right = GuidanceSession(target, 0L).update(Look(100.0, 40.0), target.aim, 100L)
        val left = GuidanceSession(target, 0L).update(Look(140.0, 40.0), target.aim, 100L)

        assertEquals(GuidanceStage.HORIZONTAL, right.frame.stage)
        assertEquals(GuidanceDirection.RIGHT, right.frame.direction)
        assertTrue(right.frame.horizontalErrorDeg > 0.0)
        assertEquals(GuidanceStage.HORIZONTAL, left.frame.stage)
        assertEquals(GuidanceDirection.LEFT, left.frame.direction)
        assertTrue(left.frame.horizontalErrorDeg < 0.0)
    }

    @Test
    fun `左右を合わせた後だけ上下を案内する`() {
        val up = GuidanceSession(target, 0L).update(Look(120.0, 20.0), target.aim, 100L)
        val downTarget = target.copy(aim = Look(120.0, 20.0))
        val down = GuidanceSession(downTarget, 0L).update(Look(120.0, 40.0), downTarget.aim, 100L)

        assertEquals(GuidanceStage.VERTICAL, up.frame.stage)
        assertEquals(GuidanceDirection.UP, up.frame.direction)
        assertTrue(up.frame.verticalErrorDeg > 0.0)
        assertEquals(GuidanceStage.VERTICAL, down.frame.stage)
        assertEquals(GuidanceDirection.DOWN, down.frame.direction)
        assertTrue(down.frame.verticalErrorDeg < 0.0)
    }

    @Test
    fun `左右は五度で上下へ進み八度を超えるまで戻らない`() {
        val horizonTarget = target.copy(aim = Look(120.0, 0.0))
        val stillHorizontal = GuidanceSession(horizonTarget, 0L)
            .update(Look(114.0, 0.0), horizonTarget.aim, 100L)
        val vertical = GuidanceSession(horizonTarget, 0L)
            .update(Look(116.0, 0.0), horizonTarget.aim, 100L)
        val held = requireNotNull(vertical.session)
            .update(Look(113.0, 0.0), horizonTarget.aim, 200L)
        val returned = requireNotNull(held.session)
            .update(Look(111.0, 0.0), horizonTarget.aim, 300L)

        assertEquals(GuidanceStage.HORIZONTAL, stillHorizontal.frame.stage)
        assertEquals(GuidanceStage.VERTICAL, vertical.frame.stage)
        assertEquals(GuidanceStage.VERTICAL, held.frame.stage)
        assertEquals(GuidanceStage.HORIZONTAL, returned.frame.stage)
    }

    @Test
    fun `方位の零度またぎでも短い向きを選ぶ`() {
        val seamTarget = target.copy(aim = Look(5.0, 20.0))
        val update = GuidanceSession(seamTarget, 0L).update(Look(355.0, 20.0), seamTarget.aim, 100L)

        assertEquals(GuidanceStage.HORIZONTAL, update.frame.stage)
        assertEquals(GuidanceDirection.RIGHT, update.frame.direction)
        assertTrue(update.frame.horizontalErrorDeg in 5.0..15.0)
    }

    @Test
    fun `五度以内へ半秒留まると到着する`() {
        var session = GuidanceSession(target, 0L)
        val first = session.update(Look(116.0, 40.0), target.aim, 100L)
        session = requireNotNull(first.session)
        assertEquals(GuidanceStage.VERTICAL, first.frame.stage)
        assertFalse(first.frame.arrived)

        val arrived = session.update(Look(116.0, 40.0), target.aim, 600L)
        assertEquals(GuidanceEvent.ARRIVED, arrived.event)
        assertTrue(arrived.frame.arrived)
        assertNull(arrived.frame.direction)
    }

    @Test
    fun `到着後八度を超えたら左右案内へ戻る`() {
        val arrived = GuidanceSession(
            target = target,
            startedAtMillis = 0L,
            stage = GuidanceStage.ARRIVED,
            withinArrivalSinceMillis = 0L,
            arrivedAtMillis = 600L,
        )
        // 同じ高度では方位差が球面上で縮むため、12°離して角距離8°超を作る。
        val resumed = arrived.update(Look(108.0, 40.0), target.aim, 700L)

        assertEquals(GuidanceEvent.NONE, resumed.event)
        assertEquals(GuidanceStage.HORIZONTAL, resumed.frame.stage)
        assertFalse(resumed.frame.arrived)
        assertNull(requireNotNull(resumed.session).arrivedAtMillis)
    }

    @Test
    fun `到着表示は三秒で終わり案内全体は六十秒で終わる`() {
        val arrived = GuidanceSession(
            target = target,
            startedAtMillis = 0L,
            stage = GuidanceStage.ARRIVED,
            withinArrivalSinceMillis = 0L,
            arrivedAtMillis = 600L,
        )
        assertEquals(
            GuidanceEvent.COMPLETED,
            arrived.update(target.aim, target.aim, 3_600L).event,
        )

        val timedOut = GuidanceSession(target, 0L).update(Look(0.0, 0.0), target.aim, 60_000L)
        assertEquals(GuidanceEvent.TIMED_OUT, timedOut.event)
        assertNull(timedOut.session)
    }
}
