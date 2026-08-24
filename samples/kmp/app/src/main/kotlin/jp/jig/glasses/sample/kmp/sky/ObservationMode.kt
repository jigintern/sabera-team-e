package jp.jig.glasses.sample.kmp.sky

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/** 現在の空と、場所・時刻を固定した空を混ぜないための観測状態。 */
sealed interface ObservationMode {
    data object Live : ObservationMode

    data class Simulation(
        val site: Site,
        val epochMillis: Long,
        val zoneId: ZoneId,
        val placeLabel: String,
    ) : ObservationMode {
        companion object {
            fun fromCity(city: City, epochMillis: Long): Simulation = Simulation(
                site = city.site,
                epochMillis = epochMillis,
                zoneId = city.zoneId,
                placeLabel = city.nameJa,
            )
        }
    }
}

/** 観測条件とは別に持つ、時間を進める機能の状態。 */
data class TimePlaybackState(
    val playing: Boolean = false,
    val startedElapsedMillis: Long? = null,
    val lastStepElapsedMillis: Long? = null,
)

/** 1回の描画・解説が最後まで共有する場所と時刻。 */
data class ObservationSnapshot(
    val site: Site,
    val epochMillis: Long,
    val zoneId: ZoneId,
    val placeLabel: String,
    val simulation: Boolean,
) {
    fun shortLabel(): String = buildString {
        if (simulation) append("シミュレーション ")
        append(placeLabel).append(' ')
        append(SHORT_FORMAT.withZone(zoneId).format(Instant.ofEpochMilli(epochMillis)))
    }

    fun fullTimeLabel(): String = FULL_FORMAT.withZone(zoneId).format(Instant.ofEpochMilli(epochMillis))

    companion object {
        private val SHORT_FORMAT = DateTimeFormatter.ofPattern("M/d H:mm")
        private val FULL_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z")
    }
}

/** シミュレーション日時がTLE元期から離れすぎたときは、衛星だけをもっともらしく出さない。 */
fun ObservationSnapshot.allowsSatellites(
    elementAgeDays: Double?,
    maxAgeDays: Double = MAX_RELIABLE_TLE_AGE_DAYS,
): Boolean = !simulation || elementAgeDays == null || abs(elementAgeDays) <= maxAgeDays

fun ObservationMode.snapshot(
    liveSite: Site,
    nowMillis: Long,
    liveZoneId: ZoneId = ZoneId.systemDefault(),
    livePlaceLabel: String = "現在地",
): ObservationSnapshot = when (this) {
    ObservationMode.Live -> ObservationSnapshot(liveSite, nowMillis, liveZoneId, livePlaceLabel, false)
    is ObservationMode.Simulation -> ObservationSnapshot(site, epochMillis, zoneId, placeLabel, true)
}

/** 現在の空から再生するときは、その瞬間の条件を固定してから時間だけを進める。 */
fun ObservationMode.freezeForPlayback(
    liveSite: Site,
    nowMillis: Long,
    liveZoneId: ZoneId = ZoneId.systemDefault(),
    livePlaceLabel: String = "現在地",
): ObservationMode.Simulation = when (this) {
    ObservationMode.Live -> ObservationMode.Simulation(liveSite, nowMillis, liveZoneId, livePlaceLabel)
    is ObservationMode.Simulation -> this
}

fun ObservationMode.Simulation.advanceBy(millis: Long): ObservationMode.Simulation =
    copy(epochMillis = epochMillis + millis)

data class PlaybackTick(val playback: TimePlaybackState, val advanceMillis: Long)

/** 再生を始める。すでに再生中なら起点を延長しない。 */
fun TimePlaybackState.startPlayback(elapsedMillis: Long): TimePlaybackState =
    if (playing) {
        this
    } else {
        copy(
            playing = true,
            startedElapsedMillis = elapsedMillis,
            lastStepElapsedMillis = elapsedMillis,
        )
    }

fun TimePlaybackState.stopPlayback(): TimePlaybackState = copy(
    playing = false,
    startedElapsedMillis = null,
    lastStepElapsedMillis = null,
)

/**
 * 実時間2秒ごとに10分進め、開始から30秒で止める。
 *
 * 首が動いている時間は捨てる。止まった瞬間に遅れたぶんをまとめて進めると、何枚も続けて送り
 * 点滅するため、移動中も次の刻みの起点だけは現在へ進める。
 */
fun TimePlaybackState.tick(elapsedMillis: Long, settled: Boolean): PlaybackTick {
    if (!playing) return PlaybackTick(this, 0L)
    val started = startedElapsedMillis ?: elapsedMillis
    if (elapsedMillis - started >= PLAYBACK_LIMIT_MS) {
        return PlaybackTick(stopPlayback(), 0L)
    }
    val last = lastStepElapsedMillis ?: elapsedMillis
    if (!settled) return PlaybackTick(copy(lastStepElapsedMillis = elapsedMillis), 0L)
    val steps = ((elapsedMillis - last) / PLAYBACK_STEP_MS).toInt()
    if (steps <= 0) return PlaybackTick(this, 0L)
    return PlaybackTick(
        copy(
            lastStepElapsedMillis = last + steps * PLAYBACK_STEP_MS,
        ),
        advanceMillis = steps * SIMULATED_STEP_MS,
    )
}

const val PLAYBACK_STEP_MS = 2_000L
const val SIMULATED_STEP_MS = 10 * 60_000L
const val PLAYBACK_LIMIT_MS = 30_000L
const val MAX_RELIABLE_TLE_AGE_DAYS = 7.0
