package jp.jig.glasses.sample.kmp.glass

import app.jigglass.glass.CommandManager

/** グラスに出しているページ。**星図と解説は同居させない**（#40）。 */
enum class GlassPage {
    STAR_MAP,

    /** AI 解説の専用画面。星図の画像を消し、テキスト要素だけで組む */
    EXPLANATION,

    /**
     * 読み込み中の画面。**星図が描けるようになるまで、グラスは真っ暗だった。**
     *
     * 解説画面と同じテキストだけの組版を使う（**画像は使わない**）。
     * 全画面の画像は 1 枚 332〜390ms かかるうえ転送中は前の絵が消えるので、
     * **回るものを画像で描くと点滅にしかならない**。
     */
    LOADING,
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
 * そこで**字幕のように流す**（[pages]）。1 枚は **3 行**で、日本語で埋まっても
 * **189 バイト**（[lineChars]）。1 電文に収まるので**何バイトまで置けるかの解釈に
 * 関わらず消えない**うえ、読み上げに合わせて出せるので、長い解説も切らずに出せる。
 *
 * **1 枚目だけ見出し＋本文 2 行、そこから先は本文 3 行。** 見出しを出したままでは
 * 本文が 2 行 34 文字しか置けず、**一度に目に入る量が少なすぎる**。
 * 行を増やしても入る文字は増えない（[ROWS]）ので、増やせるのは見出しを畳んだぶんだけ。
 * 星座名は**1 枚目と読み上げの頭**で伝わっている。
 *
 * **送るのは 1 行ずつ**（[SCROLL_LINES]）。まとめて入れ替えると全部の行が
 * 新しくなり、**どこから読めばよいのか分からなくなる**。1 行ずつ上へ流せば、
 * 下の行がそのまま上へ来るので、読みかけの行を目で追い続けられる。
 */
object GlassTextPage {
    /** 左右の余白 */
    private const val MARGIN_X = 12

    /** 行をパネルの上下中央へ寄せる */
    private val marginY = (PANEL_HEIGHT - CANVAS_TEXT_SLOTS * CANVAS_LABEL_HEIGHT) / 2

    /** 見出しの行。**本文は見出しが空でも繰り上げない**（名前が付いた瞬間に全部書き直しになる） */
    private const val HEADER_ROW = 0

    /**
     * 1 枚に置ける行の数。**190 バイトに収まるのはここまで**で、見出しもこの中に数える。
     *
     * 行を増やしても**画面に入る文字数は増えない**。要素 1 つに座標と大きさで
     * 12 バイトかかるので、行を 1 本増やすたびに本文へ回せるバイトが減り、
     * 1 行の文字数がそのぶん短くなる（3 行で 17 文字、4 行なら 11 文字、5 行なら 8 文字）。
     * **日本語で 8 文字の行は語の途中で折れて読めない**ので、3 行で止める。
     */
    const val ROWS = 3

    /** 見出しと同じ枚に出せる本文の行数。**見出しが 1 行ぶん食う** */
    const val HEADER_BODY_ROWS = ROWS - 1

    /**
     * 見出しを畳んだ枚に出せる本文の行数。
     *
     * **見出しを出したままだと本文は 2 行しか置けない。** 2 行 34 文字では、
     * 読める前に流れていく割に一度に入る情報が少ない。名前は最初の 1 枚と読み上げで
     * 伝わっているので、**流れ始めたら見出しを畳んで本文に明け渡す**（[pages]）。
     */
    const val BODY_ROWS = ROWS

    /**
     * 画素で 1 行に入る文字数。
     *
     * **[LABEL_CHAR_WIDTH] は実測ではなく見積り。** 実機で分かっているのは
     * 「120 画素に 9 文字は入らなかった」（13.3px/文字では切れた）ことだけで、
     * 真の値は 13〜28px の間にある。**実機で数えたらここだけ直せばよく、画面の作りは変わらない。**
     */
    private val charsByWidth = (PANEL_WIDTH - 2 * MARGIN_X - LABEL_PADDING) / LABEL_CHAR_WIDTH

    /** 要素 1 つの固定ぶん（座標と大きさ）。文字を 1 つも置かない枠のバイト数 */
    private val elementBytes = CommandManager.CanvasElement(
        id = 0, x = 0, y = 0, width = 0, height = 0, text = "",
    ).byteSize()

    /** 日本語 1 文字の UTF-8 バイト数。解説文はほぼ全部これ */
    private const val JA_CHAR_BYTES = 3

    /**
     * **1 電文（190 バイト）に見出し 1 行＋本文 2 行が収まる**文字数。
     *
     * ここを画素だけで決めていたときは 1 行 19 文字になり、日本語で埋まった 3 行は
     * **207 バイトで 1 電文に収まらなかった**。分割して送ると、後から送ったぶんに
     * **先に置いた行が押し出されて消える**（190 バイトが画面の合計でもあるらしい・#40）。
     * つまり**解説が途中の行から始まったり、末尾が出なかったりする**。
     *
     * 画素と電文の**小さいほう**で決めれば、どちらの読み方でも 1 枚は必ず全部出る。
     */
    private val charsByBudget =
        (CANVAS_TEXT_BUDGET_BYTES / ROWS - elementBytes) / JA_CHAR_BYTES

    /** 1 行に置く文字数 */
    val lineChars: Int = minOf(charsByWidth, charsByBudget)

    /** 見出しを畳んだ 1 枚に出せる本文の文字数 */
    val bodyChars: Int = BODY_ROWS * lineChars

