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
    data class ClarifyIntent(val targets: List<GuidanceTarget>) : GuidanceRequest
    data class Start(val target: GuidanceTarget) : GuidanceRequest
}

/** AIへ操作可否を渡さず、端末が持つ名前と許可した言い方だけから案内要求を取り出す。 */
object GuidanceRequestParser {
    /** これが無い質問を、勝手に案内へ変えない。 */
    private val guidanceWords = listOf(
        "どこ", "何処", "どっち", "場所", "方角", "方向", "位置", "向き",
        "案内", "ナビ", "導いて", "みちびいて", "連れて", "つれて",
        "見せて", "みせて", "見たい", "みたい", "探して", "さがして", "見つけたい", "みつけたい",
    ).map(::normalize)

    /** 案内語と同時に来たら、一度に二つの操作をせず聞き返す。 */
    private val explanationWords = listOf(
        "解説", "説明", "特徴", "神話", "由来", "とは", "どんな",
    ).map(::normalize)

    /** 「場所を教えて」は案内。「土星について教えて」は通常の解説へ流す。 */
    private val tellWords = listOf("教えて", "おしえて").map(::normalize)

    /** 対象だけ、または「お願い」だけでは、案内か解説かを決めない。 */
    private val vagueWords = listOf("お願い", "おねがい", "頼む", "たのむ").map(::normalize)
    private val stopWords = listOf("案内をやめて", "案内を止めて", "案内終了", "もういい")
        .map(::normalize)

    fun parse(text: String, targets: Collection<GuidanceTarget>): GuidanceRequest {
        val normalized = normalize(text)
        if (stopWords.any { it in normalized }) return GuidanceRequest.Stop

        data class Match(val target: GuidanceTarget, val start: Int, val end: Int)
        val matches = targets.flatMap { target ->
            aliasesOf(target).flatMap { alias ->
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

        val asksGuidance = guidanceWords.any { it in normalized }
        val asksExplanation = explanationWords.any { it in normalized }
        val asksToTell = tellWords.any { it in normalized }

        // 「土星を案内して、特徴も解説して」は一度に実行せず、利用者に一つ選んでもらう。
        if (asksGuidance && asksExplanation) return GuidanceRequest.ClarifyIntent(found)

        // 「場所を教えて」の「教えて」は説明要求ではなく、場所を求める言い方。
        if (asksGuidance) {
            return when (found.size) {
                0 -> GuidanceRequest.UnknownTarget
                1 -> GuidanceRequest.Start(found.single())
                else -> GuidanceRequest.MultipleTargets
            }
        }

        // 明示された解説と、案内語の無い普通の質問は従来の質問回答へ渡す。
        if (asksExplanation || asksToTell) return GuidanceRequest.NotGuidance

        val aliasOnly = found.size == 1 && normalized in aliasesOf(found.single()).map(::normalize)
        if (found.isNotEmpty() && (aliasOnly || vagueWords.any { it in normalized })) {
            return GuidanceRequest.ClarifyIntent(found)
        }

        return GuidanceRequest.NotGuidance
    }

    private fun aliasesOf(target: GuidanceTarget): Set<String> =
        target.aliases + target.nameJa + defaultAliases(target)

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

/** 案内を始められない理由。矢印を作る前に、表示と読み上げへ同じ文を渡す。 */
fun guidanceUnavailableMessage(target: GuidanceTarget): String? = when {
    !target.aim.azDeg.isFinite() || !target.aim.altDeg.isFinite() ->
        "${target.nameJa}の位置を計算できませんでした。"
    target.aim.altDeg <= 0.0 -> "${target.nameJa}は、いま地平線の下にあります。"
    else -> null
}

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
                SolarSystemBody.MOON -> setOf("つき", "お月様", "おつきさま")
                SolarSystemBody.VENUS -> setOf(
                    "きんせい", "明けの明星", "あけのみょうじょう", "宵の明星", "よいのみょうじょう",
                )
                SolarSystemBody.MARS -> setOf("かせい")
                SolarSystemBody.JUPITER -> setOf("もくせい")
                SolarSystemBody.SATURN -> setOf("どせい", "サターン")
                SolarSystemBody.MERCURY -> setOf("すいせい")
                SolarSystemBody.URANUS -> setOf("てんのうせい")
                SolarSystemBody.NEPTUNE -> setOf("かいおうせい")
                SolarSystemBody.PLUTO -> setOf("めいおうせい")
            },
        )
    }
