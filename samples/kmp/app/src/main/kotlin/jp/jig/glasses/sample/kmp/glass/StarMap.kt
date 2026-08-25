package jp.jig.glasses.sample.kmp.glass

import jp.jig.glasses.sample.kmp.sky.GuidanceDirection
import jp.jig.glasses.sample.kmp.sky.GuidanceFrame
import jp.jig.glasses.sample.kmp.sky.GuidanceStage
import jp.jig.glasses.sample.kmp.sky.GuidanceTargetKind
import kotlin.math.abs
import kotlin.math.roundToInt

// グラスに出す 1 枚ぶんの星図と、その上に置く名前。
// **絵と名前は 1 つの器で持ち歩く。** 別々に計算すると、グラスに「オリオン座」と
// 出ているのに別の星座を喋る（#37）。解説の主役は constellationNames() の先頭。

/**
 * ラベルが何の名前か。
 *
 * **AI 解説の主役はこの種別で選ぶ。** 名前の枠は星座・月惑星・衛星で共有していて、
 * 並び順は「先に消えてほしくないもの」で決まっている（衛星 → 月惑星 → 星座）。
 * 種別を持たせずに先頭を取ると、月が視野にあるだけで主役が「月」になる。
 */
enum class LabelKind {
    /** 案内中の対象名。8枠の先頭へ予約する。 */
    GUIDANCE,
    CONSTELLATION,
    BODY,
    SATELLITE,

    /** 大三角などの結び。**初心者が最初に見つけるものなので、星座名より先に置く** */
    ASTERISM,

    /**
     * 一等星の固有名。
     *
     * **どの星見アプリも出している。** 空で最初に覚えるのは星座名ではなく
     * 「ベガ」「アルタイル」のような星の名前で、それが分かると星座の探し方も決まる。
     */
    STAR_NAME,
}

/** 名前を置く位置。画像には焼かず sendCanvas のテキストとして重ねる */
data class Label(
    val text: String,
    val x: Int,
    val y: Int,
    val kind: LabelKind = LabelKind.CONSTELLATION,
)

/**
 * 星図に出す月・惑星。
 *
 * **惑星は恒星と同じ点で描く。** 肉眼でも点に見えるので、違う描き方をすると嘘になる。
 * 見分けは名前のラベルでつける。**月だけは輪で描く**（見かけの直径 0.5° をそのまま描くと
 * 4px の点になり、明るい星と区別が付かない）。
 */
data class SkyBodyMark(
    val nameJa: String,
    val azDeg: Double,
    val altDeg: Double,
    val magnitude: Double,
    val moon: Boolean = false,
)

/**
 * 流星群の放射点。
 *
 * **名前は焼かない**（テキスト枠は星座名と月惑星で埋まっている）。
 * 中心から外へ短い線が伸びる印にして、**「ここから放射する」を形で読ませる**。
 * 群の名前は一口メモ（`SkyTips`）が喋る。
 */
data class MeteorRadiantMark(val nameJa: String, val azDeg: Double, val altDeg: Double)

/** 到着時に、通常の星図と同じ1枚へ焼き込む対象の強調。 */
data class GuidanceHighlight(
    val nameJa: String,
    val kind: GuidanceTargetKind,
    val azDeg: Double,
    val altDeg: Double,
    val arrived: Boolean,
)

class StarMap(val width: Int, val height: Int, val gray: ByteArray, val labels: List<Label>)

/**
 * グラスに出した星座名を、視野中心に近い順で返す。
 *
 * **AI 解説の主役はここから取る。** 絵とラベルは同じ 1 回の [StarMapRenderer.render] から
 * 出ているので、ここを根拠にすれば「グラスに出ている星座」と「解説する星座」が食い違わない。
 */
fun StarMap.constellationNames(): List<String> =
    labels.filter { it.kind == LabelKind.CONSTELLATION }.map { it.text }

/**
 * 案内の文字を、テキスト枠の先頭 2 つへ置く。
 *
 * **1 行目は「何を案内しているか」、2 行目は「いまどちらへどれだけ首を振るか」。**
 * 実機で矢印が読み取れなかった（2026-08-24）ので、**文字でも同じことを言う**。
 * テキストは 2.1.0 のファームでも出た唯一の経路で、画像より先に信用できる
 * （[02_glass-output.md] のファーム要件）。
 *
 * 2 行目に出すのは**目標までの角距離ではなく、いまの段で詰める差**（左右か上下）。
 * 首を振るとこれが減るので、**案内が自分の動きを追えていることが数字で分かる**。
 */
fun StarMap.withGuidanceLabel(frame: GuidanceFrame, where: String? = null): StarMap {
    val name = frame.targetName
    // **どちらを向くかを文字でも残す**（ガイドだけ [where] を渡す）。
    // 騒がしい場所では文字が主役なので、声を聞き逃すと方角が分からなくなっていた。
    // 到着したら方角はもう要らないので「このあたり」へ戻す。
    val text = when {
        frame.arrived -> "$name このあたり"
        where != null -> "$name $where"
        else -> "$name 案内中"
    }
    val guidance = buildList {
        add(Label(text, width / 2, GUIDANCE_LABEL_Y_PX, LabelKind.GUIDANCE))
        guidanceTurnText(frame)?.let {
            add(Label(it, width / 2, GUIDANCE_TURN_LABEL_Y_PX, LabelKind.GUIDANCE))
        }
    }
    // 周囲の名前が並ぶと、どれへ向かっているかを読み違える。星と天体名は残し、
    // 星座と大三角などの結びの名前だけを案内中は引く。
    val focused = labels.filterNot {
        it.kind == LabelKind.CONSTELLATION || it.kind == LabelKind.ASTERISM
    }
    return StarMap(width, height, gray, guidance + focused)
}

/**
 * いまの段で詰める首振りの量。**到着したら出さない。**
 *
 * **度で言う。** 高さの言い換え（`ImpromptuGuide.heightWord`）と違って、
 * ここは「あとどれだけ動かすか」なので、減っていく数字そのものが手がかりになる。
 */
internal fun guidanceTurnText(frame: GuidanceFrame): String? {
    val direction = frame.direction ?: return null
    if (frame.arrived) return null
    val remaining = when (frame.stage) {
        GuidanceStage.HORIZONTAL -> abs(frame.horizontalErrorDeg)
        GuidanceStage.VERTICAL -> abs(frame.verticalErrorDeg)
        GuidanceStage.ARRIVED -> return null
    }
    val word = when (direction) {
        GuidanceDirection.LEFT -> "左"
        GuidanceDirection.RIGHT -> "右"
        GuidanceDirection.UP -> "上"
        GuidanceDirection.DOWN -> "下"
    }
    return "${word}へ ${remaining.roundToInt().coerceAtLeast(1)}°"
}

/** 案内が使うテキスト枠の数。**星座名へ回せる枠がそのぶん減る** */
const val GUIDANCE_LABEL_SLOTS = 2

private const val GUIDANCE_LABEL_Y_PX = 24

/** 1 行目の真下。**矢印（中央）には重ねない** */
private const val GUIDANCE_TURN_LABEL_Y_PX = 68
