package jp.jig.glasses.sample.kmp.satellite

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

private const val DEG = 180.0 / PI
private const val RAD = PI / 180.0

/** 地球の形。TLE は wgs72 で作られているが、地上の位置は wgs84 で持つ（測位がそちらのため） */
private const val WGS84_A = 6378.137
private const val WGS84_F = 1.0 / 298.257223563

/** 観測地から見た衛星。星と違って**距離が効く**ので、視差を無視できない */
class Topocentric(
    /** 方位角[度]。真北 0°・東回り。`sky/Coordinates.kt` と同じ約束 */
    val azDeg: Double,
    /** 高度[度]。地平線が 0 */
    val altDeg: Double,
    /** 観測地からの距離[km] */
    val rangeKm: Double,
)

/** 衛星の真下の点。「いまどこの上空にいるか」を出すのに使う */
class SubPoint(val latDeg: Double, val lonDeg: Double, val altitudeKm: Double)

/**
 * 観測地。緯度経度は測位から、高さは海抜[km]。
 *
 * **星の座標変換（`sky/Coordinates.kt`）とは別物。** 星は無限遠なので向きだけで決まるが、
 * 衛星は数百 km しか離れていないので、**観測地の位置ベクトルを引かないと数度ずれる**。
 */
class Observer(val latDeg: Double, val lonDeg: Double, val heightKm: Double = 0.0) {

    /** 地球固定座標での観測地の位置[km] */
    private fun positionEcef(): DoubleArray {
        val lat = latDeg * RAD
        val sinLat = sin(lat)
        val e2 = WGS84_F * (2.0 - WGS84_F)
        val n = WGS84_A / sqrt(1.0 - e2 * sinLat * sinLat)
        val lon = lonDeg * RAD
        return doubleArrayOf(
            (n + heightKm) * cos(lat) * cos(lon),
            (n + heightKm) * cos(lat) * sin(lon),
            (n * (1.0 - e2) + heightKm) * sinLat,
        )
    }

    /**
     * TEME の位置を、この観測地から見た方位・高度・距離に直す。
     *
     * TEME → 地球固定はグリニッジ恒星時ぶんの回転で足りる。
     * 極運動は数十メートルの話なので、視野 35° を 528 画素で描く用途では効かない。
     */
    fun look(state: TemeState, epochMillis: Long): Topocentric {
        val gmst = Sgp4.gstime(julianDate(epochMillis))
        val cosG = cos(gmst)
        val sinG = sin(gmst)
        // TEME → 地球固定（Z 軸まわりに -gmst 回す）
        val xe = state.x * cosG + state.y * sinG
        val ye = -state.x * sinG + state.y * cosG
        val ze = state.z

        val site = positionEcef()
        val dx = xe - site[0]
        val dy = ye - site[1]
        val dz = ze - site[2]

        // 地球固定 → 観測地の ENU（東・北・天頂）
        val lat = latDeg * RAD
        val lon = lonDeg * RAD
        val east = -sin(lon) * dx + cos(lon) * dy
        val north = -sin(lat) * cos(lon) * dx - sin(lat) * sin(lon) * dy + cos(lat) * dz
        val up = cos(lat) * cos(lon) * dx + cos(lat) * sin(lon) * dy + sin(lat) * dz

        val range = sqrt(dx * dx + dy * dy + dz * dz)
        val az = (atan2(east, north) * DEG + 360.0) % 360.0
        val alt = asin((up / range).coerceIn(-1.0, 1.0)) * DEG
        return Topocentric(az, alt, range)
    }

    companion object {
        /** ミリ秒 → ユリウス日 */
        fun julianDate(epochMillis: Long): Double = 2440587.5 + epochMillis / 86_400_000.0

        /**
         * 衛星の真下の点と高度。緯度は扁平を考えて繰り返しで解く。
         * 3 回も回せば 1m を切るので、打ち切りで十分。
         */
        fun subPoint(state: TemeState, epochMillis: Long): SubPoint {
            val gmst = Sgp4.gstime(julianDate(epochMillis))
            val xe = state.x * cos(gmst) + state.y * sin(gmst)
            val ye = -state.x * sin(gmst) + state.y * cos(gmst)
            val ze = state.z

            val lon = ((atan2(ye, xe) * DEG + 540.0) % 360.0) - 180.0
            val r = sqrt(xe * xe + ye * ye)
            val e2 = WGS84_F * (2.0 - WGS84_F)
            var lat = atan2(ze, r)
            var n = WGS84_A
            repeat(5) {
                val sinLat = sin(lat)
                n = WGS84_A / sqrt(1.0 - e2 * sinLat * sinLat)
                lat = atan2(ze + n * e2 * sinLat, r)
            }
            val altitude = if (abs(cos(lat)) > 1e-9) r / cos(lat) - n else abs(ze) - n * (1.0 - e2)
            return SubPoint(lat * DEG, lon, altitude)
        }
    }
}
