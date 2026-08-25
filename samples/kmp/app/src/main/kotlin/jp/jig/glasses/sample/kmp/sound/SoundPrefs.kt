package jp.jig.glasses.sample.kmp.sound

import android.content.Context

/**
 * 音量の設定を覚えておく。
 *
 * 覚えるのは、**適正値が場所と機種で変わるうえ、合わせ直すのが夜の屋外になる**から。
 * 毎回やり直させると、暗い中でつまみを探すことになる。
 */
class SoundPrefs(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 読み上げの音量 0..1 */
    var voiceVolume: Float
        get() = prefs.getFloat(KEY_VOICE, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_VOICE, value.coerceIn(0f, 1f)).apply()

    /** BGM の音量 0..1 */
    var bgmVolume: Float
        get() = prefs.getFloat(KEY_BGM, Bgm.DEFAULT_VOLUME)
        set(value) = prefs.edit().putFloat(KEY_BGM, value.coerceIn(0f, 1f)).apply()

    var bgmEnabled: Boolean
        get() = prefs.getBoolean(KEY_BGM_ON, true)
        set(value) = prefs.edit().putBoolean(KEY_BGM_ON, value).apply()

    /**
     * 指名された曲。null なら場面まかせ（おまかせ）。
     *
     * **知らない名前は null 扱い**にする（[BgmTrack.byName]）。曲を差し替えたあとに
     * 古い名前が残っていても、おまかせに戻るだけで落ちない。
     */
    var bgmTrack: BgmTrack?
        get() = BgmTrack.byName(prefs.getString(KEY_BGM_TRACK, null))
        set(value) = prefs.edit().putString(KEY_BGM_TRACK, value?.name).apply()

    /**
     * AI 音声で喋るか。false なら端末の読み上げ。
     *
     * **ここを覚えないと、端末の読み上げを選んだ人が毎回選び直すことになる。**
     * 音量と同じで、選ぶ理由（通信を使いたくない・声の好み）は場所ごとに変わらない。
     */
    var aiVoice: Boolean
        get() = prefs.getBoolean(KEY_AI_VOICE, true)
        set(value) = prefs.edit().putBoolean(KEY_AI_VOICE, value).apply()

    private companion object {
        const val PREFS_NAME = "starmap_sound"
        const val KEY_VOICE = "voice_volume"
        const val KEY_BGM = "bgm_volume"
        const val KEY_BGM_ON = "bgm_enabled"
        const val KEY_BGM_TRACK = "bgm_track"
        const val KEY_AI_VOICE = "ai_voice"
    }
}
