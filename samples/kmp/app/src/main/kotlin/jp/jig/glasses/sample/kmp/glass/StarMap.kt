package jp.jig.glasses.sample.kmp.glass

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
    /** 現在の空と取り違えないための、シミュレーション場所・時刻。 */
    STATUS,
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

class StarMap(val width: Int, val height: Int, val gray: ByteArray, val labels: List<Label>)

/**
 * グラスに出した星座名を、視野中心に近い順で返す。
 *
 * **AI 解説の主役はここから取る。** 絵とラベルは同じ 1 回の [StarMapRenderer.render] から
 * 出ているので、ここを根拠にすれば「グラスに出ている星座」と「解説する星座」が食い違わない。
 */
fun StarMap.constellationNames(): List<String> =
    labels.filter { it.kind == LabelKind.CONSTELLATION }.map { it.text }

/** シミュレーション条件を最優先のテキスト枠として下端へ置く。 */
fun StarMap.withStatusLabel(text: String): StarMap {
    val status = Label(
        text = text,
        x = width / 2,
        y = height - STATUS_BOTTOM_PX,
        kind = LabelKind.STATUS,
    )
    return StarMap(width, height, gray, listOf(status) + labels)
}

private const val STATUS_BOTTOM_PX = 20
