package jp.jig.glasses.sample.kmp.glass

import android.content.Context

/**
 * SDK から現在値を読み戻せないため、
 * このアプリが最後に送った値をグラスごとに覚える。
 */
class GlassBrightnessPrefs(
    context: Context,
    deviceIdentifier: String,
) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val keyPrefix = deviceIdentifier

    fun load(): StoredGlassBrightness = StoredGlassBrightness(
        configured = prefs.getBoolean(key(KEY_CONFIGURED), false),
        level = GlassBrightness.normalize(prefs.getInt(key(KEY_LEVEL), GlassBrightness.DEFAULT_LEVEL)),
    )

    fun save(level: Int) {
        prefs.edit()
            .putBoolean(key(KEY_CONFIGURED), true)
            .putInt(key(KEY_LEVEL), GlassBrightness.normalize(level))
            .apply()
    }

    private fun key(name: String): String = "$keyPrefix:$name"

    private companion object {
        const val PREFS_NAME = "glass_brightness"
        const val KEY_CONFIGURED = "configured"
        const val KEY_LEVEL = "level"
    }
}
