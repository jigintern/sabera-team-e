package jp.jig.glasses.sample.kmp.glass

import android.content.Context

/**
 * 画角とロール追従の設定。
 *
 * **画角はグラス（パネルと光学系）の定数**で、かけ直しても変わらない。ただし実測していないので
 * 既定は仮の値のまま。屋外で壁の目標に合わせて実測した値を入れられるよう、
 * **端末ごとに覚える**（測り直したかどうかも残す）。
 *
 * ロール追従は実機で軸の割り当てを確かめるまで既定オフ。
 */
class GlassGeometryPrefs(
    context: Context,
    deviceIdentifier: String,
) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val keyPrefix = deviceIdentifier

    fun load(defaultFovDeg: Double): StoredGlassGeometry = StoredGlassGeometry(
        fovDeg = clampFov(prefs.getFloat(key(KEY_FOV), defaultFovDeg.toFloat()).toDouble()),
        measured = prefs.getBoolean(key(KEY_MEASURED), false),
        rollFollow = prefs.getBoolean(key(KEY_ROLL), false),
    )

    fun saveFov(fovDeg: Double) {
        prefs.edit()
            .putFloat(key(KEY_FOV), clampFov(fovDeg).toFloat())
            .putBoolean(key(KEY_MEASURED), true)
            .apply()
    }

    fun saveRollFollow(enabled: Boolean) {
        prefs.edit().putBoolean(key(KEY_ROLL), enabled).apply()
    }

    private fun key(name: String): String = "$keyPrefix:$name"

    companion object {
        const val MIN_FOV_DEG = 20.0
        const val MAX_FOV_DEG = 50.0

        fun clampFov(fovDeg: Double): Double = fovDeg.coerceIn(MIN_FOV_DEG, MAX_FOV_DEG)

        private const val PREFS_NAME = "glass_geometry"
        private const val KEY_FOV = "fov"
        private const val KEY_MEASURED = "fov_measured"
        private const val KEY_ROLL = "roll_follow"
    }
}

/** 覚えている画角と、実測済みかどうか */
data class StoredGlassGeometry(
    val fovDeg: Double,
    val measured: Boolean,
    val rollFollow: Boolean,
)
