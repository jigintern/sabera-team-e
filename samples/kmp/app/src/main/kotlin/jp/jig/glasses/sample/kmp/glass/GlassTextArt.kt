package jp.jig.glasses.sample.kmp.glass

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.Typeface

/**
 * 文字を**画像に焼く**。
 *
 * **キャンバスのテキスト枠では字の大きさを変えられない。** 要素にあるのは座標と大きさと
 * 文字だけで、フォントの指定が無い。見出しを大きく、本文を小さく、中央に揃える——
 * といった組み方をしたければ、**自分で描いて画像として送る**しかない。
 * 方位の文字（北東南西）を線で描いているのと同じ理由だが、日本語は線では描けないので
 * Android の `Canvas` に任せる。
 *
 * **動かないものにだけ使う。** 画像は 1 枚 332〜390ms かかり、転送中は前の絵が消える。
 * 起動直後の挨拶のように**一度出して置いておくもの**なら気にならないが、
 * 回るもの（読み込み中のクルクル）はテキスト枠でやる。
 *
 * 出てくるのは 1 画素 1 バイトのグレースケール（[StarMap]）。**黒は透明**なので、
 * 透過ロゴと白い本文を黒地に描く。量子化（3bit）と圧縮は SDK がやる。
 */
object GlassTextArt {

    /** 採用ロゴと本文を中央に組んだ 1 枚。本文は入る大きさまで自動で落とす。 */
    fun splash(
        logo: Bitmap,
        body: String,
        width: Int = STAR_MAP_WIDTH,
        height: Int = STAR_MAP_HEIGHT,
    ): StarMap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        // **黒は透明。** 塗りつぶしておくと、前に出ていた絵が透けない
        canvas.drawColor(Color.BLACK)

