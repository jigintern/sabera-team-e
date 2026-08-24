package jp.jig.glasses.sample.kmp.alignment

import android.hardware.SensorManager
import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Test

class CompassRotationTest {

    @Test
    fun cameraAxesFollowAllDisplayRotations() {
        assertEquals(
            CameraAxes(SensorManager.AXIS_X, SensorManager.AXIS_Z),
            cameraAxesForRotation(Surface.ROTATION_0),
        )
        assertEquals(
            CameraAxes(SensorManager.AXIS_Y, SensorManager.AXIS_Z),
            cameraAxesForRotation(Surface.ROTATION_90),
        )
        assertEquals(
            CameraAxes(SensorManager.AXIS_MINUS_X, SensorManager.AXIS_Z),
            cameraAxesForRotation(Surface.ROTATION_180),
        )
        assertEquals(
            CameraAxes(SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_Z),
            cameraAxesForRotation(Surface.ROTATION_270),
        )
    }
}
