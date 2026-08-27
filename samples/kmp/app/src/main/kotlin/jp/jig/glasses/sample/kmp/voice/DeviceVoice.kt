package jp.jig.glasses.sample.kmp.voice

import android.content.Context
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import jp.jig.glasses.sample.kmp.support.LoudnessBoost
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * **端末の読み上げ**（`TextToSpeech`）で喋る。[CloudVoice] が使えないときの落とし先。
 *
 * SDK に音声出力 API が無いので、音はスマホから鳴らす。夜の屋外でスピーカーから鳴らせば、
 * グラスをかけていない同伴者にも聞こえる。星を見に行くのは複数人のことが多く、
 * グラスは 1 人しかかけられない（30_app-flow.md）。
 *
 * **棒読みでも喋るほうが上。** 声の質は雰囲気の問題だが、黙るのは機能の欠落。
 */
class DeviceVoice(context: Context) : Voice, VoiceStatus {

    /** 初期化が終わる前に来た発話。捨てるとタップ直後の「〇〇座ですね」が消える */
    private val pending = ArrayList<String>()

    /**
     * 読み上げの音量 0..1。設定パネルのつまみから来る。
     *
     * `TextToSpeech` に音量の持ち合わせは無く、**発話ごとに Bundle で渡す**しかない。
     */
    @Volatile
    var volume: Float = 1.0f

    /**
     * 読み上げを鳴らす音声セッション。**持ち上げ（[LoudnessBoost]）を付けるためだけ**に自分で作る。
     *
     * `KEY_PARAM_VOLUME` も 1.0 が上限なので、屋外で足りないぶんはここから上げるしかない。
     * **エンジンによっては指定を無視する**が、そのときも音量が上がらないだけで今までどおり鳴る。
     */
    private val sessionId: Int = runCatching {
        context.applicationContext.getSystemService(AudioManager::class.java)
            ?.generateAudioSessionId() ?: AudioManager.ERROR
    }.getOrDefault(AudioManager.ERROR)

    private val boost = LoudnessBoost().apply { attach(sessionId) }

    @Volatile
    private var ready = false

    /**
     * エンジンへ積んである数。**0 になったときだけ「喋り終わった」**とみなす。
     *
     * `onDone` で `tts.isSpeaking` を入れていたときは、**文と文の間で false を返す**ことがあり、
     * 解説を文ごとに積む [Voice.add] の途中で「終わった」ことになっていた。
     * 待っている側（ガイドの進行役・解説画面の秒読み）はそこで先へ進むので、
     * **読み上げの途中で解説が畳まれる**（#121）。数えていれば途中で 0 にならない。
     */
    private val queued = AtomicInteger(0)

    /**
     * 言い直し（[say]）と [stop] で進める世代。**捨てたぶんの完了通知を数に入れない。**
     *
     * `QUEUE_FLUSH` と `stop` は積んであった発話を落とすが、`UtteranceProgressListener` の
     * `onStop` は既定で `onDone` を呼ぶので、**捨てたはずの発話の「終わった」が後から届く**。
     * 世代を見ずに数えると、その 1 通で新しい発話の数まで 0 に戻ってしまう。
     */
    private val generation = AtomicInteger(0)

    /** 発話に付ける通し番号。世代と組にして、どの発話の通知かを見分ける */
    private val sequence = AtomicInteger(0)

    private val _speaking = MutableStateFlow(false)
    override val speaking: StateFlow<Boolean> = _speaking

