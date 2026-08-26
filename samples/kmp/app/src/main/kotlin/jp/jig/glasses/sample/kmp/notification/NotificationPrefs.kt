package jp.jig.glasses.sample.kmp.notification

import android.content.Context

/**
 * 通知の設定を覚えておく（#70）。
 *
 * **既定は切ってある。** `MainActivity` が起動時に BLE の権限ダイアログを出すので、
 * そこへ通知の許可を積むと両方とも読まずに閉じられる。**ホームで自分で入れてもらう。**
 */
class NotificationPrefs(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 流星群の予告を出すか */
    var meteorShowerEnabled: Boolean
        get() = prefs.getBoolean(KEY_METEOR_SHOWER, false)
        set(value) = prefs.edit().putBoolean(KEY_METEOR_SHOWER, value).apply()

    private companion object {
        const val PREFS_NAME = "starmap_notification"
        const val KEY_METEOR_SHOWER = "meteor_shower_enabled"
    }
}
