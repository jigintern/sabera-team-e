package jp.jig.glasses.sample.kmp.starmap

import app.jigglass.glass.CommandManager

/** グラスに出しているページ。**星図と解説は同居させない**（#40）。 */
enum class GlassPage {
    STAR_MAP,

    /** AI 解説の専用画面。星図の画像を消し、テキスト要素だけで組む */
    EXPLANATION,
}

/**
 * グラスへ**文字だけの画面**を出すための組版（#40）。
 *
 * 「解説文はグラスに出さない」と決めていたのは、**星図の上に重ねる前提**だったため。
 * 専用ページにすると数字が変わる。
 *
 * - **190 バイトは 1 電文あたり**で、画面の合計ではない（[batched] が分割して送る）
 * - 枠は 8 つ。1 行 1 枠として **8 行**（[CANVAS_LABEL_HEIGHT] × 8 = 320 でパネルに収まる）
 * - 1 行 [lineChars] 文字なので、見出し 1 行を除いて **本文 [bodyChars] 文字**
 *
 * 転送は 8 行で 600 バイト弱＝4 電文で 40ms ほど。星図画像の 332〜390ms に対して 1/10 なので、
 * **SSE で 1 文届くたびに描き足せる**。
 */
object GlassTextPage {
    /** 左右の余白 */
    private const val MARGIN_X = 12

    /** 8 行をパネルの上下中央へ寄せる */
    private val marginY = (PANEL_HEIGHT - CANVAS_TEXT_SLOTS * CANVAS_LABEL_HEIGHT) / 2

    /**
     * 1 行に入る文字数。
     *
     * **[LABEL_CHAR_WIDTH] は実測ではなく見積り。** 実機で分かっているのは
     * 「120 画素に 9 文字は入らなかった」（13.3px/文字では切れた）ことだけで、
     * 真の値は 13〜28px の間にある。**実機で数えたらここだけ直せばよく、画面の作りは変わらない。**
     */
    val lineChars: Int = (PANEL_WIDTH - 2 * MARGIN_X - LABEL_PADDING) / LABEL_CHAR_WIDTH

    /**
     * 見出しに 1 行使う。残りが本文。
     *
     * **見出しが空でも本文は繰り上げない。** タップした直後は星座名がまだ決まっていないので、
     * 繰り上げると名前が付いた瞬間に本文が 1 行下がり、全部書き直しになる。
     */
    private const val HEADER_ROW = 0
    val bodyRows: Int = CANVAS_TEXT_SLOTS - 1
    val bodyChars: Int = bodyRows * lineChars

    /**
     * 行頭に置かない文字。折り返し位置に来たら前の行へ吸わせる。
     *
     * 見た目のためだけではない。**吸わせる＝前の行が伸びる**方向にしか変わらないので、
     * あとから文字が届いても前の行の矩形が縮まない（縮むと消し残る）。
     */
    private const val NO_LINE_START = "。、，．,.」』）)]｝}！？!?・…ー〜:;：；"

    /** 禁則で伸ばせる上限。「。。。。」のような並びで 1 行が延々と伸びるのを止める */
    private const val KINSOKU_SLACK = 2

    /** 1 回ぶんの解説画面。[dropped] は 8 行に入り切らず捨てた文字数 */
    class Page(val elements: List<CommandManager.CanvasElement>, val dropped: Int)

    /**
     * 解説画面を組む。
     *
     * **同じ行は必ず同じ場所に置き、文字は増える方向にしか変わらない。**
     * 折り返しは先頭からの貪欲法なので、あとから文字が届いても**前の行の折り返し位置は動かない**。
     * これは見やすさの話ではなく、ファームの制約から来ている。ファームは新しい矩形しか
     * 描き直さないので、行を組み直すと**前の行の末尾が画面に残る**（実機で踏んだ「る」問題）。
     */
    fun explanation(header: String, body: String): Page {
        val elements = ArrayList<CommandManager.CanvasElement>(CANVAS_TEXT_SLOTS)
        header.trim().takeIf { it.isNotEmpty() }?.let {
            elements += row(HEADER_ROW, it.take(lineChars))
        }
        val lines = wrap(body)
        for ((index, line) in lines.take(bodyRows).withIndex()) {
            if (line.isBlank()) continue
            elements += row(HEADER_ROW + 1 + index, line)
        }
        return Page(elements, lines.drop(bodyRows).sumOf { it.length })
    }

    /** [lineChars] 文字ずつに折り返す。改行はそのまま行の区切りにする */
    fun wrap(text: String, width: Int = lineChars): List<String> {
        val lines = ArrayList<String>()
        var current = StringBuilder()
        for (ch in text) {
            if (ch == '\n') {
                lines += current.toString()
                current = StringBuilder()
                continue
            }
            // 行頭の空白は読みにくいだけなので捨てる
            if (current.isEmpty() && (ch == ' ' || ch == '　')) continue
            if (current.length < width) {
                current.append(ch)
                continue
            }
            if (ch in NO_LINE_START && current.length < width + KINSOKU_SLACK) {
                current.append(ch)
                continue
            }
            lines += current.toString()
            current = StringBuilder().append(ch)
        }
        if (current.isNotEmpty()) lines += current.toString()
        return lines
    }

    private fun row(row: Int, text: String) = CommandManager.CanvasElement(
        id = row,
        x = MARGIN_X,
        y = marginY + row * CANVAS_LABEL_HEIGHT,
        width = (text.length * LABEL_CHAR_WIDTH + LABEL_PADDING)
            .coerceAtMost(PANEL_WIDTH - MARGIN_X),
        height = CANVAS_LABEL_HEIGHT,
        text = text,
    )
}
