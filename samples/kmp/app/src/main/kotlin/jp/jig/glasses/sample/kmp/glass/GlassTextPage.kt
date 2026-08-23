package jp.jig.glasses.sample.kmp.glass

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
 * 専用ページにすれば、星図を消したぶんを全部文字に使える。
 *
 * **ただし置ける文字数は 8 行ぶんではなかった。** 8 行置いたとき、実機で
 * **最初の 1〜2 行が消えて途中から始まった**（2026-08-22。「星座の名前と方角と
 * 最初の行が見えない」）。190 バイトは 1 電文あたりの上限だと読んでいたが、
 * **画面に置ける合計でもある**らしく、分割して送っても後から送ったものに押し出される。
 *
 * そこで**字幕のようにめくる**（[pages]）。1 枚は見出し 1 行＋本文 2 行の
 * **189 バイト**で、1 電文に収まる。**何バイトまで置けるかの解釈に関わらず消えない**うえ、
 * 読み上げに合わせて出せるので、長い解説も切らずに出せる。
 */
object GlassTextPage {
    /** 左右の余白 */
    private const val MARGIN_X = 12

    /** 行をパネルの上下中央へ寄せる */
    private val marginY = (PANEL_HEIGHT - CANVAS_TEXT_SLOTS * CANVAS_LABEL_HEIGHT) / 2

    /**
     * 1 行に入る文字数。
     *
     * **[LABEL_CHAR_WIDTH] は実測ではなく見積り。** 実機で分かっているのは
     * 「120 画素に 9 文字は入らなかった」（13.3px/文字では切れた）ことだけで、
     * 真の値は 13〜28px の間にある。**実機で数えたらここだけ直せばよく、画面の作りは変わらない。**
     */
    val lineChars: Int = (PANEL_WIDTH - 2 * MARGIN_X - LABEL_PADDING) / LABEL_CHAR_WIDTH

    /** 見出しの行。**本文は見出しが空でも繰り上げない**（名前が付いた瞬間に全部書き直しになる） */
    private const val HEADER_ROW = 0

    /** 1 枚に出す本文の行数。見出しと合わせて 189 バイトに収まる数 */
    const val BODY_ROWS = 2

    /** 1 枚に出せる本文の文字数 */
    val bodyChars: Int = BODY_ROWS * lineChars

    /** めくる上限。これを超えるぶんは捨てる（解説文はこの中に収めてある） */
    private const val MAX_PAGES = 8

    /** 解説文に許す長さ。めくって出せる総量 */
    val pagedChars: Int = bodyChars * MAX_PAGES

    /**
     * 行頭に置かない文字。折り返し位置に来たら前の行へ吸わせる。
     *
     * 見た目のためだけではない。**吸わせる＝前の行が伸びる**方向にしか変わらないので、
     * あとから文字が届いても前の行の矩形が縮まない（縮むと消し残る）。
     */
    private const val NO_LINE_START = "。、，．,.」』）)]｝}！？!?・…ー〜:;：；"

    /** 禁則で伸ばせる上限。「。。。。」のような並びで 1 行が延々と伸びるのを止める */
    private const val KINSOKU_SLACK = 2

    /** 1 枚ぶんの解説画面。[dropped] は入り切らず捨てた文字数 */
    class Page(val elements: List<CommandManager.CanvasElement>, val dropped: Int)

    /**
     * 解説を**字幕のようにめくる**ための、1 枚ずつの画面。
     *
     * 1 枚は必ず 1 電文に収まるので、**置ける合計バイト数を気にしなくてよくなる**。
     * 読み上げに合わせて送れば、長い解説も切らずに最後まで出せる。
     */
    fun pages(header: String, body: String): List<Page> {
        val lines = wrap(body)
        if (lines.isEmpty()) return listOf(explanation(header, ""))
        val chunks = lines.chunked(BODY_ROWS)
        val dropped = chunks.drop(MAX_PAGES).sumOf { chunk -> chunk.sumOf { it.length } }
        return chunks.take(MAX_PAGES).mapIndexed { index, chunk ->
            val page = explanation(header, chunk.joinToString("\n"))
            // 捨てたぶんは最後の 1 枚に付けて数える。ログで「切れた」と分かればよい
            if (index == chunks.take(MAX_PAGES).lastIndex) Page(page.elements, dropped) else page
        }
    }

    /**
     * 1 枚ぶんの画面を組む。
     *
     * **同じ行は必ず同じ場所に置き、行を組み直さない。** ファームは新しい矩形しか
     * 描き直さないので、組み直すと**前の行の末尾が画面に残る**（実機で踏んだ「る」問題）。
     */
    fun explanation(header: String, body: String): Page {
        val elements = ArrayList<CommandManager.CanvasElement>(1 + BODY_ROWS)
        header.trim().takeIf { it.isNotEmpty() }?.let {
            elements += row(HEADER_ROW, it.take(lineChars))
        }
        val lines = wrap(body)
        for ((index, line) in lines.take(BODY_ROWS).withIndex()) {
            if (line.isBlank()) continue
            elements += row(HEADER_ROW + 1 + index, line)
        }
        return Page(elements, lines.drop(BODY_ROWS).sumOf { it.length })
    }

    /** [lineChars] 文字ずつに折り返す。改行はそのまま行の区切りにする */
    fun wrap(text: String, width: Int = lineChars): List<String> {
        val lines = ArrayList<String>()
        var current = StringBuilder()
        for (ch in text) {
            if (ch == '\n') {
                if (current.isNotEmpty()) lines += current.toString()
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
