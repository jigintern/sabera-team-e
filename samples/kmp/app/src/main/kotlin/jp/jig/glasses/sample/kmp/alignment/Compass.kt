package jp.jig.glasses.sample.kmp.alignment

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import jp.jig.glasses.sample.kmp.sky.DEG
import jp.jig.glasses.sample.kmp.sky.Site

/**
 * スマホの方位。段階 1（スマホ同期）で使う。
 *
 * スマホの磁気が示すのは「スマホの方位」であってグラスの方位ではないので、常時融合はできない。
 * 顔の前にかざしてもらった一瞬だけ「スマホの背面方向 ≒ 視線方向」とみなして値を移す。
 */
class Compass(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationVector: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val accelerometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val magnetometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    private val rotation = FloatArray(9)
    private val remapped = FloatArray(9)
    private val orientation = FloatArray(3)

    // 歪みの検証用。融合値では磁場そのものが見えないので生の 2 軸を別に取る
    private val gravity = FloatArray(3)
    private val magnetic = FloatArray(3)
    private val dipRotation = FloatArray(9)
    private val dipInclination = FloatArray(9)
    private var gravityReady = false
    private var magneticReady = false

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

    /** 測った磁場の強さ[µT]。土地の期待値と比べて歪みを見るためのもの */
    @Volatile
    var fieldMicroTesla: Double? = null
        private set

    /** 測った伏角[度]。磁場が水平面から何度下を向いているか */
    @Volatile
    var fieldInclinationDeg: Double? = null
        private set

    /** 磁気センサーの信頼度。`SensorManager.SENSOR_STATUS_*` */
    @Volatile
    var accuracy: Int = SensorManager.SENSOR_STATUS_UNRELIABLE
        private set

    fun start() {
        // **登録し直しでは古い信頼度を持ち越さない。** 磁力計が初期値を配るまでは
        // 「信用できない」から始める（配られなければ [CompassGate] が時間で逃がす）
        accuracy = SensorManager.SENSOR_STATUS_UNRELIABLE
        rotationVector?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        accelerometer?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        magnetometer?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> updateOrientation(event)
            Sensor.TYPE_ACCELEROMETER -> {
                event.values.copyInto(gravity, endIndex = 3)
                gravityReady = true
                updateField()
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                event.values.copyInto(magnetic, endIndex = 3)
                magneticReady = true
                updateField()
            }
        }
    }

    private fun updateOrientation(event: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(rotation, event.values)
        // 端末を立てて顔の前にかざす姿勢を想定する。この差し替えを忘れると
        // 画面が上を向いている前提の方位が返り、90° ずれる
        SensorManager.remapCoordinateSystem(rotation, SensorManager.AXIS_X, SensorManager.AXIS_Z, remapped)
        SensorManager.getOrientation(remapped, orientation)
        magneticHeadingDeg = ((orientation[0] * DEG) % 360.0 + 360.0) % 360.0
        // getOrientation のピッチは端末の上端が下がる向きが正。見上げを正に揃える
        pitchDeg = -orientation[1] * DEG
    }

    /**
     * 磁場の強さと伏角を出す。伏角は重力と磁場から作った傾斜行列から取る
     * （生の磁場ベクトルだけでは、端末がどう傾いているか分からず水平面が決まらない）。
     */
    private fun updateField() {
        if (!gravityReady || !magneticReady) return
        fieldMicroTesla = kotlin.math.sqrt(
            (magnetic[0] * magnetic[0] + magnetic[1] * magnetic[1] + magnetic[2] * magnetic[2]).toDouble(),
        )
        if (SensorManager.getRotationMatrix(dipRotation, dipInclination, gravity, magnetic)) {
            fieldInclinationDeg = SensorManager.getInclination(dipInclination).toDouble() * DEG
        }
    }

    /**
     * **3 つのセンサーを 1 つのリスナーで購読している**ので、種別を見ないと取り違える。
     *
     * 加速度計はたいてい `SENSOR_STATUS_ACCURACY_HIGH` を返すので、それが磁力計の低い値を
     * 上書きすると「8 の字に振ってください」が素通りする。しかも初期値を配る順は
     * 登録のたびに変わるので、**画面に入り直すたびに挙動が変わった**（#65）。
     */
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        if (sensor?.type != Sensor.TYPE_MAGNETIC_FIELD) return
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
        val heading = magneticHeadingDeg ?: return null
        return ((heading + expectedField(site, epochMillis).declination) % 360.0 + 360.0) % 360.0
    }

    /**
     * 磁気が歪んでいないかを土地の期待値と比べて返す。まだ生の 2 軸が揃っていなければ null。
     *
     * OS の信頼度は「キャリブレーションが済んだか」しか言わないので、
     * **ケースの磁石や鉄骨の近くでも「高い」のまま返る**。そこを外から検証する。
     */
    fun quality(site: Site, epochMillis: Long): MagneticQuality? {
        val strength = fieldMicroTesla ?: return null
        val dip = fieldInclinationDeg ?: return null
        val expected = expectedField(site, epochMillis)
        return magneticQuality(
            measuredMicroTesla = strength,
            measuredInclinationDeg = dip,
            // GeomagneticField はナノテスラで返す
            expectedMicroTesla = expected.fieldStrength / 1_000.0,
            expectedInclinationDeg = expected.inclination.toDouble(),
        )
    }

    private fun expectedField(site: Site, epochMillis: Long) = GeomagneticField(
        site.latDeg.toFloat(),
        site.lonDeg.toFloat(),
        0f,
        epochMillis,
    )
}
