package jp.jig.glasses.sample.kmp.sky

import kotlin.math.atan2

/** 「ふつう」の星図と同じ出どころから選ぶ、案内可能な名前付き対象。 */
enum class GuidanceTargetKind {
    CONSTELLATION,
    STAR,
    ASTERISM,
    BODY,
    SATELLITE,
}

data class GuidanceTarget(
    val id: String,
    val nameJa: String,
    val kind: GuidanceTargetKind,
    val aim: Look,
    val aliases: Set<String> = emptySet(),
)

sealed interface GuidanceRequest {
    data object NotGuidance : GuidanceRequest
    data object Stop : GuidanceRequest
    data object UnknownTarget : GuidanceRequest
    data object MultipleTargets : GuidanceRequest
    data class Start(val target: GuidanceTarget) : GuidanceRequest
}

/** AIへ操作可否を渡さず、端末が持つ名前と許可した言い方だけから案内要求を取り出す。 */
object GuidanceRequestParser {
    private val requestWords = listOf(
        "どこ", "何処", "どっち", "方向", "向き", "案内", "見せて", "みせて", "探して", "さがして",
    ).map(::normalize)
    private val stopWords = listOf("案内をやめて", "案内を止めて", "案内終了", "もういい")
        .map(::normalize)

    fun parse(text: String, targets: Collection<GuidanceTarget>): GuidanceRequest {
        val normalized = normalize(text)
        if (stopWords.any { it in normalized }) return GuidanceRequest.Stop
        if (requestWords.none { it in normalized }) return GuidanceRequest.NotGuidance

        data class Match(val target: GuidanceTarget, val start: Int, val end: Int)
        val matches = targets.flatMap { target ->
            (target.aliases + target.nameJa + defaultAliases(target)).flatMap { alias ->
                val key = normalize(alias)
                if (key.isEmpty()) return@flatMap emptyList()
                buildList {
                    var start = normalized.indexOf(key)
                    while (start >= 0) {
                        add(Match(target, start, start + key.length))
                        start = normalized.indexOf(key, start + 1)
                    }
                }
            }
        }
        // 「りゅうこつ座」の中にある短い「りゅう」で、りゅう座まで候補にしない。
        // 離れた場所にある「オリオンとシリウス」はどちらも残す。
        val found = matches.filterNot { match ->
            matches.any { other ->
                other.target.id != match.target.id &&
                    other.start <= match.start && other.end >= match.end &&
                    other.end - other.start > match.end - match.start
            }
        }.map { it.target }.distinctBy { it.id }
        return when (found.size) {
            0 -> GuidanceRequest.UnknownTarget
            1 -> GuidanceRequest.Start(found.single())
            else -> GuidanceRequest.MultipleTargets
        }
    }

    private fun defaultAliases(target: GuidanceTarget): Set<String> = when (target.kind) {
        GuidanceTargetKind.CONSTELLATION -> setOf(target.nameJa.removeSuffix("座"))
        GuidanceTargetKind.STAR -> setOf(target.nameJa.removeSuffix("星"), target.nameJa + "星")
        else -> emptySet()
    }

    internal fun normalize(text: String): String = buildString(text.length) {
        for (raw in text.lowercase()) {
            val char = when (raw) {
                in 'ァ'..'ヶ' -> raw - 0x60
                in 'Ａ'..'Ｚ' -> 'a' + (raw - 'Ａ')
                in 'ａ'..'ｚ' -> 'a' + (raw - 'ａ')
                in '０'..'９' -> '0' + (raw - '０')
                else -> raw
            }
            if (char.isLetterOrDigit() || char in '\u3041'..'\u3096') append(char)
        }
    }
}

enum class GuidanceEvent {
    NONE,
    ARRIVED,
    COMPLETED,
    TIMED_OUT,
}

data class GuidanceFrame(
    val targetName: String,
    val distanceDeg: Double,
    /** 画面の上を0°、右を90°とする矢印の向き。 */
    val arrowClockwiseDeg: Double,
    val near: Boolean,
    val arrived: Boolean,
)

data class GuidanceSession(
    val target: GuidanceTarget,
    val startedAtMillis: Long,
    val withinArrivalSinceMillis: Long? = null,
    val arrivedAtMillis: Long? = null,
)

