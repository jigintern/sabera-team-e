package jp.jig.glasses.sample.kmp.sound

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.util.Log
import jp.jig.glasses.sample.kmp.support.LoudnessBoost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 背景に流す曲。**解説していなくても鳴っている。**
 *
 * 星を見ている間ずっと鳴らすものなので、音量と継ぎ目の扱いに気を配る：
 *
 * - **解説が始まったら自動で絞る**（[duck]）。同じ音量のままだと言葉が埋もれる
 * - **絞りは 0.4 秒かけて**。いきなり落とすと曲が途切れたように聞こえる
 * - **曲の入れ替えはクロスフェード**。片方を落としながらもう片方を上げるので無音を挟まない。
 *   以前は 0 まで落としてから作り直していたので、**薄暮から夜へ変わるたびに約 3 秒黙っていた**
 * - 音量そのものはユーザーが決める。スマホのスピーカーで屋外なので、適正値は状況で変わる
 *
 * 曲は [scene] と [pinned] で決まる。**[scene] にいる限り 120 秒ごとに次の曲へ渡り歩く**（#69）。
 * 曲を指名されているとき（[pinned]）は、その 1 曲を繰り返す。
 *
 * **持ち主は `GlassesApp`。** 画面ごとに作ると、ホームから星図へ進むたびに音が切れる。
 */
class Bgm(context: Context, private val scope: CoroutineScope) {

    /**
     * 失敗を観測画面のログへ出す口。
     *
     * このクラスは画面より長生きする（`GlassesApp` が持つ）ので、**画面が差し込んで外す**。
     * 差さっていない間の失敗は `Log` にだけ残る。
     */
    var onLog: ((String, Boolean) -> Unit)? = null

    /** 鳴らしている 1 曲。クロスフェード中は 2 つ並ぶ */
    private class Deck(val player: MediaPlayer, val track: BgmTrack) {
        /** クロスフェードの位置 0..1。実際の音量は「[level] × これ」 */
        var factor = 0f
    }

    private val app = context.applicationContext

    /**
     * **2 つの `MediaPlayer` を同じセッションに入れる。**
     *
     * [LoudnessBoost] はセッションに付けるので、別々だとクロスフェードの最中に
     * 片方だけ持ち上がって音量が動く。
     */
    private val sessionId = runCatching {
        (app.getSystemService(Context.AUDIO_SERVICE) as AudioManager).generateAudioSessionId()
    }.getOrDefault(AudioManager.ERROR)

    private val boost = LoudnessBoost().apply { attach(sessionId) }

    /** 手前が新しい。クロスフェード中だけ 2 つになる */
    private val decks = mutableListOf<Deck>()

    private val _track = MutableStateFlow<BgmTrack?>(null)

    /** 設定パネルに「いま何が鳴っているか」を出すために持つ */
    val track: StateFlow<BgmTrack?> = _track

    /** 曲の入れ替えを直列化する。**音量の追従はここを通さない**（つまみが固まる） */
    private val gate = Mutex()

    /** つまみと絞りの追従 */
    private var fade: Job? = null

    /** 次の曲への渡し。曲の残り時間から予約する */
    private var advance: Job? = null

    /** 音量つまみ × 解説中の絞り。フェードで動く。実際の音量は「これ × [Deck.factor]」 */
    private var level = 0f

    private var enabledValue = true
    private var volumeValue = DEFAULT_VOLUME
    private var duckedValue = false
    private var sceneValue = BgmScene.TWILIGHT
    private var pinnedValue: BgmTrack? = null

    var enabled: Boolean
        get() = enabledValue
        set(value) {
            if (enabledValue == value) return
            enabledValue = value
            if (value) {
                // 止める途中のフェードを捨てて上げ直す。**捨てるだけでは戻らない**：
                // まだ閉じていない曲が残っている場合、鳴っているのに音量が 0 のままになる
                ramp(target(), DUCK_MS)
                retune()
            } else {
                stop()
            }
        }

    /** 0..1。設定パネルのつまみから来る */
    var volume: Float
        get() = volumeValue
        set(value) {
            volumeValue = value.coerceIn(0f, 1f)
            // つまみを動かしている間はフェードを挟まない。追従しないと合わせられない
            ramp(target(), 0L)
        }

