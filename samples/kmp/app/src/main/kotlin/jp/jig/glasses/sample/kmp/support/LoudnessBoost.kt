package jp.jig.glasses.sample.kmp.support

import android.media.audiofx.LoudnessEnhancer
import android.util.Log

/**
 * 鳴っている音を、つまみの上限より**さらに持ち上げる**。
 *
 * **音の出し先はスマホのスピーカー**（SDK に音声出力 API が無い）で、使う場所は**夜の屋外**。
 * `AudioTrack.setVolume` も TTS の `KEY_PARAM_VOLUME` も `MediaPlayer.setVolume` も
 * **1.0 が上限**なので、つまみを右端にしても足りないときに手が無かった。
 *
 * 素の掛け算で持ち上げると**大きいところが潰れて割れる**ので、
 * 圧縮しながら持ち上げる [LoudnessEnhancer] に任せる。
 *
 * **効かなくても音は今までどおり鳴る。** 効果を作れない端末があるうえ、
 * 音が小さいのは不便だが、**黙るのは機能の欠落**（[jp.jig.glasses.sample.kmp.voice.DeviceVoice]）。
 * 失敗はログに 1 行だけ残して先へ進む。
 *
 * 読み上げ・BGM のどちらからも使うので [jp.jig.glasses.sample.kmp.voice] にも
 * [jp.jig.glasses.sample.kmp.sound] にも置いていない。
 */
class LoudnessBoost {

    private var effect: LoudnessEnhancer? = null

    /**
     * [sessionId] のセッションに効果を付け直す。
     *
     * `AudioTrack` は 1 文ごとに作り直すので、**そのたびに呼ぶ**。
     * セッションを取れなかったとき（`0` や `AudioManager.ERROR`）には付けられないので何もしない。
     */
    fun attach(sessionId: Int) {
        release()
        if (sessionId <= 0) return
        effect = runCatching {
            LoudnessEnhancer(sessionId).apply {
                setTargetGain(TARGET_GAIN_MB)
                enabled = true
            }
        }.getOrElse { e ->
            Log.w(TAG, "音量の持ち上げを付けられない session=$sessionId", e)
            null
        }
    }

    /** 効果を外す。付けたセッションが終わるときに必ず呼ぶ（残すと効果が積み上がる） */
    fun release() {
        runCatching { effect?.release() }
        effect = null
    }

    companion object {
        /**
         * 持ち上げる量[ミリベル]。**+6 dB**（音の大きさの感覚でおよそ 1.5 倍）。
         *
         * つまみが右端でも足りないという話から入れた値で、**実機未確認**。
         * これ以上上げると、圧縮が効いていても声の質が変わってくる。
         */
        const val TARGET_GAIN_MB = 600

        private const val TAG = "LoudnessBoost"
    }
}
