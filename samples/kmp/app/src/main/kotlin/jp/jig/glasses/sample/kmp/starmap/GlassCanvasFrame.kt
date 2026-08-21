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

/** SDK の 190 バイト制限に収めつつ、前フレームで余った要素も消す。 */
internal fun List<CommandManager.CanvasElement>.batched(
    previousCount: Int,
): List<List<CommandManager.CanvasElement>> {
    val cleared = (size until previousCount.coerceAtMost(CANVAS_TEXT_SLOTS)).map { id ->
        CommandManager.CanvasElement(id = id, x = 0, y = 0, width = 0, height = 0, text = "")
    }
    val batches = ArrayList<List<CommandManager.CanvasElement>>()
    var current = ArrayList<CommandManager.CanvasElement>()
    var used = 0
    for (element in this + cleared) {
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

internal fun CommandManager.CanvasElement.byteSize(): Int = 12 + text.toByteArray(Charsets.UTF_8).size

private infix fun CommandManager.CanvasElement.overlaps(other: CommandManager.CanvasElement): Boolean =
    x < other.x + other.width && other.x < x + width && y < other.y + other.height && other.y < y + height
