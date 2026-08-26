package jp.jig.glasses.sample.kmp.sky

import kotlin.math.abs
import kotlin.math.atan2

enum class GuidanceEvent {
    NONE,
    ARRIVED,
    COMPLETED,
    TIMED_OUT,
}

/** 斜めの矢印を避け、最初に左右、次に上下だけを案内する。 */
enum class GuidanceStage {
    HORIZONTAL,
    VERTICAL,
    ARRIVED,
}

enum class GuidanceDirection {
    LEFT,
    RIGHT,
    UP,
    DOWN,
}

data class GuidanceFrame(
    val targetName: String,
    val distanceDeg: Double,
    val stage: GuidanceStage,
    val direction: GuidanceDirection?,
    /** 水平を保った接平面上の左右差。右が正。 */
    val horizontalErrorDeg: Double,
    /** 水平を保った接平面上の上下差。上が正。 */
    val verticalErrorDeg: Double,
    val near: Boolean,
) {
    val arrived: Boolean get() = stage == GuidanceStage.ARRIVED
}

data class GuidanceSession(
    val target: GuidanceTarget,
    val startedAtMillis: Long,
    val stage: GuidanceStage = GuidanceStage.HORIZONTAL,
    val withinArrivalSinceMillis: Long? = null,
    val arrivedAtMillis: Long? = null,
)

data class GuidanceUpdate(
    val session: GuidanceSession?,
    val frame: GuidanceFrame,
    val event: GuidanceEvent,
)

/**
 * 左右差を5°まで合わせてから上下へ進み、左右差が8°を超えたら最初の段階へ戻す。
 * 最後は従来どおり角距離5°へ0.5秒留まると到着する。
 *
 * ここで使う左右・上下は水平を保った空の接平面であり、
 * 首を傾けるロールとは分ける。方位合わせには±5〜15°の残差があるため、
 * 表示では「到着」ではなく「このあたり」と伝える。
 */
fun GuidanceSession.update(
    currentLook: Look,
    targetAim: Look,
    nowMillis: Long,
): GuidanceUpdate {
    val currentVector = enu(currentLook.azDeg, currentLook.altDeg)
    val targetVector = enu(targetAim.azDeg, targetAim.altDeg)
    val distance = angleBetweenDeg(currentVector, targetVector)
    // ロールを入れない基底で、利用者が実際に行う
    // 「左右を向く」「上下を向く」を分ける。
    val levelBasis = Basis(currentLook.azDeg, currentLook.altDeg)
    val forward = targetVector dot levelBasis.forward
    val horizontalError = atan2(targetVector dot levelBasis.right, forward) * DEG
    val verticalError = atan2(targetVector dot levelBasis.up, forward) * DEG
    val nextStage = when (stage) {
        GuidanceStage.HORIZONTAL -> if (abs(horizontalError) <= GUIDANCE_ARRIVAL_DEG) {
            GuidanceStage.VERTICAL
        } else {
            GuidanceStage.HORIZONTAL
        }
        GuidanceStage.VERTICAL,
        GuidanceStage.ARRIVED,
        -> if (abs(horizontalError) > GUIDANCE_LEAVE_DEG) {
            GuidanceStage.HORIZONTAL
        } else {
            GuidanceStage.VERTICAL
        }
    }

    if (nowMillis - startedAtMillis >= GUIDANCE_TIMEOUT_MS) {
        return GuidanceUpdate(
            session = null,
            frame = frame(distance, horizontalError, verticalError, nextStage),
            event = GuidanceEvent.TIMED_OUT,
        )
    }

    val arrivedAt = arrivedAtMillis
    if (arrivedAt != null) {
        if (distance > GUIDANCE_LEAVE_DEG) {
            val resumed = copy(
                stage = nextStage,
                withinArrivalSinceMillis = null,
                arrivedAtMillis = null,
            )
            return GuidanceUpdate(
                resumed,
                frame(distance, horizontalError, verticalError, nextStage),
                GuidanceEvent.NONE,
            )
        }
        if (nowMillis - arrivedAt >= GUIDANCE_ARRIVAL_HOLD_MS) {
            return GuidanceUpdate(
                null,
                frame(distance, horizontalError, verticalError, GuidanceStage.ARRIVED),
                GuidanceEvent.COMPLETED,
            )
        }
        return GuidanceUpdate(
            this,
            frame(distance, horizontalError, verticalError, GuidanceStage.ARRIVED),
            GuidanceEvent.NONE,
        )
    }

    if (nextStage != GuidanceStage.VERTICAL || distance > GUIDANCE_ARRIVAL_DEG) {
        val next = if (stage == nextStage && withinArrivalSinceMillis == null) {
            this
        } else {
            copy(stage = nextStage, withinArrivalSinceMillis = null)
        }
        return GuidanceUpdate(
            next,
            frame(distance, horizontalError, verticalError, nextStage),
            GuidanceEvent.NONE,
        )
    }

    val withinSince = withinArrivalSinceMillis ?: nowMillis
    if (nowMillis - withinSince < GUIDANCE_ARRIVAL_DWELL_MS) {
        val next = copy(stage = nextStage, withinArrivalSinceMillis = withinSince)
        return GuidanceUpdate(
            next,
            frame(distance, horizontalError, verticalError, nextStage),
            GuidanceEvent.NONE,
        )
    }

    val arrived = copy(
        stage = GuidanceStage.ARRIVED,
        arrivedAtMillis = nowMillis,
        withinArrivalSinceMillis = withinSince,
    )
    return GuidanceUpdate(
        arrived,
        frame(distance, horizontalError, verticalError, GuidanceStage.ARRIVED),
        GuidanceEvent.ARRIVED,
    )
}

