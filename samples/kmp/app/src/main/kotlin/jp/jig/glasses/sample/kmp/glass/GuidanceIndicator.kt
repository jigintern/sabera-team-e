package jp.jig.glasses.sample.kmp.glass

import jp.jig.glasses.sample.kmp.sky.GuidanceDirection
import jp.jig.glasses.sample.kmp.sky.GuidanceFrame
import kotlin.math.cos
import kotlin.math.sin

data class GuidancePoint(val x: Double, val y: Double)

sealed interface GuidanceIndicatorGeometry {
    /**
     * 塗りつぶした矢印。**細線と薄い点では描かない。**
     *
     * 最初は「柔らかいシェブロン＋明るさの違う3つの点」だったが、実機で
     * **「矢印が非常に分かりにくい」**（2026-08-24）。緑 8 階調の下の段は屋外の空に負け、
     * 2〜4px の点は**背後の星と見分けが付かない**（星図も点で星を描いている）。
     * 案内は「どちらを向くか」だけを伝える印なので、**最上段の階調で面として描く**。
     */
    data class Arrow(
        val tail: GuidancePoint,
        val tip: GuidancePoint,
        /** 軸の太さ（半分）。線幅ではなく塗る帯の幅 */
        val shaftHalfWidth: Double,
        /** 頭の三角の底辺。[tip] と結んで塗る */
        val headBase: List<GuidancePoint>,
    ) : GuidanceIndicatorGeometry

    data class Arrival(
        val innerRadius: Double,
        val outerRadius: Double,
        val starArm: Double,
        /** 輪の太さ（半分）。ここも 1px の輪では見えない */
        val strokeHalfWidth: Double,
    ) : GuidanceIndicatorGeometry
}

/**
 * 案内の小画像の枠。
 *
 * **枠を形に合わせると、同じバッファでより長い矢印が描ける。** 矢印は必ず軸に沿うので、
 * 正方形では短い辺のぶんが丸ごと無駄になる。星図（528×330）と足して
 * [CANVAS_IMAGE_BUFFER_BYTES] に収める必要があり、`width * height * 2` が
 * そのまま効くため、**細長い枠のほうが安い**（120×48 は 80×80 より小さく、1.5 倍長い）。
 */
data class GuidanceIndicatorBox(val width: Int, val height: Int) {
    /** 正規化した形を画素へ直す倍率。長辺に合わせる */
    val span: Int get() = maxOf(width, height)
}

fun guidanceIndicatorBox(frame: GuidanceFrame): GuidanceIndicatorBox = when (frame.arrowDirection()) {
    GuidanceDirection.LEFT, GuidanceDirection.RIGHT ->
        GuidanceIndicatorBox(GUIDANCE_ARROW_LONG_PX, GUIDANCE_ARROW_SHORT_PX)
    GuidanceDirection.UP, GuidanceDirection.DOWN ->
        GuidanceIndicatorBox(GUIDANCE_ARROW_SHORT_PX, GUIDANCE_ARROW_LONG_PX)
    null -> GuidanceIndicatorBox(GUIDANCE_ARRIVAL_SIZE_PX, GUIDANCE_ARRIVAL_SIZE_PX)
}

/** グラスとスマホで同じ向き・比率を使う、枠の中心を原点とした案内表示。 */
fun guidanceIndicatorGeometry(frame: GuidanceFrame): GuidanceIndicatorGeometry {
    // **方向の無い到着前のフレームで落とさない。** 作られないはずだが、ここは
    // グラスの描画とスマホの Canvas の両方から呼ばれるので、投げると観測画面ごと落ちる。
    val direction = when (frame.arrowDirection()) {
        GuidanceDirection.LEFT -> GuidancePoint(-1.0, 0.0)
        GuidanceDirection.RIGHT -> GuidancePoint(1.0, 0.0)
        GuidanceDirection.UP -> GuidancePoint(0.0, -1.0)
        GuidanceDirection.DOWN -> GuidancePoint(0.0, 1.0)
        null -> return GuidanceIndicatorGeometry.Arrival(
            innerRadius = 0.24,
            outerRadius = 0.34,
            starArm = 0.10,
            strokeHalfWidth = 0.028,
        )
    }
    // 10°以内は短くする。**細くはしない**（近いほど見えにくくなっては意味がない）
    val half = if (frame.near) NEAR_HALF_LENGTH else FAR_HALF_LENGTH
    val tail = GuidancePoint(-direction.x * half, -direction.y * half)
    val tip = GuidancePoint(direction.x * half, direction.y * half)
    val headBase = listOf(-HEAD_SPREAD_DEG, HEAD_SPREAD_DEG).map { offsetDeg ->
        val angle = offsetDeg * Math.PI / 180.0
        val c = cos(angle)
        val s = sin(angle)
        val x = c * direction.x - s * direction.y
        val y = s * direction.x + c * direction.y
        GuidancePoint(tip.x + x * HEAD_LENGTH, tip.y + y * HEAD_LENGTH)
    }
    return GuidanceIndicatorGeometry.Arrow(tail, tip, SHAFT_HALF_WIDTH, headBase)
}

/** 矢印を出す向き。到着したら向きは無い。 */
private fun GuidanceFrame.arrowDirection(): GuidanceDirection? = if (arrived) null else direction

/** 枠の長辺いっぱいまで使う。**中央の小さな印では気付かれない** */
private const val FAR_HALF_LENGTH = 0.44
private const val NEAR_HALF_LENGTH = 0.30

/**
 * 頭の長さと開き。
 *
 * **頭は軸の 3 倍以上ひらく。** 145°・0.20 で描いたときは、頭が軸より 5px しか
 * 広がらず**「棒に小さな瘤が付いた形」**にしかならなかった（描いて数えて確かめた）。
 * いまは軸 13px に対して頭が 45px 幅・22px 長で、一目で矢印に見える。
 */
private const val HEAD_LENGTH = 0.26
private const val HEAD_SPREAD_DEG = 133.0

/** 軸の太さ（半分）。120px の枠で 12px の帯になる */
private const val SHAFT_HALF_WIDTH = 0.050

/**
 * 矢印の枠。**星図（528×330）と足してバッファに収まる上限の目安**
 * （`120 * 56 * 2` ＋ 圧縮後 ≒ 14,000 バイト。星図の圧縮後に 17,000 バイト残る）。
 *
 * **同じ量なら細長いほうが長く描ける。** 13,440 バイトを正方形（82×82）に使うと
 * 矢印は 72px しか取れないが、120×56 なら 105px になる。
 */
const val GUIDANCE_ARROW_LONG_PX = 120
const val GUIDANCE_ARROW_SHORT_PX = 56

/** 到着の輪は向きを持たないので正方形。矢印より小さくても、動かないので読める */
const val GUIDANCE_ARRIVAL_SIZE_PX = 80
