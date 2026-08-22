package jp.jig.glasses.sample.kmp.ai

import app.jigglass.glass.CommandManager
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import java.io.ByteArrayOutputStream
import kotlin.math.sqrt

/**
 * グラスのマイクで 1 回ぶん録る（#38）。
 *
 * SDK が Opus をほどいて **PCM16 リトルエンディアン・16kHz モノラル**で流してくれる
 * （`CommandManager.micAudio`）。ここは貯めて、**喋り終わりを見つけて止める**だけ。
 *
 * **ホールドは押しっぱなしを取れない。** ジェスチャーは 1 回のイベントとして届くので、
 * 「離したら終わり」にはできない。**黙ったら終わり**にする。
 *
 * **マイクを流すと毎秒 32,000 バイトが同じ BLE を通る**ので、録っている間は星図を送らない。
 */
class GlassMic(private val commandManager: CommandManager) {

    /** 録れた PCM。空なら一言も拾えなかった */
    class Recording(val pcm: ByteArray, val speechMs: Long)

    /**
     * 喋り終わるまで録る。
     *
     * @param onLevel 0..1 に正規化した音の大きさ。画面に出して「聞こえている」ことを見せる
     */
    suspend fun record(onLevel: (Float) -> Unit = {}): Recording = coroutineScope {
        val buffer = ByteArrayOutputStream(MAX_MS * SAMPLE_RATE * 2 / 1000)
        var speechMs = 0L
        var silenceMs = 0L
        var startedAt = 0L

        val job = commandManager.micAudio.onEach { chunk ->
            buffer.write(chunk)
            val level = rms(chunk)
            onLevel((level / SPEECH_RMS).coerceIn(0.0, 1.0).toFloat())
            val chunkMs = chunk.size * 1000L / (SAMPLE_RATE * 2)
            if (level >= SPEECH_RMS) {
                speechMs += chunkMs
                silenceMs = 0
            } else if (speechMs > 0) {
                silenceMs += chunkMs
            }
        }.launchIn(this)

        try {
            commandManager.startMicStreaming()
            startedAt = System.currentTimeMillis()
            while (true) {
                delay(POLL_MS)
                val elapsed = System.currentTimeMillis() - startedAt
                // 喋り出す前に諦める時間と、喋りすぎを切る時間は別にする
                if (speechMs == 0L && elapsed > SILENT_START_MS) break
                if (speechMs >= MIN_SPEECH_MS && silenceMs >= END_SILENCE_MS) break
                if (elapsed > MAX_MS) break
            }
        } catch (e: TimeoutCancellationException) {
            throw e
        } finally {
            job.cancel()
            runCatching { commandManager.stopMicStreaming() }
        }
        Recording(buffer.toByteArray(), speechMs)
    }

    /** PCM16 の平均音量。無音の判定にしか使わないので二乗平均で足りる */
    private fun rms(pcm: ByteArray): Double {
        if (pcm.size < 2) return 0.0
        var sum = 0.0
        var i = 0
        while (i + 1 < pcm.size) {
            val sample = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort().toInt()
            sum += sample.toDouble() * sample
            i += 2
        }
        return sqrt(sum / (pcm.size / 2))
    }

    companion object {
        const val SAMPLE_RATE = 16_000

        /** 喋っているとみなす音量。静かな屋外の暗騒音より上、小声より下 */
        private const val SPEECH_RMS = 900.0

        /** これだけ黙ったら喋り終わり */
        private const val END_SILENCE_MS = 1_200L

        /** これだけ喋っていないと「終わり」と判断しない。息継ぎで切らないため */
        private const val MIN_SPEECH_MS = 400L

        /** 一言も喋らなかったときに諦める時間 */
        private const val SILENT_START_MS = 4_000L

        /** 長く喋られても切る。**BLE を占有し続けると星図が止まる** */
        private const val MAX_MS = 12_000

        private const val POLL_MS = 100L

        /**
         * PCM16 モノラルを WAV にする。
         *
         * OpenAI の文字起こしは拡張子と中身から形式を見るので、**ヘッダを付けるだけ**でよい。
         */
        fun toWav(pcm: ByteArray, sampleRate: Int = SAMPLE_RATE): ByteArray {
            val header = ByteArray(44)
            fun putAscii(at: Int, text: String) {
                for ((i, c) in text.withIndex()) header[at + i] = c.code.toByte()
            }
            fun putInt(at: Int, value: Int) {
                header[at] = (value and 0xFF).toByte()
                header[at + 1] = ((value shr 8) and 0xFF).toByte()
                header[at + 2] = ((value shr 16) and 0xFF).toByte()
                header[at + 3] = ((value shr 24) and 0xFF).toByte()
            }
            fun putShort(at: Int, value: Int) {
                header[at] = (value and 0xFF).toByte()
                header[at + 1] = ((value shr 8) and 0xFF).toByte()
            }
            putAscii(0, "RIFF")
            putInt(4, 36 + pcm.size)
            putAscii(8, "WAVE")
            putAscii(12, "fmt ")
            putInt(16, 16)
            putShort(20, 1)
            putShort(22, 1)
            putInt(24, sampleRate)
            putInt(28, sampleRate * 2)
            putShort(32, 2)
            putShort(34, 16)
            putAscii(36, "data")
            putInt(40, pcm.size)
            return header + pcm
        }
    }
}
