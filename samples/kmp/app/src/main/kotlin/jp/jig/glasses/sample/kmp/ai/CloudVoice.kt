package jp.jig.glasses.sample.kmp.ai

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * AI 音声で喋る。作れないときは端末の読み上げ（[Speaker]）に落とす。
 *
 * 落とし先を必ず持つのは、**夜の屋外は電波が悪い**うえに、
 * **タップして無反応が一番よくない**（app-flow.md）から。声の質は雰囲気の問題だが、
 * 黙るのは機能の欠落になる。
 *
 * 発話は 1 本ずつ順に鳴らす。[say] は積んであるものを捨てて言い直し、[add] は後ろに続ける
 * （「〇〇座ですね」→ 解説、という [Narrator] の組み立てに合わせている）。
 */
class CloudVoice(
    context: Context,
    private val speech: OpenAiSpeech,
    private val fallback: Speaker,
    private val scope: CoroutineScope,
    private val log: (String, Boolean) -> Unit,
) : Voice, VoiceStatus {

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

    private var current: Job? = null

    private val _speaking = MutableStateFlow(false)

    /**
     * 鳴っているか。**端末の読み上げに落ちた発話も含める**
     * （画面側は 1 本の Flow だけを見て「終わったら待機に戻す」を判断している）。
     */
    override val speaking: StateFlow<Boolean> =
        combine(_speaking, fallback.speaking) { cloud, device -> cloud || device }
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
        if (!usable()) {
            fallback.add(text)
            return
        }
        enqueue(text, flush = false)
    }

    override fun stop() {
        generation++
        current?.cancel()
        cancelPreparations()
        fallback.stop()
        _speaking.value = false
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
                .onFailure { Log.w(TAG, "先読みに失敗 ${it.message}") }
        }
    }

    private fun usable(): Boolean = _enabled.value && !_broken.value && speech.configured

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
        // 発話が始まる前に true にしておく。ここが遅れると画面側が「もう終わった」と誤解する
        _speaking.value = true
        queued.incrementAndGet()
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
        try {
            play(utterance.prepared.await())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val permanent = (e as? OpenAiSpeech.SpeechException)?.permanent == true
            if (permanent) _broken.value = true
            Log.e(TAG, "AI 音声に失敗", e)
            log(
                if (permanent) {
                    "AI 音声が使えない（${e.message}）。以後は端末の読み上げにする"
                } else {
                    "AI 音声が届かない（${e.message}）。この一言は端末の読み上げで喋る"
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
            .apply { setVolume(volumeValue) }
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

        const val CACHE_MAX_CHARS = 48
        const val CACHE_MAX_BYTES = 16L * 1024 * 1024
        const val CACHE_VERSION = "pcm16-static-v1"

        /** 端末の読み上げを待つ上限。1 文字あたりの見込み ＋ 立ち上がりぶん */
        const val FALLBACK_BASE_MS = 3_000L
        const val FALLBACK_PER_CHAR_MS = 200L
    }
}
