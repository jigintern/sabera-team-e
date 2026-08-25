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
            fun fromPlace(place: SkyPlace, epochMillis: Long): Simulation = Simulation(
                site = place.site,
                epochMillis = epochMillis,
                zoneId = place.zoneId,
                placeLabel = place.nameJa,
            )
        }
    }
}

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

/**
 * 月・惑星を出してよいか。**Ephemeris の近似は数世紀ぶんしか持たない。**
 *
 * 恒星は長期歳差（[longTermPrecess]）で 1 万年まで持つが、月と惑星の摂動の級数は
 * そこまで作られていない。**衛星と同じで、もっともらしい嘘を描かずに理由を出す。**
 */
fun ObservationSnapshot.allowsSolarSystemBodies(): Boolean =
    abs(daysFromJ2000(epochMillis)) <= LONG_TERM_PRECESSION_DAYS

/** 恒星の形が信じられる範囲を出たか。**固有運動を持たないので星座の形が崩れる** */
fun ObservationSnapshot.beyondStarShapes(): Boolean =
    abs(daysFromJ2000(epochMillis)) > LONG_TERM_PRECESSION_DAYS

/**
 * つまみの位置。**基準時刻からいまの空が何時間ずれているか**（#45）。
 *
 * **別に持った値を離すたびに 0 へ戻してはいけない。** そうすると、動かしても
 * つまみが中央へ跳ね返って**「元に戻った」ように見える**（実際は空だけ変わっている）。
 * いまの時刻から引き直せば、置いた場所に留まる。
 */
fun scrubOffsetHours(anchorMillis: Long, epochMillis: Long, limitHours: Float): Float =
    ((epochMillis - anchorMillis) / 3_600_000f).coerceIn(-limitHours, limitHours)

/** つまみを離した位置に対応する時刻。**基準は動かさない**（動かすと行き来でずれる） */
fun scrubTargetMillis(anchorMillis: Long, offsetHours: Float): Long =
    anchorMillis + (offsetHours * 3_600_000f).toLong()

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

const val MAX_RELIABLE_TLE_AGE_DAYS = 7.0