    /** 音が出たかどうか。エンジンの `onStart` が来てから立てる（積んだ時点では立てない） */
    private val _sounding = MutableStateFlow(false)
    override val sounding: StateFlow<Boolean> = _sounding

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
        selectMaleVoice()
        ready = true
        _available.value = true
        synchronized(pending) {
            pending.forEach { enqueue(it, flush = false) }
            pending.clear()
        }
    }

    /**
     * 日本語の**男性の声**を選ぶ。無ければ既定のまま。
     *
     * AI 音声（OpenAI）が男性の声なので、**圏外で 1 文だけ端末の読み上げに落ちたときに
     * 声が女性に入れ替わって聞こえる**（2026-08-22 実機）。同じ解説の中で声が変わると、
     * 別の人が喋り出したように聞こえて話が切れる。完全には揃わないが、性別は合わせる。
     */
    private fun selectMaleVoice() {
        val male = runCatching {
            tts.voices.orEmpty()
                .filter { it.locale.language == Locale.JAPANESE.language }
                // 圏外で使う声なので、端末に入っているものだけ
                .filterNot { it.isNetworkConnectionRequired }
                .firstOrNull { voice ->
                    val name = voice.name.lowercase()
                    // **"female" は "male" を含む。** 先に弾かないと女性の声を選んでしまう
                    "female" !in name && MALE_HINTS.any { it in name }
                }
        }.getOrNull() ?: return
        runCatching { tts.voice = male }
            .onSuccess { Log.i(TAG, "端末の読み上げに男性の声を選んだ: ${male.name}") }
    }

    init {
        tts.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    if (!isLive(utteranceId)) return
                    _speaking.value = true
                    _sounding.value = true
                }

                override fun onDone(utteranceId: String?) = finishOne(utteranceId)

                @Deprecated("引数なしの onError は API 21 で置き換えられたが、抽象なので実装が要る")
                override fun onError(utteranceId: String?) = finishOne(utteranceId)

                override fun onError(utteranceId: String?, errorCode: Int) {
                    Log.e(TAG, "読み上げに失敗 id=$utteranceId code=$errorCode")
                    finishOne(utteranceId)
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
            if (sessionId > 0) putInt(TextToSpeech.Engine.KEY_PARAM_SESSION_ID, sessionId)
        }
        // **捨てるぶんと数を切り離す。** QUEUE_FLUSH で落とした発話の完了通知は
        // 後から届くので、世代を進めて数に入れないようにする
        if (flush) {
            generation.incrementAndGet()
            queued.set(0)
        }
        val id = "sabera-${generation.get()}-${sequence.incrementAndGet()}"
        // **数を先に増やす。** 立ててから増やすと、その隙間に来た onDone が 0 と見て倒す
        queued.incrementAndGet()
        _speaking.value = true
        if (tts.speak(text, mode, params, id) != TextToSpeech.SUCCESS) {
            // 積めなかったぶんの完了通知は来ない
            Log.e(TAG, "読み上げを積めなかった")
            finishOne(id)
        }
    }

    /** この発話がいまの世代のものか。捨てたぶんの通知は数えない */
    private fun isLive(utteranceId: String?): Boolean =
        utteranceId?.substringAfter('-')?.substringBefore('-')?.toIntOrNull() == generation.get()

    /** 1 本ぶん終わった。**最後の 1 本が終わったときだけ**待機に戻す */
    private fun finishOne(utteranceId: String?) {
        if (!isLive(utteranceId)) return
        // 音が出ているのは onStart から onDone までの間だけ（字幕はこちらに合わせている）
        _sounding.value = false
        if (queued.decrementAndGet() <= 0) {
            queued.set(0)
            _speaking.value = false
        }
    }

    override fun stop() {
        synchronized(pending) { pending.clear() }
        tts.stop()
        // 止めたぶんの完了通知が後から届くので、世代ごと切り離す
        generation.incrementAndGet()
        queued.set(0)
        _speaking.value = false
        _sounding.value = false
    }

    /** 画面を離れるときに呼ぶ。呼ばないとエンジンへの接続が残る */
    fun shutdown() {
        stop()
        boost.release()
        tts.shutdown()
    }

    private companion object {
        /**
         * 声の名前に入る、男性を表す綴り。
         *
         * **エンジンによっては名前に性別が入っていない**（Google の日本語は
         * ja-jp-x-jab のような綴りで、どれが男性かは公開されていない）。
         * その場合はここで選べないので、端末の既定のままになる。
         */
        private val MALE_HINTS = listOf("male", "-m-", "_m_", "man")

        const val TAG = "DeviceVoice"

        /** 初期化を待つ間に積む上限。初期化が返ってこない端末で溜め込まない */
        const val MAX_PENDING = 4
    }
}
