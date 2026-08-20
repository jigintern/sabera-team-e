package jp.jig.glasses.sample.kmp.starmap

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/**
 * スマホの方位。段階 1（スマホ同期）で使う。
 *
 * スマホの磁気が示すのは「スマホの方位」であってグラスの方位ではないので、常時融合はできない。
 * 顔の前にかざしてもらった一瞬だけ「スマホの背面方向 ≒ 視線方向」とみなして値を移す。
 */
class Compass(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationVector: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    private val rotation = FloatArray(9)
    private val remapped = FloatArray(9)
    private val orientation = FloatArray(3)

    /** 背面カメラが向いている方角[度]。磁北基準・北 = 0° の東回り。まだ取れていなければ null */
    @Volatile
    var magneticHeadingDeg: Double? = null
        private set

    /**
     * 背面が向いている仰角[度]。上向きが正で、グラスの `pitchDegrees` と符号を揃えてある。
     *
     * 方位合わせでは「スマホの画面が視線に正対しているか」を見たい。正対していれば
     * スマホの仰角とグラスの仰角が一致するので、その差で正対の度合いを測る。
     */
    @Volatile
    var pitchDeg: Double? = null
        private set

    /** 磁気センサーの信頼度。`SensorManager.SENSOR_STATUS_*` */
    @Volatile
    var accuracy: Int = SensorManager.SENSOR_STATUS_UNRELIABLE
        private set

    val available: Boolean get() = rotationVector != null

    fun start() {
        rotationVector?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        SensorManager.getRotationMatrixFromVector(rotation, event.values)
        // 端末を立てて顔の前にかざす姿勢を想定する。この差し替えを忘れると
        // 画面が上を向いている前提の方位が返り、90° ずれる
        SensorManager.remapCoordinateSystem(rotation, SensorManager.AXIS_X, SensorManager.AXIS_Z, remapped)
        SensorManager.getOrientation(remapped, orientation)
        magneticHeadingDeg = ((orientation[0] * DEG) % 360.0 + 360.0) % 360.0
        // getOrientation のピッチは端末の上端が下がる向きが正。見上げを正に揃える
        pitchDeg = -orientation[1] * DEG
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        this.accuracy = accuracy
    }

    /** 磁気センサーの信頼度を日本語で。低いまま合わせても、その誤差がそのまま星図に乗る */
    fun accuracyText(): String = when (accuracy) {
        SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> "高い"
        SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> "ふつう"
        SensorManager.SENSOR_STATUS_ACCURACY_LOW -> "低い（8 の字に振ると上がる）"
        else -> "信用できない（8 の字に振る）"
    }

    /** 磁北から真北へ直す。偏角は日本で 7〜9° あるので、入れないと星図がその分ずれる */
    fun trueHeadingDeg(site: Site, epochMillis: Long): Double? {
        val magnetic = magneticHeadingDeg ?: return null
        val declination = GeomagneticField(
            site.latDeg.toFloat(),
            site.lonDeg.toFloat(),
            0f,
            epochMillis,
        ).declination
        return ((magnetic + declination) % 360.0 + 360.0) % 360.0
    }
}
