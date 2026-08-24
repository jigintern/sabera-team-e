package jp.jig.glasses.sample.kmp.ui

import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Test

class CalibrationLayoutTest {

    @Test
    fun portraitKeepsTargetCentered() {
        assertEquals(CalibrationTargetSide.CENTER, calibrationTargetSide(false, Surface.ROTATION_0))
        assertEquals(CalibrationTargetSide.CENTER, calibrationTargetSide(false, Surface.ROTATION_180))
    }

    @Test
    fun landscapeMovesTargetTowardPhysicalTopEdge() {
        assertEquals(CalibrationTargetSide.LEFT, calibrationTargetSide(true, Surface.ROTATION_90))
        assertEquals(CalibrationTargetSide.RIGHT, calibrationTargetSide(true, Surface.ROTATION_270))
    }
}
