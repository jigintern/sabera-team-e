package jp.jig.glasses.sample.kmp.glass

import app.jigglass.glass.CommandManager

/** グラスのキャンバス座標系。 */
const val PANEL_WIDTH = 576
const val PANEL_HEIGHT = 360

/**
 * 星図の標準サイズ。**バッファ上限に余裕を持って収まる**。
 *
 * [STAR_MAP_MAX_WIDTH] で入らなかったときの落とし先。
 */
const val STAR_MAP_WIDTH = 528
const val STAR_MAP_HEIGHT = 330

/**
 * 画像 1 枚の上限いっぱい。16:10 でこれ以上大きくすると必ず弾かれる。
 *
 * `width * height * 2` だけで 369,920 バイトを使うので、圧縮後に残るのは **10,080 バイト**しかない。
 * 背景の下限だけで 5,780 バイト（面積 / 32）なので、星と線と絵で 4,300 バイトを超えると入らない。
 * **入るかどうかは空の濃さと向きで変わる**ので、送る前に同じ式で数えて、
 * 溢れたら [STAR_MAP_WIDTH] へ落とす。
 */
const val STAR_MAP_MAX_WIDTH = 544
const val STAR_MAP_MAX_HEIGHT = 340

const val STAR_MAP_IMAGE_ID = 0
const val CANVAS_TEXT_SLOTS = 8
const val CANVAS_TEXT_BUDGET_BYTES = 190
const val CANVAS_PACKET_BYTES = 200
const val CANVAS_IMAGE_BUFFER_BYTES = 380_000

internal const val LABEL_CHAR_WIDTH = 28
internal const val LABEL_PADDING = 8
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

/**
 * 星座名を、重なりを除いたキャンバスのテキスト要素へ変換する。
 *
 * **枠の数（8）とバイト数（190）の両方で打ち切る。** 190 バイトは 1 電文の上限であると
 * 同時に**画面に置ける合計でもある**（#40。分けて送ると先に置いたぶんが押し出されて消えた）。
 * 数だけで打ち切っていたときは、日本語の名前 8 個で 200 バイトを超え、
 * [batched] が 2 電文に割ったところで**先頭の枠から消えていた**。
 * 先頭は案内のラベルなので、**案内中にいちばん消えてはいけない文字が消える**。
 */
internal fun StarMap.toCanvasElements(): List<CommandManager.CanvasElement> {
    val offsetX = (PANEL_WIDTH - width) / 2
    val offsetY = (PANEL_HEIGHT - height) / 2
    val shown = ArrayList<CommandManager.CanvasElement>(CANVAS_TEXT_SLOTS)
    var used = 0
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
        // 入らない名前は飛ばして次を見る。**並びは優先順位**（案内 → 衛星 → 月惑星 → …）なので、
        // 打ち切らずに続けると、余ったバイトへ短い名前が入る
        val bytes = element.byteSize()
        if (used + bytes > CANVAS_TEXT_BUDGET_BYTES) continue
        used += bytes
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
    return chunkByBudget(clearsFor(previous)) + chunkByBudget(this)
}

/**
 * [batched] と同じだが、**内容が変わっていない枠は送らない**。
 *
 * AI 解説の画面（#40）は SSE で文字が届くたびに組み直す。伸びた行だけを送れば
 * 1 回の更新は 1 電文で済み、変わっていない行を描き直させて**ちらつかせずに済む**。
 * 星図のラベルは毎フレーム位置が変わるので、こちらは使わない。
 */
internal fun List<CommandManager.CanvasElement>.updatesFrom(
    previous: List<CommandManager.CanvasElement>,
): List<List<CommandManager.CanvasElement>> {
    val before = previous.associateBy { it.id }
    val changed = filter { element -> before[element.id]?.sameAs(element) != true }
    return chunkByBudget(clearsFor(previous)) + chunkByBudget(changed)
}

/**
 * テキスト枠を全部空にする電文。**掃除用。**
 *
 * ファームは**消すまでテキストを持ち続ける**ので、前に動いていたアプリ（や前の版）が
 * 置いた文字が残っていると、あとから送った画像に**重なって出る**（実機で踏んだ）。
 * 何が残っているか知りようがないので、**8 枠ぶんまとめて空にする**。
 * 12 バイト × 8 = 96 バイトで 1 電文に収まる。
 */
internal fun clearedCanvasText(): List<CommandManager.CanvasElement> =
    (0 until CANVAS_TEXT_SLOTS).map { id ->
        CommandManager.CanvasElement(id = id, x = 0, y = 0, width = 0, height = 0, text = "")
    }

/** 上書きでは消え残る枠を、先に空文字で消す */
private fun List<CommandManager.CanvasElement>.clearsFor(
    previous: List<CommandManager.CanvasElement>,
): List<CommandManager.CanvasElement> {
    val next = associateBy { it.id }
    return previous.mapNotNull { old ->
        val replacement = next[old.id]
        // 新しい矩形が前の矩形を覆っているなら、そのまま上書きして消え残らない
        if (replacement != null && replacement covers old) return@mapNotNull null
        CommandManager.CanvasElement(id = old.id, x = 0, y = 0, width = 0, height = 0, text = "")
    }
}

private infix fun CommandManager.CanvasElement.sameAs(other: CommandManager.CanvasElement): Boolean =
    id == other.id && x == other.x && y == other.y &&
        width == other.width && height == other.height && text == other.text

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

/** 3bit 緑の最上段。**中間の階調は屋外で消える**ので、目立たせたいものはこの値で描く */
const val INK_LIT = 255
