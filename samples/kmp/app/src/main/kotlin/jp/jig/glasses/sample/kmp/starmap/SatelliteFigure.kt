package jp.jig.glasses.sample.kmp.starmap

/**
 * 衛星の輪郭。**実物大ではないと割り切ったアイコン**。
 *
 * ISS は 400km 先の全長 110m ＝ **視角 0.016°** で、528px / 35° では **0.24 画素**しかない。
 * ひまわりは 36,000km なのでさらに桁が違う。**肉眼でも点**なので、
 * 見える大きさに描いた輪郭は必ず 100 倍以上の嘘になる。
 *
 * だから輪郭は「いまの位置」には重ねない。位置は小さな印のまま置き、
 * 空いている場所へ輪郭を出して**引き出し線でつなぐ**（[StarMapRenderer] が置き場所を決める）。
 * こうすれば「どこを見るか」と「何が飛んでいるか」が両立する。
 *
 * 形は 3 種類しか持たない。緑 8 階調・96 画素では、それ以上描き分けても読めない。
 */
enum class SatelliteFigure {
    /** 有人。長いトラスに 4 枚のパネル（ISS・天宮） */
    STATION,

    /** 気象。箱＋片翼＋パラボラ（ひまわり） */
    DISH,

    /** それ以外。箱＋両翼＋アンテナ（みちびき・だいち・いぶき・しきさい・しずく） */
    WINGED,

    ;

    /**
     * 0..1 の正規化座標で表した輪郭。
     * `strong` は外形（明るく）、それ以外はパネルの桟（暗く）。
     */
    val strokes: List<FigureStroke>
        get() = when (this) {
            STATION -> station()
            DISH -> dish()
            WINGED -> winged()
        }

    companion object {
        /**
         * 名前から形を決める。**TLE には日本語名しか入っていない**ので、
         * 種別の対応表（`tools/satellites-ja.json`）ではなく名前で分ける。
         */
        fun of(name: String): SatelliteFigure = when {
            name.startsWith("ISS") || name.startsWith("天宮") -> STATION
            name.startsWith("ひまわり") -> DISH
            else -> WINGED
        }

        private fun rect(x0: Double, y0: Double, x1: Double, y1: Double, strong: Boolean) = FigureStroke(
            points = listOf(
                doubleArrayOf(x0, y0), doubleArrayOf(x1, y0),
                doubleArrayOf(x1, y1), doubleArrayOf(x0, y1),
            ),
            strong = strong,
            closed = true,
        )

        private fun segment(x0: Double, y0: Double, x1: Double, y1: Double, strong: Boolean) = FigureStroke(
            points = listOf(doubleArrayOf(x0, y0), doubleArrayOf(x1, y1)),
            strong = strong,
            closed = false,
        )

        /** パネル 1 枚。桟を 2 本入れると、ただの四角ではなく太陽電池に見える */
        private fun panel(x0: Double, y0: Double, x1: Double, y1: Double): List<FigureStroke> {
            val strokes = ArrayList<FigureStroke>()
            strokes += rect(x0, y0, x1, y1, strong = true)
            for (i in 1..2) {
                val x = x0 + (x1 - x0) * i / 3.0
                strokes += segment(x, y0, x, y1, strong = false)
            }
            return strokes
        }

        private fun station(): List<FigureStroke> {
            val strokes = ArrayList<FigureStroke>()
            // 中央のトラス。**端から端まで 1 本通す。** 途切れるとパネル 4 枚が
            // ばらばらの四角に見えて、横に長い ISS だと分からない
            strokes += segment(0.02, 0.50, 0.98, 0.50, strong = true)
            // 加圧モジュール
            strokes += rect(0.41, 0.41, 0.59, 0.59, strong = true)
            strokes += panel(0.02, 0.16, 0.30, 0.42)
            strokes += panel(0.02, 0.58, 0.30, 0.84)
            strokes += panel(0.70, 0.16, 0.98, 0.42)
            strokes += panel(0.70, 0.58, 0.98, 0.84)
            return strokes
        }

        private fun dish(): List<FigureStroke> {
            val strokes = ArrayList<FigureStroke>()
            strokes += rect(0.40, 0.26, 0.60, 0.76, strong = true)
            strokes += panel(0.64, 0.34, 0.99, 0.68)
            // 地球を向くパラボラ。左半分の弧を折れ線で描く。
            // **本体から離して腕でつなぐ。** くっつけると 96 画素では 1 つの塊になる
            val arc = ArrayList<DoubleArray>()
            for (i in 0..8) {
                val t = Math.PI * (0.5 + i / 8.0)
                arc += doubleArrayOf(0.30 + 0.13 * Math.cos(t), 0.51 + 0.19 * Math.sin(t))
            }
            strokes += FigureStroke(arc, strong = true, closed = false)
            strokes += segment(0.30, 0.51, 0.40, 0.51, strong = true)
            return strokes
        }

        private fun winged(): List<FigureStroke> {
            val strokes = ArrayList<FigureStroke>()
            strokes += rect(0.39, 0.33, 0.61, 0.67, strong = true)
            strokes += panel(0.04, 0.41, 0.37, 0.59)
            strokes += panel(0.63, 0.41, 0.96, 0.59)
            // 下向きのアンテナ。上下が分かると「衛星が向いている先」が想像できる
            strokes += segment(0.50, 0.67, 0.50, 0.86, strong = true)
            strokes += segment(0.42, 0.86, 0.58, 0.86, strong = true)
            return strokes
        }
    }
}

/** 輪郭の 1 本。`closed` なら最後の点と最初の点も結ぶ */
class FigureStroke(
    /** `[x, y]` の並び。どちらも 0..1 */
    val points: List<DoubleArray>,
    /** 外形か（明るく描く）。パネルの桟は false */
    val strong: Boolean,
    val closed: Boolean,
)