    /**
     * いまの場面。空の暗さとガイドの有無から**呼ぶ側が決める**。
     *
     * いま鳴っている曲がこの場面にも入っているなら**そのまま続ける**（同じ曲を鳴らし直さない）。
     */
    var scene: BgmScene
        get() = sceneValue
        set(value) {
            sceneValue = value
            // **同じ場面でも通す。** 起動直後は「まだ何も鳴っていない」状態で
            // 場面が既定値と同じなので、ここで返すと曲が一度も始まらない
            retune()
        }

    /**
     * 設定パネルで指名された曲。null なら場面まかせ。
     *
     * 指名されている間は**その 1 曲を繰り返す**（場面が変わっても入れ替えない）。
     */
    var pinned: BgmTrack?
        get() = pinnedValue
        set(value) {
            pinnedValue = value
            retune()
        }

    /**
     * 覚えてある設定を**鳴らし始める前に**入れる（`SoundPrefs`）。
     *
     * セッター経由で入れると、[scene] が決まる前に既定の場面で 1 曲鳴り出してしまい、
     * **起動のたびに薄暮の曲が一瞬鳴ってから夜の曲へ渡る**ことになる。
     * ここで入れておき、**[scene] が決まった時点で初めて鳴らし始める**。
     */
    fun restore(enabled: Boolean, volume: Float, pinned: BgmTrack?) {
        enabledValue = enabled
        volumeValue = volume.coerceIn(0f, 1f)
        pinnedValue = pinned
    }

    /** 解説中に絞る。**言葉が埋もれるのを防ぐのが目的**で、無音にはしない */
    fun duck(on: Boolean) {
        if (duckedValue == on) return
        duckedValue = on
        ramp(target(), DUCK_MS)
    }

    fun release() {
        fade?.cancel()
        advance?.cancel()
        boost.release()
        // 入れ替えの途中で呼ばれても壊れないように、写しを回す
        for (deck in decks.toList()) close(deck)
        decks.clear()
        _track.value = null
    }

    private fun target(): Float =
        if (!enabledValue) 0f else volumeValue * if (duckedValue) DUCK_FACTOR else 1f

    /** 場面と指名から鳴らすべき曲を決め、変わっていれば渡す */
    private fun retune() {
        if (!enabledValue) return
        val playing = decks.firstOrNull()?.track
        val wanted = pinnedValue
            // 場面が変わっても、その曲が新しい場面にも入っているなら続ける
            ?: playing?.takeIf { sceneValue in it.scenes }
            ?: BgmPlaylist.next(sceneValue, playing)
            // 曲が 1 つも無い場面。**黙るよりいまの曲を続ける**
            ?: return
        if (wanted == playing) {
            // 曲は同じでも、指名の有無で繰り返し方が変わる
            decks.firstOrNull()?.let { deck ->
                runCatching { deck.player.isLooping = pinnedValue != null }
                scheduleAdvance(deck)
            }
            return
        }
        crossfadeTo(wanted)
    }

    private fun stop() {
        advance?.cancel()
        fade?.cancel()
        fade = scope.launch {
            // **落としきってから閉じる。** 先に閉じると音がぶつ切りになる
            rampNow(0f, FADE_MS)
            gate.withLock {
                // 落としている 3 秒の間に入れ直されていたら、その曲を閉じない
                if (enabledValue) return@withLock
                for (deck in decks.toList()) close(deck)
                decks.clear()
                _track.value = null
            }
        }
    }

    /**
     * [next] へ渡す。**落としながら上げる**ので無音を挟まない。
     *
     * 曲送りと場面の入れ替えの両方でここを通る。
     */
    private fun crossfadeTo(next: BgmTrack) {
        scope.launch {
            gate.withLock {
                advance?.cancel()
                // 待っている間に別の入れ替えが同じ曲を鳴らし始めていたら、二重に開かない
                if (decks.firstOrNull()?.track == next) return@withLock
                val deck = open(next) ?: return@withLock
                // 何も鳴っていないところから始めるときは、つまみの値へ合わせておく。
                // ここを飛ばすと level が 0 のままで、上げても音が出ない
                if (decks.isEmpty()) level = target()
                // 指名されているときだけ 1 曲を繰り返す。
                // 場面まかせのときは終わりを受けて次の曲へ渡すので、繰り返させない
                runCatching { deck.player.isLooping = pinnedValue != null }
                val outgoing = decks.toList()
                decks.add(0, deck)
                _track.value = next
                runCatching { deck.player.start() }

                val millis = if (outgoing.isEmpty()) 0L else CROSSFADE_MS
                val steps = (millis / STEP_MS).toInt().coerceAtLeast(1)
                val from = outgoing.map { it.factor }
                for (i in 1..steps) {
                    val p = i.toFloat() / steps
                    deck.factor = p
                    outgoing.forEachIndexed { k, old -> old.factor = from[k] * (1f - p) }
                    applyVolume()
                    if (millis > 0L) delay(STEP_MS)
                }
                deck.factor = 1f
                for (old in outgoing) {
                    decks.remove(old)
                    close(old)
                }
                applyVolume()
                scheduleAdvance(deck)
            }
        }
    }

