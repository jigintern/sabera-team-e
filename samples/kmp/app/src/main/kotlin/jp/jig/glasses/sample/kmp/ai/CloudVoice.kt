package jp.jig.glasses.sample.kmp.ai

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

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

    private class Utterance(val text: String, val flush: Boolean, val generation: Int)

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
        enqueue(Utterance(text, flush = true, generation = generation))
    }

    override fun add(text: String) {
        if (text.isBlank()) return
        if (!usable()) {
            fallback.add(text)
            return
        }
        enqueue(Utterance(text, flush = false, generation = generation))
    }

    override fun stop() {
        generation++
        queued.set(0)
        current?.cancel()
        fallback.stop()
        _speaking.value = false
    }

    fun shutdown() {
        stop()
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
            runCatching { synthesize(text) { _, _ -> } }
                .onFailure { Log.w(TAG, "先読みに失敗 ${it.message}") }
        }
    }

    private fun usable(): Boolean = _enabled.value && !_broken.value && speech.configured

    private fun enqueue(utterance: Utterance) {
        // 発話が始まる前に true にしておく。ここが遅れると画面側が「もう終わった」と誤解する
        _speaking.value = true
        queued.incrementAndGet()
        if (queue.trySend(utterance).isFailure) {
            queued.decrementAndGet()
            fallback.say(utterance.text)
        }
    }

    private suspend fun speakOne(utterance: Utterance) {
        try {
            play(utterance.text)
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
    private suspend fun play(text: String) {
        val track = newTrack().also { this.track = it }
        var started = false
        var bytesWritten = 0L
        try {
            synthesize(text) { buffer, length ->
                var offset = 0
                while (offset < length) {
                    val written = track.write(buffer, offset, length - offset)
                    if (written <= 0) break
                    offset += written
                    bytesWritten += written
                    // 先頭を少し溜めてから鳴らす。届いたぶんをすぐ鳴らすと、
                    // 電波が細いときに一言ごとに途切れて、かえって人工的に聞こえる
                    if (!started && bytesWritten >= PREBUFFER_BYTES) {
                        track.play()
                        started = true
                    }
                }
            }
            if (!started) track.play()
            // 溜めたぶんが鳴り切るまで待つ。ここで返すと次の発話が上から重なる
            val frames = bytesWritten / BYTES_PER_FRAME
            while (currentCoroutineContext().isActive && track.playbackHeadPosition < frames) {
                delay(DRAIN_POLL_MS)
            }
        } finally {
            this.track = null
            runCatching {
                track.pause()
                track.flush()
                track.release()
            }
        }
    }

    private fun newTrack(): AudioTrack {
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(OpenAiSpeech.SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val minimum = AudioTrack.getMinBufferSize(
            OpenAiSpeech.SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(format)
            .setTransferMode(AudioTrack.MODE_STREAM)
            // 溜める量より小さいと、鳴らし始める前に write が詰まって進まなくなる
            .setBufferSizeInBytes(maxOf(minimum, PREBUFFER_BYTES * 2))
            .build()
            .apply { setVolume(volumeValue) }
    }

    /** キャッシュにあればそれを、無ければ作らせて（残せるものは残して）[onPcm] へ流す */
    private suspend fun synthesize(text: String, onPcm: (ByteArray, Int) -> Unit) {
        val file = cacheFile(text)
        if (file != null && file.isFile) {
            withContext(Dispatchers.IO) {
                val buffer = ByteArray(READ_BYTES)
                file.inputStream().use { input ->
                    while (currentCoroutineContext().isActive) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read > 0) onPcm(buffer, read)
                    }
                }
            }
            return
        }

        val kept = if (file != null) ByteArrayOutputStream() else null
        speech.stream(text) { buffer, length ->
            kept?.write(buffer, 0, length)
            onPcm(buffer, length)
        }
        if (file != null && kept != null && kept.size() > 0) {
            withContext(Dispatchers.IO) {
                runCatching {
                    cacheDir.mkdirs()
                    file.writeBytes(kept.toByteArray())
                    prune()
                }
            }
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
            .digest("${speech.signature}|$text".toByteArray(Charsets.UTF_8))
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

        const val BYTES_PER_FRAME = 2

        /** 鳴らし始める前に溜める量。24kHz / 16bit なので 0.6 秒ぶん */
        const val PREBUFFER_BYTES = 28_800

        const val READ_BYTES = 4096
        const val DRAIN_POLL_MS = 50L

        const val CACHE_MAX_CHARS = 48
        const val CACHE_MAX_BYTES = 16L * 1024 * 1024

        /** 端末の読み上げを待つ上限。1 文字あたりの見込み ＋ 立ち上がりぶん */
        const val FALLBACK_BASE_MS = 3_000L
        const val FALLBACK_PER_CHAR_MS = 200L
    }
}