private fun GuidanceSession.frame(
    distance: Double,
    horizontalError: Double,
    verticalError: Double,
    stage: GuidanceStage,
): GuidanceFrame = GuidanceFrame(
    targetName = target.nameJa,
    distanceDeg = distance,
    stage = stage,
    direction = when (stage) {
        GuidanceStage.HORIZONTAL -> if (horizontalError < 0.0) GuidanceDirection.LEFT else GuidanceDirection.RIGHT
        GuidanceStage.VERTICAL -> if (verticalError < 0.0) GuidanceDirection.DOWN else GuidanceDirection.UP
        GuidanceStage.ARRIVED -> null
    },
    horizontalErrorDeg = horizontalError,
    verticalErrorDeg = verticalError,
    near = distance <= GUIDANCE_NEAR_DEG,
)

const val GUIDANCE_NEAR_DEG = 10.0
const val GUIDANCE_ARRIVAL_DEG = 5.0
const val GUIDANCE_LEAVE_DEG = 8.0
const val GUIDANCE_ARRIVAL_DWELL_MS = 500L
const val GUIDANCE_ARRIVAL_HOLD_MS = 3_000L
const val GUIDANCE_TIMEOUT_MS = 60_000L

// 案内の進行で画面側が使う周期としきい値

/**
 * 矢印を差し替える間隔。6DoF の到着間隔（約10Hz）に近い値。
 *
 * **POLL_MS（追従の見張り） と同じ 100ms にしない。** 星図の描き直しは `sendGate.tryLock()` で
 * 「塞がっていたら諦める」ので、追従の見張りと矢印の送信が同じ周期だと
 * **位相が噛み合ったまま何秒も星図が描き直されない**（矢印だけ動いて空が古いままになる）。
 * 周期をずらしておけば、噛み合っても 1 秒ほどで抜ける。
 */
const val GUIDANCE_REFRESH_MS = 130L

/** 転送見積りが1周期を超えても、連続送信で他の表示を塞がないための隙間。 */
const val GUIDANCE_REFRESH_MIN_DELAY_MS = 10L

/** 音声で対象を復唱し、見間違いならタップで止められる時間。 */
const val GUIDANCE_CONFIRMATION_MS = 1_000L

/** 地平線すれすれは遮蔽物や大気で見つけにくいため、案内前に断りを入れる。 */
const val GUIDANCE_LOW_ALTITUDE_DEG = 5.0
