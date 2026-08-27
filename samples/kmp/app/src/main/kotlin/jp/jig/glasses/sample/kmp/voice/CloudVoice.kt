package jp.jig.glasses.sample.kmp.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import jp.jig.glasses.sample.kmp.openai.OpenAiSpeech
import jp.jig.glasses.sample.kmp.openai.Pcm16FrameAssembler
import jp.jig.glasses.sample.kmp.support.LoudnessBoost
import jp.jig.glasses.sample.kmp.support.SingleFlight
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * AI 音声で喋る。作れないときは端末の読み上げ（[DeviceVoice]）に落とす。
 *
 * 落とし先を必ず持つのは、**夜の屋外は電波が悪い**うえに、
 * **タップして無反応が一番よくない**（31_gestures.md）から。声の質は雰囲気の問題だが、
 * 黙るのは機能の欠落になる。
 *
 * 発話は 1 本ずつ順に鳴らす。[say] は積んであるものを捨てて言い直し、[add] は後ろに続ける
 * （「〇〇座ですね」→ 解説、という narration.Narrator の組み立てに合わせている）。
 */
class CloudVoice(
    context: Context,
    private val speech: OpenAiSpeech,
    private val fallback: DeviceVoice,
    caller: CoroutineScope,
    private val log: (String, Boolean) -> Unit,
) : Voice, VoiceStatus {

    /**
     * 声のための子スコープ。**必ず [SupervisorJob] を挟む。**
     *
     * `async` の失敗は `await` で受け取っても**親スコープを巻き込んでキャンセルする**。
     * 渡ってくるのは画面の `rememberCoroutineScope()` なので、素の子として走らせると
     * **TTS が 1 回失敗しただけで画面のコルーチンが全部死ぬ**。
     * 実機（2026-08-21・圏外）では先読みが 2 回失敗したあと、
     * **6DoF の購読とログの書き出しが道連れで止まり、星図が二度と更新されなくなった**。
     *
     * 親を渡してあるので、画面を離れたときのキャンセルは今までどおり伝わる。
     */
    private val scope = CoroutineScope(
        caller.coroutineContext + SupervisorJob(caller.coroutineContext[Job]),
    )

    private class Utterance(
        val text: String,
        val flush: Boolean,
        val generation: Int,
        val prepared: Deferred<ByteArray>,
    )

    /** AI 音声を使うか。**声の好みは実機で聴かないと決まらない**ので設定パネルから切り替える */
    private val _enabled = MutableStateFlow(true)
    var enabled: Boolean
        get() = _enabled.value
        set(value) { _enabled.value = value }

    /** 直しても直らない失敗（キーが違う等）を踏んだか。踏んだら以後は端末の読み上げだけ使う */
    private val _broken = MutableStateFlow(false)

    /**
     * **1 回の解説の中で声を入れ替えない。**
     *
     * 解説は「〇〇座ですね」＋各文を続けて積む。1 文だけ通信に失敗すると、そこから
     * 端末の読み上げに落ちるので、**同じ解説の途中で別の人が喋り出したように聞こえる**
     * （実機で踏んだ・2026-08-22）。落ちたらその解説は最後まで端末の読み上げで通す。
     */
    @Volatile
    private var fellBack = false

    /**
     * 失敗したあと、AI 音声を試し直さない時刻まで。
     *
     * 圏外では**解説のたびに「1 文目は AI・2 文目から端末」**を繰り返す。
     * 1 度落ちたらしばらく端末の読み上げだけにすれば、**次の解説は頭から同じ声**になる。
     * キャッシュに当たるぶんまで捨てることになるが、**声が揃うほうを採る**。
     */
    @Volatile
    private var coolDownUntil = 0L

    /**
     * 読み上げの音量 0..1。BGM とのつり合いは場所と機種で変わるので、ユーザーが決める。
     *
     * 落とし先（端末の読み上げ）にも同じ値を渡す。**退避したときに音量が飛ぶと事故に聞こえる**。
     */
    @Volatile
    private var volumeValue = 1.0f
    var volume: Float
        get() = volumeValue
        set(value) {
            volumeValue = value.coerceIn(0f, 1f)
            fallback.volume = volumeValue
            runCatching { track?.setVolume(volumeValue) }
        }

    /** 鳴っている最中の AudioTrack。つまみを動かしたその場で効かせるために持つ */
    @Volatile
    private var track: AudioTrack? = null

    /**
     * つまみの上限（1.0）より上へ持ち上げる（[LoudnessBoost]）。
     *
     * **AudioTrack は 1 文ごとに作り直す**ので、効果も文ごとに付け直して外す。
     */
    private val boost = LoudnessBoost()

    private val cacheDir = File(context.cacheDir, CACHE_DIR)
    private val queue = Channel<Utterance>(Channel.UNLIMITED)

    /** TTS通信は1本だけ。現在の文を再生している間に次の文を先読みする。 */
    private val preparationGate = Semaphore(1)
    private val preparations = ConcurrentHashMap.newKeySet<Deferred<ByteArray>>()

    /** 同じ「〇〇座ですね」の先読みを、星図の再描画ごとに重複して送らない。 */
    private val warmFlights = SingleFlight<String>(scope)

    /** [say] で世代を進める。積んであった古い発話は worker が読み飛ばす */
    @Volatile
    private var generation = 0

    /** 積んである数。0 になった時点で「喋り終わった」と判断する */
    private val queued = AtomicInteger(0)

    /**
     * いま鳴らしている 1 本。**worker が書き、画面側の [stop] / [shutdown] が読んで止める**ので、
     * 同じクラスの他のフィールドと同じく `@Volatile` が要る（付け忘れていた）。
     */
    @Volatile
    private var current: Job? = null

    private val _speaking = MutableStateFlow(false)

    /**
     * **AudioTrack が鳴っている間だけ** true。合成の待ちは含めない。
     *
     * [speaking] は積んだ時点で true になる（そうしないと画面側が合成中を「終わった」と読む）。
     * 一方、**字幕を音に合わせるには鳴り出した時刻が要る**（#40）。生成に 1〜2 秒かかると、
     * [speaking] を起点にめくった字幕は 1 枚ぶん先へ行ってしまう。
     */
    private val _sounding = MutableStateFlow(false)

    /**
     * 鳴っているか。**端末の読み上げに落ちた発話も含める**
     * （画面側は 1 本の Flow だけを見て「終わったら待機に戻す」を判断している）。
     */
    override val speaking: StateFlow<Boolean> =
        combine(_speaking, fallback.speaking) { cloud, device -> cloud || device }
            .stateIn(scope, SharingStarted.Eagerly, false)

    override val sounding: StateFlow<Boolean> =
        combine(_sounding, fallback.sounding) { cloud, device -> cloud || device }
            .stateIn(scope, SharingStarted.Eagerly, false)

    /** 喋れるか。AI 音声が使えるなら true、駄目でも端末の読み上げが生きていれば true */
    override val available: StateFlow<Boolean?> =
        combine(_enabled, _broken, fallback.available) { on, broken, device ->
            if (on && !broken && speech.configured) true else device
        }.stateIn(scope, SharingStarted.Eagerly, null)

    init {
        scope.launch {
            for (utterance in queue) {
                if (utterance.generation == generation) {
                    // join で順番を保ちつつ、stop から cancel できるよう子ジョブに切る
                    val job = scope.launch { speakOne(utterance) }
                    current = job
                    job.join()
                }
                if (queued.decrementAndGet() <= 0) _speaking.value = false
            }
        }
    }

    override fun say(text: String) {
        if (text.isBlank()) return
        // 言い直しは新しい解説の始まり。ここで声の選び直しをする
        fellBack = false
        if (!usable()) {
            fallback.say(text)
            return
        }
        generation++
        current?.cancel()
        cancelPreparations()
        fallback.stop()
        enqueue(text, flush = true)
    }

    override fun add(text: String) {
        if (text.isBlank()) return
        // **1 文でも端末の読み上げに落ちたら、その解説は最後まで端末で通す**
        if (fellBack || !usable()) {
            fallback.add(text)
            return
        }
        enqueue(text, flush = false)
    }

    override fun stop() {
        // 止めた時点でその解説は終わり。次に喋るぶんは声を選び直す
        // （声の質問は [say] を通らず [add] だけで積むので、ここで戻さないと前回の落ちを引き継ぐ）
        fellBack = false
        generation++
        current?.cancel()
        cancelPreparations()
        fallback.stop()
        _speaking.value = false
        // cancel は非同期なので play() の finally を待たずにここで落とす
        _sounding.value = false
    }

    fun shutdown() {
        stop()
        scope.launch { warmFlights.cancelAll() }
        queue.close()
    }

    /**
     * 鳴らす前に作っておく。タップした瞬間に「〇〇座ですね」を返すため。
     *
     * 短い定型文はキャッシュに残るので、**2 回目からは通信すら要らない**。
     */
    fun warm(text: String) {
        if (!usable() || text.isBlank()) return
        val file = cacheFile(text) ?: return
        if (file.isFile) return
        scope.launch {
            runCatching {
                warmFlights.getOrStart(file.path) { downloadToCache(text, file) }.await()
            }
                .onFailure {
                    // 圏外だと毎フレーム落ちる。**黙って落ちていると原因に辿り着けない**
                    Log.w(TAG, "先読みに失敗 ${it.message}")
                    log("最初の一言の先読みに失敗（${it.message}）", true)
                }
        }
    }

    private fun usable(): Boolean = _enabled.value && !_broken.value && speech.configured &&
        System.currentTimeMillis() >= coolDownUntil

    private fun enqueue(text: String, flush: Boolean) {
        val utteranceGeneration = generation
        val prepared = scope.async(Dispatchers.IO, start = CoroutineStart.LAZY) {
            preparationGate.withPermit {
                if (utteranceGeneration != generation) throw CancellationException("古い発話")
                preparePcm(text)
            }
        }
        preparations += prepared
        prepared.invokeOnCompletion { preparations -= prepared }
        prepared.start()
        val utterance = Utterance(text, flush, utteranceGeneration, prepared)
        // **数を先に増やしてから立てる。** 逆にすると、その隙間で worker が前の 1 本を
        // 数え終えたときに 0 と見えて、**積んだばかりの発話の間ずっと false** になる
        // （待っている側は読み上げ前に先へ進む・#121）
        queued.incrementAndGet()
        // 発話が始まる前に true にしておく。ここが遅れると画面側が「もう終わった」と誤解する
        _speaking.value = true
        if (queue.trySend(utterance).isFailure) {
            queued.decrementAndGet()
            prepared.cancel()
            fallback.say(utterance.text)
        }
    }

    private fun cancelPreparations() {
        preparations.forEach { it.cancel() }
    }

    private suspend fun speakOne(utterance: Utterance) {
        // ここへ来る前に落ちていたら、積んであるぶんも端末の読み上げで通す（声を揃える）
        if (fellBack) {
            speakWithFallback(utterance)
            return
        }
        try {
            play(utterance.prepared.await())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val permanent = (e as? OpenAiSpeech.SpeechException)?.permanent == true
            if (permanent) _broken.value = true
            // **この解説はここから端末の読み上げで通す。** 次の文で AI 音声に戻すと、
            // 同じ解説の中で声が入れ替わって別の人が喋り出したように聞こえる
            fellBack = true
            if (!permanent) coolDownUntil = System.currentTimeMillis() + FAILURE_COOLDOWN_MS
            Log.e(TAG, "AI 音声に失敗", e)
            log(
                if (permanent) {
                    "AI 音声が使えない（${e.message}）。以後は端末の読み上げにする"
                } else {
                    "AI 音声が届かない（${e.message}）。" +
                        "この解説と ${FAILURE_COOLDOWN_MS / 1000} 秒は端末の読み上げで通す"
                },
                true,
            )
            speakWithFallback(utterance)
        }
    }

    /** 端末の読み上げに回す。**鳴り終わるまで待つ**。待たないと次の発話と重なる */
    private suspend fun speakWithFallback(utterance: Utterance) {
        if (utterance.flush) fallback.say(utterance.text) else fallback.add(utterance.text)
        // 完了通知が来ない端末があるので、文の長さから見た上限で必ず抜ける
        val limit = FALLBACK_BASE_MS + utterance.text.length * FALLBACK_PER_CHAR_MS
        withTimeoutOrNull(limit) { fallback.speaking.first { !it } }
    }

    /**
     * 1 本ぶん鳴らす。
     *
     * AudioTrack は発話ごとに作って捨てる。使い回すと停止と再生の状態が絡まるうえ、
     * 生成の数百 ms に対して確保のコストは小さい。
     */
    private suspend fun play(pcm: ByteArray) {
        Pcm16FrameAssembler.requireValid(pcm)
        val track = newTrack(pcm.size).also { this.track = it }
        try {
            val written = track.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
            if (written != pcm.size) {
                throw IOException("AI音声を書き込めない: $written/${pcm.size} bytes")
            }
            val frames = pcm.size / Pcm16FrameAssembler.BYTES_PER_FRAME
            val durationMs = frames * 1_000L / OpenAiSpeech.SAMPLE_RATE
            track.play()
            // ここが「音が出た」時刻。字幕はこれを待ってからめくる（#40）
            _sounding.value = true
            val drained = withTimeoutOrNull(durationMs + DRAIN_MARGIN_MS) {
                while (track.playbackHeadPosition.toLong() < frames) delay(DRAIN_POLL_MS)
                true
            } == true
            log(
                "AI音声再生 ${pcm.size}B・${durationMs}ms・" +
                    "underrun=${track.underrunCount}・session=${track.audioSessionId}" +
                    if (drained) "" else "・再生完了待ちtimeout",
                !drained || track.underrunCount > 0,
            )
            if (!drained) {
                runCatching { track.stop() }
            }
        } finally {
            this.track = null
            _sounding.value = false
            // **効果を先に外す。** セッションが消えたあとに残すと次の 1 文に積み上がる
            boost.release()
            runCatching {
                track.stop()
                track.release()
            }
        }
    }

    private fun newTrack(bytes: Int): AudioTrack {
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(OpenAiSpeech.SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(format)
            .setTransferMode(AudioTrack.MODE_STATIC)
            // 1文を検証し終えてから全量を載せる。通信速度と再生速度を切り離してノイズを防ぐ
            .setBufferSizeInBytes(bytes)
            .build()
            .apply {
                setVolume(volumeValue)
                boost.attach(audioSessionId)
            }
    }

    /** キャッシュまたはAPIから、検証済みの1文ぶんPCMを得る。 */
    private suspend fun preparePcm(text: String): ByteArray {
        val file = cacheFile(text)
        if (file != null && file.isFile) {
            readValidCache(file)?.let { return it }
        }

        // 先読みが走っているなら、その通信結果を共有する。
        // ここで別のTTSリクエストを始めると、弱い回線上で解説文の生成まで奪い合ってしまう
        val pending = file?.let { cached -> warmFlights.current(cached.path) }
        if (pending != null) {
            pending.await()
            if (file.isFile) {
                readValidCache(file)?.let { return it }
            }
        }

        return speech.synthesize(text).also { pcm ->
            if (file != null) runCatching { writeCache(file, pcm) }
        }
    }

    private suspend fun downloadToCache(text: String, file: File) {
        val pcm = speech.synthesize(text)
        withContext(Dispatchers.IO) { writeCache(file, pcm) }
    }

    private fun readValidCache(file: File): ByteArray? = runCatching {
        val pcm = file.readBytes()
        Pcm16FrameAssembler.requireValid(pcm)
        pcm
    }.getOrElse {
        // 壊れたPCMを何度も鳴らさない。キャッシュなので消してAPIから作り直せる
        file.delete()
        null
    }

    private fun writeCache(file: File, pcm: ByteArray) {
        Pcm16FrameAssembler.requireValid(pcm)
        cacheDir.mkdirs()
        val temporary = File(cacheDir, "${file.name}.${System.nanoTime()}.tmp")
        try {
            temporary.writeBytes(pcm)
            runCatching {
                Files.move(
                    temporary.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }.getOrElse {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            prune()
        } finally {
            temporary.delete()
        }
    }

    /**
     * キャッシュ先。**短い定型文だけ**を残す。
     *
     * 「〇〇座ですね」や案内文は何度も同じものを喋るので効くが、解説は毎回違うので
     * 残しても当たらない（PCM は 1 秒 48KB あるので、当たらないものを置く余裕はない）。
     */
    private fun cacheFile(text: String): File? {
        if (text.length > CACHE_MAX_CHARS) return null
        val digest = MessageDigest.getInstance("SHA-1")
            .digest("$CACHE_VERSION|${speech.signature}|$text".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(cacheDir, "$digest.pcm")
    }

    /** 古いものから捨てる。キャッシュは端末が消して構わない場所なので、上限だけ決めておく */
    private fun prune() {
        val files = cacheDir.listFiles()?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (file in files) {
            if (total <= CACHE_MAX_BYTES) break
            total -= file.length()
            file.delete()
        }
    }

    private companion object {
        const val TAG = "CloudVoice"
        const val CACHE_DIR = "tts"

        const val DRAIN_POLL_MS = 50L
        const val DRAIN_MARGIN_MS = 3_000L

        /**
         * キャッシュに残す文の長さの上限。
         *
         * 48 文字にしていたときは、**解説の各文（40〜80 文字）がほとんど残らなかった**。
         * 残らないと先読み（[warm]）も効かないので、圏外では「〇〇座ですね」だけが AI 音声で、
         * **続きが端末の読み上げになって声が入れ替わっていた**。
         * 解説文は 88 星座ぶんしか種類が無いので、1 文まるごと残せば 2 回目からは通信が要らない。
         */
        const val CACHE_MAX_CHARS = 120
        const val CACHE_MAX_BYTES = 16L * 1024 * 1024
        const val CACHE_VERSION = "pcm16-static-v1"

        /**
         * 通信で 1 度落ちたあと、AI 音声を試し直さない時間。
         *
         * **解説ごとに「1 文目だけ AI」を繰り返さない**ためのもの。圏外に入ったら、
         * 次の解説は頭から端末の読み上げで揃う。電波が戻れば自然に AI 音声へ返る。
         */
        const val FAILURE_COOLDOWN_MS = 60_000L

        /** 端末の読み上げを待つ上限。1 文字あたりの見込み ＋ 立ち上がりぶん */
        const val FALLBACK_BASE_MS = 3_000L
        const val FALLBACK_PER_CHAR_MS = 200L
    }
}
