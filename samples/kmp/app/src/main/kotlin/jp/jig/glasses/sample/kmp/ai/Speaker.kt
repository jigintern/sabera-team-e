package jp.jig.glasses.sample.kmp.ai

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

/**
 * 喋る先。**[Narrator] を JVM テストで回すために切ってある**
 * （`TextToSpeech` は端末が要るので、テストでは差し替える）。
 */
interface Voice {
    /** 言い直す。前の発話は捨てる */
    fun say(text: String)

    /** 前の発話に続ける */
    fun add(text: String)

    fun stop()
}

/**
 * 読み上げの状態。**画面に出すためだけ**にある。
 *
 * [Voice] と分けているのは、JVM テストの差し替え（[Narrator] の検算）に状態が要らないから。
 */
interface VoiceStatus {
    val speaking: StateFlow<Boolean>

    /** 喋れるか。**使えないまま黙るのがいちばん困る**ので、分からない間は null */
    val available: StateFlow<Boolean?>
}

/**
 * 読み上げ。SDK に音声出力 API が無いので、音はスマホから鳴らす。
 *
 * 夜の屋外でスピーカーから鳴らせば、グラスをかけていない同伴者にも聞こえる。
 * 星を見に行くのは複数人のことが多く、グラスは 1 人しかかけられない（app-flow.md）。
 */
class Speaker(context: Context) : Voice, VoiceStatus {

    /** 初期化が終わる前に来た発話。捨てるとタップ直後の「〇〇座ですね」が消える */
    private val pending = ArrayList<String>()

    /**
     * 読み上げの音量 0..1。設定パネルのつまみから来る。
     *
     * `TextToSpeech` に音量の持ち合わせは無く、**発話ごとに Bundle で渡す**しかない。
     */
    @Volatile
    var volume: Float = 1.0f

    @Volatile
    private var ready = false

    private val _speaking = MutableStateFlow(false)
    override val speaking: StateFlow<Boolean> = _speaking

    private val _available = MutableStateFlow<Boolean?>(null)
    override val available: StateFlow<Boolean?> = _available

    /**
     * 型を明記しているのは、初期化のコールバックが `tts` 自身を触るため
     * （書かないと型推論が循環して通らない）。コールバックはコンストラクタが返ったあとに来る。
     */
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext, ::handleInit)

    private fun handleInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            Log.e(TAG, "TextToSpeech の初期化に失敗 status=$status")
            synchronized(pending) { pending.clear() }
            _available.value = false
            return
        }
        // **言語はここで入れる。** コンストラクタの直後に入れても、エンジンの初期化が
        // 終わっていないので取りこぼす（端末の既定言語のまま日本語を読むことになる）
        val result = tts.setLanguage(Locale.JAPANESE)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.e(TAG, "日本語の音声データが無い result=$result")
            synchronized(pending) { pending.clear() }
            _available.value = false
            return
        }
        ready = true
        _available.value = true
        synchronized(pending) {
            pending.forEach { enqueue(it, flush = false) }
            pending.clear()
        }
    }

    init {
        tts.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    _speaking.value = true
                }

                override fun onDone(utteranceId: String?) {
                    _speaking.value = tts.isSpeaking
                }

                @Deprecated("引数なしの onError は API 21 で置き換えられたが、抽象なので実装が要る")
                override fun onError(utteranceId: String?) {
                    _speaking.value = false
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    Log.e(TAG, "読み上げに失敗 id=$utteranceId code=$errorCode")
                    _speaking.value = false
                }
            },
        )
    }

    override fun say(text: String) = speak(text, flush = true)

    /** 前の発話に続ける。「〇〇座ですね」のあとに解説を足すときに使う */
    override fun add(text: String) = speak(text, flush = false)

    private fun speak(text: String, flush: Boolean) {
        if (text.isBlank()) return
        // 使えないと分かっているなら積まない。溜め続けても鳴らないので捨てる
        if (_available.value == false) return
        if (!ready) {
            // 初期化は非同期。端末によっては 1 秒近くかかるので、積んでおいて後から流す
            synchronized(pending) {
                if (flush) pending.clear()
                pending += text
                while (pending.size > MAX_PENDING) pending.removeAt(0)
            }
            return
        }
        enqueue(text, flush)
    }

    private fun enqueue(text: String, flush: Boolean) {
        val mode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume.coerceIn(0f, 1f))
        }
        _speaking.value = true
        tts.speak(text, mode, params, "sabera-${text.hashCode()}")
    }

    override fun stop() {
        synchronized(pending) { pending.clear() }
        tts.stop()
        _speaking.value = false
    }

    /** 画面を離れるときに呼ぶ。呼ばないとエンジンへの接続が残る */
    fun shutdown() {
        stop()
        tts.shutdown()
    }

    private companion object {
        const val TAG = "Speaker"

        /** 初期化を待つ間に積む上限。初期化が返ってこない端末で溜め込まない */
        const val MAX_PENDING = 4
    }
}