    /**
     * 曲の終わりから [CROSSFADE_MS] だけ手前に、次の曲への渡しを予約する。
     *
     * `setOnCompletionListener` では**鳴り終わってからしか気づけない**ので、
     * 落としながら上げることができない。
     */
    private fun scheduleAdvance(deck: Deck) {
        advance?.cancel()
        if (pinnedValue != null) return
        val duration = runCatching { deck.player.duration }.getOrDefault(0)
        if (duration <= 0) return
        advance = scope.launch {
            val position = runCatching { deck.player.currentPosition }.getOrDefault(0)
            delay((duration - position - CROSSFADE_MS).coerceAtLeast(0L))
            // 予約した当人がまだ鳴っているときだけ渡す（間に場面が変わっていたら任せる）
            if (decks.firstOrNull() !== deck) return@launch
            val next = BgmPlaylist.next(sceneValue, deck.track) ?: return@launch
            crossfadeTo(next)
        }
    }

    private suspend fun open(track: BgmTrack): Deck? {
        val player = withContext(Dispatchers.IO) {
            runCatching {
                MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build(),
                    )
                    // **データを渡す前にセッションを決める。** あとからは変えられない
                    if (sessionId > 0) audioSessionId = sessionId
                    app.resources.openRawResourceFd(track.res).use {
                        setDataSource(it.fileDescriptor, it.startOffset, it.length)
                    }
                    prepare()
                }
            }.getOrNull()
        }
        if (player == null) {
            // 音源が無い／デコードできない。星図は動くので黙って諦めるが、理由は残す
            Log.e(TAG, "BGM を開けない ${track.title}")
            onLog?.invoke("BGM を開けない（${track.title}）", true)
            return null
        }
        val deck = Deck(player, track)
        deck.factor = 0f
        runCatching { player.setVolume(0f, 0f) }
        return deck
    }

    private fun close(deck: Deck) {
        runCatching {
            deck.player.stop()
            deck.player.release()
        }
    }

    /** 呼び出し元を待たせずに音量を動かす。**曲の入れ替えとは別の口**（つまみを固まらせない） */
    private fun ramp(to: Float, millis: Long) {
        fade?.cancel()
        fade = scope.launch { rampNow(to, millis) }
    }

    private suspend fun rampNow(to: Float, millis: Long) {
        val from = level
        val steps = (millis / STEP_MS).toInt().coerceAtLeast(1)
        for (i in 1..steps) {
            level = from + (to - from) * i / steps
            applyVolume()
            if (millis > 0L) delay(STEP_MS)
        }
        level = to
        applyVolume()
    }

    private fun applyVolume() {
        // 入れ替えの最中に呼ばれても壊れないよう、写しを回す
        for (deck in decks.toList()) {
            val v = level * deck.factor
            runCatching { deck.player.setVolume(v, v) }
        }
    }

    companion object {
        private const val TAG = "Bgm"

        /** 既定の音量。屋外のスマホスピーカーでも聞こえる大きさから始める */
        const val DEFAULT_VOLUME = 0.75f

        /** 解説中に落とす倍率。0 にしないのは、曲が消えると解説だけ浮くため */
        private const val DUCK_FACTOR = 0.28f

        private const val DUCK_MS = 400L

        /** 止めるときのフェード */
        private const val FADE_MS = 3_000L

        /**
         * 曲を渡すのにかける時間。
         *
         * 太陽が沈むたびに切り替わるので、**気づかれないほうがよい**。
         * 曲送りでも同じ時間をかける。
         */
        private const val CROSSFADE_MS = 3_000L

        private const val STEP_MS = 40L
    }
}
