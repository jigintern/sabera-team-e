package jp.jig.glasses.sample.kmp.voice

import app.jigglass.glass.CommandManager
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * グラスのマイクで 1 回ぶん録る（#38）。
 *
 * SDK が Opus をほどいて **PCM16 リトルエンディアン・16kHz モノラル**で流してくれる
 * （`CommandManager.micAudio`）。ここは貯めて、**送信の長押しを受けたら止める**だけ。
 *
 * **ホールドは押しっぱなしを取れない。** ジェスチャーは 1 回のイベントとして届くので、
 * 「離したら終わり」にはできない。`HOLD` で始め、もう一度の `HOLD` で送信する。
 *
 * **マイクを流すと毎秒 32,000 バイトが同じ BLE を通る**ので、録っている間は星図を送らない。
 */
class GlassMic(private val commandManager: CommandManager) {

    /**
     * 録れた PCM。空なら一言も拾えなかった。
     *
     * [noiseFloorRms] と [thresholdRms] はログに残すために持つ。**しきい値が合っているかは
     * 屋外の数字を見ないと決まらない**（風の夜と静かな夜で 1 桁変わる）。
     */
    class Recording(
        val pcm: ByteArray,
        /**
         * 声として数えた時間。**送るかどうかの判定には使わない。**
         *
         * しきい値はその場の暗騒音からの推測なので、0ms は「喋っていない」ではなく
         * 「しきい値が高すぎた」でも起こる。**長押しは「これを送る」という意思表示**なので、
         * 端末の推測で質問を捨てない（2026-08-24。「聞き取れているのか分からない」）。
         */
        val speechMs: Long,
        val noiseFloorRms: Double = 0.0,
        val thresholdRms: Double = SPEECH_RMS_MIN,
        val submitted: Boolean = true,
    )

    /**
     * 送信の合図が来るまで録る。
     *
     * **しきい値はその場の暗騒音から決める。** 録音を止める条件にも、送るかどうかの
     * 条件にもしない（[Recording.speechMs] は表示とログだけ）。固定値（900）だけでは
     * 風を声と誤認したため、静かな夜向けの下限を保ったまま暗騒音に合わせて上げる。
     *
     * @param onLevel 0..1 に正規化した音の大きさと、いま声として拾えているか。
     *   **画面に出して「聞こえている」ことを見せる**のが唯一の使い道
     */
    suspend fun record(
        submitRequests: ReceiveChannel<Unit>,
        onLevel: (level: Float, heard: Boolean) -> Unit = { _, _ -> },
    ): Recording = coroutineScope {
        val buffer = ByteArrayOutputStream(MAX_MS * SAMPLE_RATE * 2 / 1000)
        var speechMs = 0L
        var startedAt = 0L
        var submitted = false

        // **録音全体でいちばん静かな塊**を暗騒音とみなす。冒頭の 0.4 秒だけでは、
        // 読み終わって喋り出すのが早い人と、風が 1 回入った夜を取りこぼす
        var noiseFloor = Double.MAX_VALUE
        var threshold = SPEECH_RMS_MIN

        val job = commandManager.micAudio.onEach { chunk ->
            buffer.write(chunk)
            val level = rms(chunk)
            val chunkMs = chunk.size * 1000L / (SAMPLE_RATE * 2)
            val elapsed = if (startedAt == 0L) 0L else System.currentTimeMillis() - startedAt
            // **暗騒音は録っている間ずっと下へ更新する。** 最初の 0.4 秒だけで決めていたときは、
            // そこに風が 1 回入るだけでしきい値が上限（4,000）に張り付き、
            // **そのあとの声を一度も数えられなかった**。人は言葉の合間に必ず黙るので、
            // 最小値を取り続けるほうが本当の暗騒音に寄る
            noiseFloor = minOf(noiseFloor, level)
            threshold = (noiseFloor * NOISE_MARGIN).coerceIn(SPEECH_RMS_MIN, SPEECH_RMS_MAX)
            val heard = elapsed >= NOISE_FLOOR_MS && level >= threshold
            // **暗騒音を測っている間も枠は動かす。** 0 を返していたときは、
            // 喋り出しの 0.4 秒がまるごと「反応していない画面」に見えていた。
            // 声として数えるのはしきい値が決まってから（暗騒音を質問として数えない）
            onLevel(loudness(level, threshold), heard)
            if (heard) {
                speechMs += chunkMs
            }
        }.launchIn(this)

        try {
            commandManager.startMicStreaming()
            startedAt = System.currentTimeMillis()
            while (true) {
                delay(POLL_MS)
                val elapsed = System.currentTimeMillis() - startedAt
                if (submitRequests.tryReceive().isSuccess) {
                    submitted = true
                    break
                }
                // 長押しを忘れても BLE を占有し続けない。無音では送らず、安全上限だけ置く
                if (elapsed > MAX_MS) break
            }
        } catch (e: TimeoutCancellationException) {
            throw e
        } finally {
            job.cancel()
            runCatching { commandManager.stopMicStreaming() }
        }
        Recording(
            pcm = buffer.toByteArray(),
            speechMs = speechMs,
            noiseFloorRms = if (noiseFloor == Double.MAX_VALUE) 0.0 else noiseFloor,
            thresholdRms = threshold,
            submitted = submitted,
        )
    }

    /**
     * 音量表示の目盛り。**しきい値の 0.5〜8 倍を対数で 0..1 に写す。**
     *
     * しきい値で頭打ちにしていたときは、**喋り出した瞬間に全点灯**して以降動かず、
     * 「枠が固まっている＝聞こえていない」ようにしか見えなかった（2026-08-24 実機）。
     * ふつうの声はしきい値の 2〜8 倍に入るので、この幅なら強弱で目に見えて動く。
     */
    private fun loudness(level: Double, threshold: Double): Float {
        if (level <= 0.0) return 0f
        val floor = threshold * 0.5
        if (level <= floor) return 0f
        val span = ln(METER_RANGE)
        return (ln(level / floor) / span).coerceIn(0.0, 1.0).toFloat()
    }

    /** PCM16 の平均音量。音量表示と声の有無の判定だけなので二乗平均で足りる */
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

        /**
         * 喋っているとみなす音量の下限。**静かな夜はこれで足りていた**ので、
         * ここより下げない（下げると暗騒音を質問として数えやすい）。
         */
        const val SPEECH_RMS_MIN = 900.0

        /**
         * しきい値の上限。上げ続けると小声を丸ごと落としてしまうので、ここで頭打ちにする。
         */
        private const val SPEECH_RMS_MAX = 4_000.0

        /** 声として数え始めるまでの時間。「質問をどうぞ」を読んでいる間なので、まだ声は乗っていない */
        private const val NOISE_FLOOR_MS = 400L

        /** 音量表示が受け持つ幅。しきい値の 0.5 倍から 8 倍（＝16 倍）まで */
        private const val METER_RANGE = 16.0

        /** 暗騒音の何倍を声とみなすか。RMS は 2.5 倍で 8dB ほど上 */
        private const val NOISE_MARGIN = 2.5

        /** 長押しを忘れたときの安全上限。**BLE を占有し続けると星図が止まる** */
        private const val MAX_MS = 30_000

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