    /** 見出しと一緒に出す 1 枚に置ける本文の文字数 */
    val headerBodyChars: Int = HEADER_BODY_ROWS * lineChars


    /** 出せる行数の上限。これを超えるぶんは捨てる（解説文はこの中に収めてある） */
    private const val MAX_LINES = 16

    /** 解説文に許す長さ。めくって出せる総量 */
    val pagedChars: Int = MAX_LINES * lineChars

    /**
     * 1 回で送り出す行数。**1 行ずつ上へ流す**（＝スクロール）。
     *
     * まとめてめくっていたときは、**どの行も新しい**ので毎回読み直しになる。
     * 1 行ずつなら、下にあった行が上へ来て**読みかけの行がそのまま残る**ので、
     * 目の行き先が決まる（字幕と同じ理屈）。1 枚あたりの新しい文字は減るが、
     * 送る回数が増えるだけで**1 電文という制約は変わらない**。
     */
    private const val SCROLL_LINES = 1

    /**
     * 行頭に置かない文字。折り返し位置に来たら前の行へ吸わせる。
     *
     * 見た目のためだけではない。**吸わせる＝前の行が伸びる**方向にしか変わらないので、
     * あとから文字が届いても前の行の矩形が縮まない（縮むと消し残る）。
     */
    private const val NO_LINE_START = "。、，．,.」』）)]｝}！？!?・…ー〜:;：；"

    /** 禁則で伸ばせる上限。「。。。。」のような並びで 1 行が延々と伸びるのを止める */
    private const val KINSOKU_SLACK = 2

    /**
     * 1 枚ぶんの解説画面。
     *
     * [dropped] は入り切らず捨てた文字数、[revealed] は**この 1 枚で新しく出た文字数**。
     * [revealed] を出しているのは、**次の 1 枚まで何秒置くかをこれで決める**ため
     * （送る側が「1 行ぶんか 2 行ぶんか」を数え直さずに済む）。
     */
    class Page(
        val elements: List<CommandManager.CanvasElement>,
        val dropped: Int,
        val revealed: Int,
    )

    /**
     * 解説を**字幕のように流す**ための、1 枚ずつの画面。
     *
     * 1 枚は必ず 1 電文に収まるので、**置ける合計バイト数を気にしなくてよくなる**。
     * 2 枚目からは **1 行ずつ上へ流す**（[SCROLL_LINES]）ので、
     * 前の枚の下の行が次の枚の上の行になり、**読みかけの行が残ったまま次が出る**。
     */
    fun pages(header: String, body: String): List<Page> {
        val head = header.trim().take(lineChars)
        val all = wrap(body)
        if (all.isEmpty()) return listOf(explanation(head, ""))
        val lines = all.take(MAX_LINES)
        val dropped = all.drop(MAX_LINES).sumOf { it.length }

        val screens = ArrayList<Page>()
        // 1 枚目だけ見出しを出す。**名前はここと読み上げの頭で伝わる**
        if (head.isNotEmpty()) {
            val opening = lines.take(HEADER_BODY_ROWS)
            screens += Page(rows(listOf(head) + opening), dropped = 0, revealed = opening.sumOf { it.length })
        }
        // 見出しを畳んだら本文 3 行。windowed は窓に足りないと空を返すので、短い本文は 1 枚で出す
        if (head.isEmpty() || lines.size > HEADER_BODY_ROWS) {
            val windows =
                if (lines.size <= BODY_ROWS) listOf(lines) else lines.windowed(BODY_ROWS, SCROLL_LINES)
            for ((index, window) in windows.withIndex()) {
                // 見出しの枚から続くときは、下へ入ってきた行だけが新しい
                val revealed = if (index == 0 && screens.isEmpty()) {
                    window.sumOf { it.length }
                } else {
                    window.takeLast(SCROLL_LINES).sumOf { it.length }
                }
                screens += Page(rows(window), dropped = 0, revealed = revealed)
            }
        }
        // 捨てたぶんは最後の 1 枚に付けて数える。ログで「切れた」と分かればよい
        val last = screens.last()
        screens[screens.lastIndex] = Page(last.elements, dropped, last.revealed)
        return screens
    }

    /** 上から順に行を置く。空の行は置かない（枠だけが残ると前の文字が消えない） */
    private fun rows(texts: List<String>): List<CommandManager.CanvasElement> =
        texts.take(ROWS).mapIndexedNotNull { index, text ->
            if (text.isBlank()) null else row(index, text)
        }

    /**
     * 1 枚ぶんの画面を組む。
     *
     * **同じ行は必ず同じ場所に置き、行を組み直さない。** ファームは新しい矩形しか
     * 描き直さないので、組み直すと**前の行の末尾が画面に残る**（実機で踏んだ「る」問題）。
     */
    fun explanation(header: String, body: String): Page {
        val elements = ArrayList<CommandManager.CanvasElement>(ROWS)
        header.trim().takeIf { it.isNotEmpty() }?.let {
            elements += row(HEADER_ROW, it.take(lineChars))
        }
        val lines = wrap(body)
        for ((index, line) in lines.take(HEADER_BODY_ROWS).withIndex()) {
            if (line.isBlank()) continue
            elements += row(HEADER_ROW + 1 + index, line)
        }
        return Page(
            elements = elements,
            dropped = lines.drop(HEADER_BODY_ROWS).sumOf { it.length },
            revealed = lines.take(HEADER_BODY_ROWS).sumOf { it.length },
        )
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
