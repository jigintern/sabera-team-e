package jp.jig.glasses.sample.kmp.sound

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import androidx.annotation.RawRes
import jp.jig.glasses.sample.kmp.sky.SkyDarkness
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 流す曲。**空の暗さで選ぶ。**
 *
 * 時計で切り替えないのは、同じ 19 時が夏は明るくて冬は暗いから。
 * 太陽高度なら季節と緯度を勝手に吸収する（[SkyDarkness] は星図と衛星で既に使っている）。
 *
 * どちらも 120 秒。**末尾を先頭にクロスフェードして焼いてある**ので、繰り返しても継ぎ目が出ない。
 */
enum class BgmTrack(@param:RawRes val res: Int, val label: String) {
    /** 日没前後から薄明まで。明るさが移り変わる時間に合う曲 */
    TWILIGHT(R.raw.bgm_twilight, "薄暮"),

    /** 天文薄明から先。広くて何も無い感じの曲 */
    NIGHT(R.raw.bgm_night, "夜"),
}

/**
 * 背景に流す曲。**解説していなくても鳴っている。**
 *
 * 星を見ている間ずっと鳴らすものなので、音量の扱いに気を配る：
 *
 * - **解説が始まったら自動で絞る**（[duck]）。同じ音量のままだと言葉が埋もれる
 * - **絞りは 0.4 秒かけて**。いきなり落とすと曲が途切れたように聞こえる
 * - **曲の入れ替えは 3 秒かけて**。太陽が沈むたびに切り替わるので、気づかれないほうがよい
 * - 音量そのものはユーザーが決める。スマホのスピーカーで屋外なので、適正値は状況で変わる
 */
class Bgm(
    context: Context,
    private val scope: CoroutineScope,
    private val log: (String, Boolean) -> Unit,
) {

    private val app = context.applicationContext

    private var player: MediaPlayer? = null

    /** いま鳴らしている曲。切り替え判定に使う */
    private var playing: BgmTrack? = null

    /** 鳴らしたい曲。止めている間も覚えておいて、再開時にこれへ戻る */
    private var wanted: BgmTrack = BgmTrack.TWILIGHT

    private val _track = MutableStateFlow<BgmTrack?>(null)

    /** 画面に「いまどっちが鳴っているか」を出すために持つ */
    val track: StateFlow<BgmTrack?> = _track

    /** 入れ替えと音量変化がぶつからないように直列化する */
    private val gate = Mutex()
    private var fade: Job? = null

    /** 実際に MediaPlayer へ渡している音量。フェードの起点として持つ */
    private var applied = 0f

    private var enabledValue = true
    private var volumeValue = DEFAULT_VOLUME
    private var duckedValue = false

    var enabled: Boolean
        get() = enabledValue
        set(value) {
            if (enabledValue == value) return
            enabledValue = value
            if (value) switch(wanted) else stop()
        }

    /** 0..1。設定パネルのつまみから来る */
    var volume: Float
        get() = volumeValue
        set(value) {
            volumeValue = value.coerceIn(0f, 1f)
            // つまみを動かしている間はフェードを挟まない。追従しないと合わせられない
            ramp(target(), 0L)
        }

    /** 解説中に絞る。**言葉が埋もれるのを防ぐのが目的**で、無音にはしない */
    fun duck(on: Boolean) {
        if (duckedValue == on) return
        duckedValue = on
        ramp(target(), DUCK_MS)
    }

    /** 空の暗さに合わせて曲を選ぶ。同じ曲なら何もしない */
    fun follow(darkness: SkyDarkness) {
        wanted = if (darkness == SkyDarkness.NIGHT) BgmTrack.NIGHT else BgmTrack.TWILIGHT
        if (enabledValue && wanted != playing) switch(wanted)
    }

    fun release() {
        fade?.cancel()
        runCatching {
            player?.stop()
            player?.release()
        }
        player = null
        playing = null
        _track.value = null
    }

    private fun target(): Float =
        if (!enabledValue) 0f else volumeValue * if (duckedValue) DUCK_FACTOR else 1f

    private fun stop() {
        scope.launch {
            gate.withLock {
                rampNow(0f, FADE_MS)
                runCatching {
                    player?.stop()
                    player?.release()
                }
                player = null
                playing = null
                _track.value = null
            }
        }
    }

    private fun switch(next: BgmTrack) {
        scope.launch {
            gate.withLock {
                if (playing == next && player != null) return@withLock
                rampNow(0f, if (player == null) 0L else FADE_MS)
                runCatching {
                    player?.stop()
                    player?.release()
                }
                player = null
                playing = null

                val created = runCatching { MediaPlayer.create(app, next.res) }.getOrNull()
                if (created == null) {
                    // 音源が無い／デコードできない。星図は動くので黙って諦めるが、理由は残す
                    Log.e(TAG, "BGM を開けない res=${next.res}")
                    log("BGM を開けない（${next.label}）", true)
                    _track.value = null
                    return@withLock
                }
                created.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                created.isLooping = true
                created.setVolume(0f, 0f)
                applied = 0f
                runCatching { created.start() }
                player = created
                playing = next
                _track.value = next
                rampNow(target(), FADE_MS)
            }
        }
    }

    /** 呼び出し元を待たせずに音量を動かす */
    private fun ramp(to: Float, millis: Long) {
        fade?.cancel()
        fade = scope.launch { rampNow(to, millis) }
    }

    private suspend fun rampNow(to: Float, millis: Long) {
        val active = player
        if (active == null) {
            applied = to
            return
        }
        if (millis <= 0L) {
            applied = to
            runCatching { active.setVolume(to, to) }
            return
        }
        val from = applied
        val steps = (millis / STEP_MS).toInt().coerceAtLeast(1)
        for (i in 1..steps) {
            applied = from + (to - from) * i / steps
            runCatching { active.setVolume(applied, applied) }
            delay(STEP_MS)
        }
        applied = to
        runCatching { active.setVolume(to, to) }
    }

    companion object {
        private const val TAG = "Bgm"

        /** 既定の音量。屋外のスマホスピーカーでも聞こえる大きさから始める */
        const val DEFAULT_VOLUME = 0.75f

        /** 解説中に落とす倍率。0 にしないのは、曲が消えると解説だけ浮くため */
        private const val DUCK_FACTOR = 0.28f

        private const val DUCK_MS = 400L
        private const val FADE_MS = 3_000L
        private const val STEP_MS = 40L
    }
}
