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
    /**
     * 左右どちらへ回ると決めたか。**対象が遠いあいだだけ握る**（近づいたら null に戻る）。
     *
     * 真後ろは「左へ 180°」と「右へ 180°」が同じ場所を指すので、
     * 首がわずかに揺れるだけで矢印が反転する。決めた向きを持ち回って止める。
     */
    val turnRight: Boolean? = null,
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
    // **真後ろで左右がぱたつかないよう、回る向きを握る。** 対象が背後にあると
    // `atan2` は ±180° を跨ぐので、首の揺れだけで符号が反転する（矢印が左右に踊る）。
    // 180° 右回りでも同じ場所に着くので、握った向きのまま回ってもらってよい。
    // 90° を切れば符号は安定するので、そこで放して実際の向きに戻す。
    val turning = when {
        abs(horizontalError) < GUIDANCE_TURN_LATCH_DEG -> null
        turnRight != null -> turnRight
        else -> horizontalError >= 0.0
    }
    val base = if (turnRight == turning) this else copy(turnRight = turning)
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
            frame = base.frame(distance, horizontalError, verticalError, nextStage),
            event = GuidanceEvent.TIMED_OUT,
        )
    }

    val arrivedAt = arrivedAtMillis
    if (arrivedAt != null) {
        if (distance > GUIDANCE_LEAVE_DEG) {
            val resumed = base.copy(
                stage = nextStage,
                withinArrivalSinceMillis = null,
                arrivedAtMillis = null,
            )
            return GuidanceUpdate(
                resumed,
                base.frame(distance, horizontalError, verticalError, nextStage),
                GuidanceEvent.NONE,
            )
        }
        if (nowMillis - arrivedAt >= GUIDANCE_ARRIVAL_HOLD_MS) {
            return GuidanceUpdate(
                null,
                base.frame(distance, horizontalError, verticalError, GuidanceStage.ARRIVED),
                GuidanceEvent.COMPLETED,
            )
        }
        return GuidanceUpdate(
            base,
            base.frame(distance, horizontalError, verticalError, GuidanceStage.ARRIVED),
            GuidanceEvent.NONE,
        )
    }

    if (nextStage != GuidanceStage.VERTICAL || distance > GUIDANCE_ARRIVAL_DEG) {
        val next = if (stage == nextStage && withinArrivalSinceMillis == null) {
            base
        } else {
            base.copy(stage = nextStage, withinArrivalSinceMillis = null)
        }
        return GuidanceUpdate(
            next,
            base.frame(distance, horizontalError, verticalError, nextStage),
            GuidanceEvent.NONE,
        )
    }

    val withinSince = withinArrivalSinceMillis ?: nowMillis
    if (nowMillis - withinSince < GUIDANCE_ARRIVAL_DWELL_MS) {
        val next = base.copy(stage = nextStage, withinArrivalSinceMillis = withinSince)
        return GuidanceUpdate(
            next,
            base.frame(distance, horizontalError, verticalError, nextStage),
            GuidanceEvent.NONE,
        )
    }

    val arrived = base.copy(
        stage = GuidanceStage.ARRIVED,
        arrivedAtMillis = nowMillis,
        withinArrivalSinceMillis = withinSince,
    )
    return GuidanceUpdate(
        arrived,
        base.frame(distance, horizontalError, verticalError, GuidanceStage.ARRIVED),
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
        // 握った向きがあればそちらを優先する（真後ろでの反転を止めるのが目的）
        GuidanceStage.HORIZONTAL -> if (turnRight ?: (horizontalError >= 0.0)) {
            GuidanceDirection.RIGHT
        } else {
            GuidanceDirection.LEFT
        }
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

/**
 * ここより左右差が大きい間は、**回る向きを握ったまま動かさない**。
 *
 * 90° を境にしたのは、`atan2` の符号が不安定になるのが対象の真後ろ側（`forward < 0`）だから。
 * 前を向いている側まで来れば符号は素直に動くので、そこで放して実際の向きに従う。
 */
const val GUIDANCE_TURN_LATCH_DEG = 90.0
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
