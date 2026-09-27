package jp.jig.glasses.sample.kmp.doc

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowSensor
import org.robolectric.shadows.ShadowSensorManager
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 方位合わせの画面を撮るための**スマホのセンサー**。
 *
 * Robolectric の端末にはセンサーが 1 つも無いので、「スマホの向きを待っています」から先へ
 * 進まない。**顔の前へかざした姿勢**（背面が空の一点を向き、画面はこちらを向いている）を
 * 作って流し込むと、アプリの判定がひととおり動く。
 *
 * 磁場は日本の値（46µT・伏角 49°）。**歪んでいない屋外**として通る。
 */
class DemoSensors(context: Context) {

    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val shadow: ShadowSensorManager = shadowOf(manager)

    private val rotationVector = ShadowSensor.newInstance(Sensor.TYPE_ROTATION_VECTOR)
    private val accelerometer = ShadowSensor.newInstance(Sensor.TYPE_ACCELEROMETER)
    private val magnetometer = ShadowSensor.newInstance(Sensor.TYPE_MAGNETIC_FIELD)

    init {
        shadow.addSensor(rotationVector)
        shadow.addSensor(accelerometer)
        shadow.addSensor(magnetometer)
    }

    /**
     * 背面が[azimuthDeg]の方角・[elevationDeg]の高さを向いている姿勢を 1 回配る。
     * [rollDeg] は背面の向きを保ったまま画面法線まわりに回す角度（0 = 縦持ち、±90 = 横持ち）。
     *
     * **同じ値を配り続けると「止めている」ことになる**ので、静止の判定も満たせる。
     */
    fun aim(azimuthDeg: Double, elevationDeg: Double, rollDeg: Double = 0.0) {
        val r = deviceToWorld(azimuthDeg, elevationDeg, rollDeg)
        send(Sensor.TYPE_ROTATION_VECTOR, quaternionOf(r))
        // 静止しているので、加速度計が読むのは重力だけ
        send(Sensor.TYPE_ACCELEROMETER, intoDevice(r, 0.0, 0.0, GRAVITY))
        val inclination = Math.toRadians(FIELD_INCLINATION_DEG)
        send(
            Sensor.TYPE_MAGNETIC_FIELD,
            intoDevice(
                r,
                0.0,
                FIELD_MICRO_TESLA * cos(inclination),
                -FIELD_MICRO_TESLA * sin(inclination),
            ),
        )
        // **「精度は高い」は別の口で来る。** これが来ないと 8 の字を振らされ続ける
        for (listener in shadow.listeners) {
            listener.onAccuracyChanged(magnetometer, SensorManager.SENSOR_STATUS_ACCURACY_HIGH)
        }
    }

    private fun send(type: Int, values: FloatArray) {
        if (shadow.listeners.isEmpty()) return
        val event = ShadowSensorManager.createSensorEvent(values.size, type)
        values.copyInto(event.values)
        shadow.sendSensorEventToListeners(event)
    }

    /**
     * 端末の軸を世界の軸（東・北・上）へ移す 3×3。行優先で 9 個。
     *
     * 端末の −Z（背面）が狙った向き、＋Y（画面の上端）はできるだけ天頂側を向くように取り、
     * そこから Z まわりに [rollDeg] だけ回す。
     */
    private fun deviceToWorld(azimuthDeg: Double, elevationDeg: Double, rollDeg: Double): DoubleArray {
        val az = Math.toRadians(azimuthDeg)
        val el = Math.toRadians(elevationDeg)
        val gaze = doubleArrayOf(sin(az) * cos(el), cos(az) * cos(el), sin(el))
        val z = doubleArrayOf(-gaze[0], -gaze[1], -gaze[2])
        // 天頂から z 成分を抜いたものが画面の上端
        val dot = z[2]
        val y = normalized(doubleArrayOf(-dot * z[0], -dot * z[1], 1.0 - dot * z[2]))
        val upright = doubleArrayOf(
            y[1] * z[2] - y[2] * z[1],
            y[2] * z[0] - y[0] * z[2],
            y[0] * z[1] - y[1] * z[0],
        )
        val c = cos(Math.toRadians(rollDeg))
        val s = sin(Math.toRadians(rollDeg))
        val x = DoubleArray(3) { c * upright[it] + s * y[it] }
        val rolledY = DoubleArray(3) { -s * upright[it] + c * y[it] }
        return doubleArrayOf(x[0], rolledY[0], z[0], x[1], rolledY[1], z[1], x[2], rolledY[2], z[2])
    }

    /** 世界の向きのベクトルを端末の軸で読み直す（回転行列の転置をかける） */
    private fun intoDevice(r: DoubleArray, east: Double, north: Double, up: Double) = floatArrayOf(
        (r[0] * east + r[3] * north + r[6] * up).toFloat(),
        (r[1] * east + r[4] * north + r[7] * up).toFloat(),
        (r[2] * east + r[5] * north + r[8] * up).toFloat(),
    )

    /** 回転行列 → 回転ベクトル（x, y, z, w）。SDK が配ってくるのはこの形 */
    private fun quaternionOf(r: DoubleArray): FloatArray {
        val trace = r[0] + r[4] + r[8]
        val q = DoubleArray(4) // x, y, z, w
        if (trace > 0) {
            val s = sqrt(trace + 1.0) * 2
            q[3] = 0.25 * s
            q[0] = (r[7] - r[5]) / s
            q[1] = (r[2] - r[6]) / s
            q[2] = (r[3] - r[1]) / s
        } else if (r[0] > r[4] && r[0] > r[8]) {
            val s = sqrt(1.0 + r[0] - r[4] - r[8]) * 2
            q[3] = (r[7] - r[5]) / s
            q[0] = 0.25 * s
            q[1] = (r[1] + r[3]) / s
            q[2] = (r[2] + r[6]) / s
        } else if (r[4] > r[8]) {
            val s = sqrt(1.0 + r[4] - r[0] - r[8]) * 2
            q[3] = (r[2] - r[6]) / s
            q[0] = (r[1] + r[3]) / s
            q[1] = 0.25 * s
            q[2] = (r[5] + r[7]) / s
        } else {
            val s = sqrt(1.0 + r[8] - r[0] - r[4]) * 2
            q[3] = (r[3] - r[1]) / s
            q[0] = (r[2] + r[6]) / s
            q[1] = (r[5] + r[7]) / s
            q[2] = 0.25 * s
        }
        return floatArrayOf(q[0].toFloat(), q[1].toFloat(), q[2].toFloat(), q[3].toFloat())
    }

    private fun normalized(v: DoubleArray): DoubleArray {
        val length = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        return if (abs(length) < 1e-9) v else doubleArrayOf(v[0] / length, v[1] / length, v[2] / length)
    }

    private companion object {
        const val GRAVITY = 9.81
        const val FIELD_MICRO_TESLA = 46.0
        const val FIELD_INCLINATION_DEG = 49.0
    }
}
