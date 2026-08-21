package jp.jig.glasses.sample.kmp.starmap

import app.jigglass.glass.CommandManager

/** グラスのキャンバス座標系。 */
const val PANEL_WIDTH = 576
const val PANEL_HEIGHT = 360

/** バッファ上限に余裕を持って収まる、星図の標準サイズ。 */
const val STAR_MAP_WIDTH = 528
const val STAR_MAP_HEIGHT = 330

const val STAR_MAP_IMAGE_ID = 0
const val CANVAS_TEXT_SLOTS = 8
const val CANVAS_TEXT_BUDGET_BYTES = 190
const val CANVAS_PACKET_BYTES = 200
const val CANVAS_IMAGE_BUFFER_BYTES = 380_000

private const val LABEL_CHAR_WIDTH = 28
private const val LABEL_PADDING = 8
const val CANVAS_LABEL_HEIGHT = 40

/** SDK と同じ 3bit RLE で数えた、画像ペイロードのバイト数。 */
internal fun StarMap.compressedSizeBytes(): Int {
    var bytes = 0
    var i = 0
    val count = width * height
    while (i < count) {
        val value = (gray[i].toInt() and 0xFF) ushr 5
        var run = 1
        while (i + run < count && run < 32 && ((gray[i + run].toInt() and 0xFF) ushr 5) == value) {
            run++
        }
        bytes++
        i += run
    }
    return bytes
}

/** SDK が画像バッファの上限判定に使うサイズ。 */
internal fun StarMap.canvasBufferUsageBytes(): Int = width * height * 2 + compressedSizeBytes()

/** 星座名を、重なりを除いたキャンバスのテキスト要素へ変換する。 */
internal fun StarMap.toCanvasElements(): List<CommandManager.CanvasElement> {
    val offsetX = (PANEL_WIDTH - width) / 2
    val offsetY = (PANEL_HEIGHT - height) / 2
    val shown = ArrayList<CommandManager.CanvasElement>(CANVAS_TEXT_SLOTS)
    for (label in labels) {
        if (shown.size >= CANVAS_TEXT_SLOTS) break
        val elementWidth = (label.text.length * LABEL_CHAR_WIDTH + LABEL_PADDING).coerceAtMost(PANEL_WIDTH)
        val element = CommandManager.CanvasElement(
            id = shown.size,
            x = (offsetX + label.x - elementWidth / 2).coerceIn(0, PANEL_WIDTH - elementWidth),
            y = (offsetY + label.y - CANVAS_LABEL_HEIGHT / 2).coerceIn(0, PANEL_HEIGHT - CANVAS_LABEL_HEIGHT),
            width = elementWidth,
            height = CANVAS_LABEL_HEIGHT,
            text = label.text,
        )
        if (shown.any { it overlaps element }) continue
        shown += element
    }
    return shown
}

/**
 * SDK の 190 バイト制限に収めて送る形にする。**書き換える前に、消す必要のあるスロットを消す。**
 *
 * ファームは**新しい矩形しか描き直さない**。同じ id に前より短い名前や左に寄った名前を置くと、
 * **前の名前の末尾が画面に残る**（実機で「る」の 1 文字が右上に残った）。
 * 消すのは空文字を送ればよいが、**同じ電文の中で消してから置くと順番が保証されない**ので、
 * 消す分だけを先のバッチにまとめる。
 *
 * 消すのは**必要なスロットだけ**にする。全部消してから置き直すと、
 * 位置だけ動かす衛星の印（1.5 秒ごと）で名前が毎回ちらつく。
 */
internal fun List<CommandManager.CanvasElement>.batched(
    previous: List<CommandManager.CanvasElement>,
): List<List<CommandManager.CanvasElement>> {
    val next = associateBy { it.id }
    val cleared = previous.mapNotNull { old ->
        val replacement = next[old.id]
        // 新しい矩形が前の矩形を覆っているなら、そのまま上書きして消え残らない
        if (replacement != null && replacement covers old) return@mapNotNull null
        CommandManager.CanvasElement(id = old.id, x = 0, y = 0, width = 0, height = 0, text = "")
    }
    return chunkByBudget(cleared) + chunkByBudget(this)
}

/** 190 バイトずつに切る。1 要素で超える場合はその 1 つだけで送る */
private fun chunkByBudget(
    elements: List<CommandManager.CanvasElement>,
): List<List<CommandManager.CanvasElement>> {
    val batches = ArrayList<List<CommandManager.CanvasElement>>()
    var current = ArrayList<CommandManager.CanvasElement>()
    var used = 0
    for (element in elements) {
        val elementBytes = element.byteSize()
        if (current.isNotEmpty() && used + elementBytes > CANVAS_TEXT_BUDGET_BYTES) {
            batches += current
            current = ArrayList()
            used = 0
        }
        current += element
        used += elementBytes
    }
    if (current.isNotEmpty()) batches += current
    return batches
}

/** 前の矩形を完全に覆うか。覆っていれば消さずに上書きしてよい */
private infix fun CommandManager.CanvasElement.covers(other: CommandManager.CanvasElement): Boolean =
    x <= other.x && y <= other.y &&
        x + width >= other.x + other.width && y + height >= other.y + other.height

internal fun CommandManager.CanvasElement.byteSize(): Int = 12 + text.toByteArray(Charsets.UTF_8).size

private infix fun CommandManager.CanvasElement.overlaps(other: CommandManager.CanvasElement): Boolean =
    x < other.x + other.width && other.x < x + width && y < other.y + other.height && other.y < y + height