data class GuidanceUpdate(
    val session: GuidanceSession?,
    val frame: GuidanceFrame,
    val event: GuidanceEvent,
)

/**
 * 5°へ0.5秒留まったら到着し、8°を超えたら案内へ戻す。
 *
 * 方位合わせには±5〜15°の残差があるため、表示では「到着」ではなく「このあたり」と伝える。
 */
fun GuidanceSession.update(
    currentLook: Look,
    targetAim: Look,
    rollDeg: Double,
    nowMillis: Long,
): GuidanceUpdate {
    val distance = angleBetweenDeg(enu(currentLook.azDeg, currentLook.altDeg), enu(targetAim.azDeg, targetAim.altDeg))
    val basis = Basis(currentLook.azDeg, currentLook.altDeg, rollDeg)
    val targetVector = enu(targetAim.azDeg, targetAim.altDeg)
    val x = targetVector dot basis.right
    val y = targetVector dot basis.up
    val arrow = ((atan2(x, y) * DEG) % 360.0 + 360.0) % 360.0

    if (nowMillis - startedAtMillis >= GUIDANCE_TIMEOUT_MS) {
        return GuidanceUpdate(null, frame(distance, arrow, arrived = false), GuidanceEvent.TIMED_OUT)
    }

    val arrivedAt = arrivedAtMillis
    if (arrivedAt != null) {
        if (distance > GUIDANCE_LEAVE_DEG) {
            val resumed = copy(withinArrivalSinceMillis = null, arrivedAtMillis = null)
            return GuidanceUpdate(resumed, frame(distance, arrow, arrived = false), GuidanceEvent.NONE)
        }
        if (nowMillis - arrivedAt >= GUIDANCE_ARRIVAL_HOLD_MS) {
            return GuidanceUpdate(null, frame(distance, arrow, arrived = true), GuidanceEvent.COMPLETED)
        }
        return GuidanceUpdate(this, frame(distance, arrow, arrived = true), GuidanceEvent.NONE)
    }

    if (distance > GUIDANCE_ARRIVAL_DEG) {
        val next = if (withinArrivalSinceMillis == null) this else copy(withinArrivalSinceMillis = null)
        return GuidanceUpdate(next, frame(distance, arrow, arrived = false), GuidanceEvent.NONE)
    }

    val withinSince = withinArrivalSinceMillis ?: nowMillis
    if (nowMillis - withinSince < GUIDANCE_ARRIVAL_DWELL_MS) {
        val next = if (withinArrivalSinceMillis == null) copy(withinArrivalSinceMillis = withinSince) else this
        return GuidanceUpdate(next, frame(distance, arrow, arrived = false), GuidanceEvent.NONE)
    }

    val arrived = copy(arrivedAtMillis = nowMillis, withinArrivalSinceMillis = withinSince)
    return GuidanceUpdate(arrived, frame(distance, arrow, arrived = true), GuidanceEvent.ARRIVED)
}

private fun GuidanceSession.frame(distance: Double, arrow: Double, arrived: Boolean) = GuidanceFrame(
    targetName = target.nameJa,
    distanceDeg = distance,
    arrowClockwiseDeg = arrow,
    near = distance <= GUIDANCE_NEAR_DEG,
    arrived = arrived,
)

const val GUIDANCE_NEAR_DEG = 10.0
const val GUIDANCE_ARRIVAL_DEG = 5.0
const val GUIDANCE_LEAVE_DEG = 8.0
const val GUIDANCE_ARRIVAL_DWELL_MS = 500L
const val GUIDANCE_ARRIVAL_HOLD_MS = 3_000L
const val GUIDANCE_TIMEOUT_MS = 60_000L

/** 星図が名前付きで描く月・惑星。地平線の下も「今は見えない」と答えるため残す。 */
fun bodyGuidanceTargets(site: Site, epochMillis: Long): List<GuidanceTarget> =
    SolarSystemBody.entries.map { body ->
        val aa = bodyAltAz(body, site, epochMillis)
        GuidanceTarget(
            id = "body:${body.name}",
            nameJa = body.nameJa,
            kind = GuidanceTargetKind.BODY,
            aim = Look(aa[0], aa[1]),
            aliases = when (body) {
                SolarSystemBody.MOON -> setOf("お月様", "おつきさま")
                else -> emptySet()
            },
        )
    }