        val usable = width - 2 * MARGIN_X
        val logoWidth = minOf(LOGO_WIDTH, usable.toFloat())
        val logoHeight = logo.height * logoWidth / logo.width
        val logoLeft = (width - logoWidth) / 2f
        val logoBottom = LOGO_TOP + logoHeight
        // スマホ用のミントは赤成分が 117 しかなく、そのままグレースケールへ落とすと
        // 白いロゴ文字（244）より暗くなる。グラスでは色を使えないので、透過だけ残して白へ揃える
        val logoPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
        }
        canvas.drawBitmap(
            logo,
            null,
            RectF(logoLeft, LOGO_TOP, logoLeft + logoWidth, logoBottom),
            logoPaint,
        )

        // ロゴの下から、下の余白まで。採用ロゴは横長なので本文の場所を圧迫しない
        val bodyTop = logoBottom + LOGO_GAP
        val room = height - MARGIN_Y - bodyTop
        val bodyPaint = paint(BODY_SIZES.first())
        var lines = wrap(body, bodyPaint, usable.toFloat())
        for (size in BODY_SIZES) {
            bodyPaint.textSize = size
            lines = wrap(body, bodyPaint, usable.toFloat())
            if (lines.size * size * LINE_SPACING <= room) break
        }

        var baseline = bodyTop + bodyPaint.textSize
        for (line in lines) {
            if (baseline > height - MARGIN_Y) break
            canvas.drawText(line, width / 2f, baseline, bodyPaint)
            baseline += bodyPaint.textSize * LINE_SPACING
        }

        return StarMap(width, height, toGray(bitmap), emptyList())
    }

    /**
     * 1 行を**枠いっぱいの大きさ**で焼く（タイムラプスの年号・#45）。
     *
     * **テキスト枠では字の大きさを変えられない**ので、大きく出したいものはここを通す。
     * [splash] と違って**毎フレーム作り直す**ため、折り返しも行送りもしない。
     * 入らなければ入る大きさまで落とす（「紀元前 10000 年」がいちばん長い）。
     */
    fun bigLine(text: String, width: Int, height: Int): StarMap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        // **黒は透明。** 塗りつぶしておくと、前に出ていた絵が透けない
        canvas.drawColor(Color.BLACK)

        val usable = width - 2 * BIG_LINE_MARGIN
        val linePaint = paint(height * BIG_LINE_HEIGHT_RATIO)
        while (linePaint.textSize > BIG_LINE_MIN_SIZE &&
            linePaint.measureText(text) > usable
        ) {
            linePaint.textSize -= 2f
        }
        // 中央へ。**上下も真ん中**にしたいので、字の高さの半分だけベースラインを下げる
        val metrics = linePaint.fontMetrics
        val baseline = height / 2f - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText(text, width / 2f, baseline, linePaint)

        return StarMap(width, height, toGray(bitmap), emptyList())
    }

    /** 枠の高さに対する字の大きさ。上下に余白が要るので目いっぱいにはしない */
    private const val BIG_LINE_HEIGHT_RATIO = 0.62f
    private const val BIG_LINE_MARGIN = 12
    private const val BIG_LINE_MIN_SIZE = 16f

    private fun paint(size: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = size
        textAlign = Paint.Align.CENTER
        typeface = Typeface.SANS_SERIF
    }

    /**
     * 幅で折り返す。**キリのいいところで切る。**
     *
     * ただ幅で切ると**文の途中で改行が入って読みにくい**（実機で見て分かった）。
     * 行が埋まったら、**句読点まで戻れるなら戻って切る**。戻りすぎると行がすかすかになるので、
     * 行の [MIN_BREAK_RATIO] より後ろにある句読点だけを使う。
     *
     * 行頭に置けない字（。、」など）は、**はみ出しても前の行へ吸わせる**
     * （[NO_LINE_START]。GlassTextPage と同じ考え方）。
     */
    private fun wrap(text: String, paint: Paint, maxWidth: Float): List<String> {
        val lines = ArrayList<String>()
        var current = StringBuilder()
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (ch == '\n') {
                if (current.isNotEmpty()) lines += current.toString()
                current = StringBuilder()
                i++
                continue
            }
            if (current.isEmpty() || paint.measureText(current.toString() + ch) <= maxWidth) {
                current.append(ch)
                i++
                continue
            }

            // 行が埋まった。**まず句読点まで戻れるか見る**
            val breakAt = current.lastIndexOfAny(BREAK_AFTER)
            if (breakAt >= 0 && breakAt + 1 >= current.length * MIN_BREAK_RATIO) {
                lines += current.substring(0, breakAt + 1)
                // 戻したぶんは次の行へ持ち越す。**ここで ch は消費しない**
                current = StringBuilder(current.substring(breakAt + 1))
                continue
            }
            // 行頭に置けない字は、少しはみ出してでも前の行へ吸わせる
            if (ch in NO_LINE_START &&
                paint.measureText(current.toString() + ch) <= maxWidth * KINSOKU_SLACK
            ) {
                current.append(ch)
                i++
                continue
            }
            lines += current.toString()
            current = StringBuilder()
        }
        if (current.isNotEmpty()) lines += current.toString()
        return lines
    }

    /**
     * 1 画素 1 バイトのグレースケールへ落とす。
     *
     * 黒地に白で描いてあるので、**赤の成分だけ見れば足りる**（3 色とも同じ値）。
     */
    private fun toGray(bitmap: Bitmap): ByteArray {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val gray = ByteArray(pixels.size)
        for (i in pixels.indices) gray[i] = ((pixels[i] shr 16) and 0xFF).toByte()
        return gray
    }

    /** 左右の余白 */
    private const val MARGIN_X = 24

    /** 上下の余白 */
    private const val MARGIN_Y = 20

    /** 横長ロゴ。288px なら高さは約81pxで、長いひとことも24pxを保てる */
    private const val LOGO_WIDTH = 288f

    private const val LOGO_TOP = 5f

    /** ロゴと本文のあいだ[画素] */
    private const val LOGO_GAP = 10f

    /**
     * 本文の字の大きさ[画素]。**入る大きさが見つかるまで上から順に試す。**
     *
     * ひとことの長さはメモによって倍近く変わる（60〜120 文字）。この並びなら
     * **短いものは 30px で 4 行、長いものでも 24px で 7 行**に収まる。
     * いちばん小さい 20px は最後の逃げ道で、そこまで落ちるなら**ひとことを短くするほうがよい**。
     */
    private val BODY_SIZES = floatArrayOf(30f, 27f, 24f, 22f, 20f)

    /**
     * 行送り（字の大きさに対する倍率）。
     *
     * **1.35 では本文が 20px まで落ちた。** 句読点で切るようにしたぶん行数が増えたので、
     * 行送りと見出しの場所を詰めて **24px を保てるように**した
     * （いちばん長いひとことが 113 文字・7 行）。
     */
    private const val LINE_SPACING = 1.25f

    /** ここまで来ていれば、句読点まで戻って切ってよい（行の長さに対する割合） */
    private const val MIN_BREAK_RATIO = 0.55f

    /** 禁則ではみ出してよい幅（行幅に対する倍率）。「。。。」で延々伸びるのを止める */
    private const val KINSOKU_SLACK = 1.08f

    /** ここで切ると読みやすい。**句読点のうしろ** */
    private val BREAK_AFTER = charArrayOf('。', '、')

    /** 行頭に置かない字。折り返しに来たら前の行へ吸わせる */
    private const val NO_LINE_START = "。、，．,.」』）)]｝}！？!?・…ー〜:;：；"
}
