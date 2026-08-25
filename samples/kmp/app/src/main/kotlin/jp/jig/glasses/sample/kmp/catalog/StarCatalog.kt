package jp.jig.glasses.sample.kmp.catalog

import android.content.Context
import jp.jig.glasses.sample.kmp.sky.ConstellationBoundary
import jp.jig.glasses.sample.kmp.sky.ConstellationBoundaryCatalog
import org.json.JSONObject

/**
 * 同梱の星表を読む。夜空の下は電波が悪いので外部 API は使わない。
 * 実体は data/ にあり、build.gradle.kts で assets に足している。
 */

/**
 * 星表の 1 行。座標は J2000.0 なので、使う前に歳差をかける。
 *
 * **固有運動を持たせているのは深い時代のため**（#45）。1 万年遡ると
 * アークトゥルスは 6.4° 動き、**星座の形そのものが変わる**。
 * 数世紀の範囲では 1 分角にも満たないので、ふだんは効かない。
 */
class Star(
    val hip: Int,
    val raDeg: Double,
    val decDeg: Double,
    val magnitude: Double,
    /** μα·cos δ [mas/年]。**すでに cos δ が掛かっている** */
    val pmRaMasPerYear: Double = 0.0,
    val pmDecMasPerYear: Double = 0.0,
)

/**
 * 星座線は折れ線の集まり。1 頂点は **[赤経, 赤緯, μα·cos δ, μδ]**。
 *
 * **頂点にも固有運動を持たせる。** 頂点は星の位置そのもの（893 個中 891 個に
 * 固有運動が付いた）なので、これが無いと**星だけ動いて線が置き去りになる**（#45）。
 */
class Constellation(val abbr: String, val nameJa: String, val lines: List<List<DoubleArray>>)

/**
 * 星座絵。**正規化 [0,1]（x=右・y=下）の折れ線**で、星座線の外接矩形へ写して薄く重ねる。
 *
 * 星の位置に厳密に貼るのではなく「なにに見立てたのか」を伝える絵なので、
 * 外接矩形に写すだけでよい。向きは南を向いた星図と同じ（`tools/build-constellation-figures.py`）。
 */
typealias ConstellationFigure = List<List<DoubleArray>>

/**
 * 大三角のような星の結び。**初心者が空で最初に見つけるのはこれ。**
 *
 * 星座線は 88 星座ぶんあってどれがどれか分からないが、**3 点の結びは覚えられる**。
 * 星は HIP 番号で指すので、位置は星表と同じ（歳差込み）。
 */
class Asterism(val nameJa: String, val hips: List<Int>, val closed: Boolean)

class StarCatalog(
    val stars: List<Star>,
    val constellations: List<Constellation>,
    val brightNames: Map<Int, String>,
    val boundaries: ConstellationBoundaryCatalog? = null,
    /** 略号 → 星座絵。持っていない星座は線だけになる */
    val figures: Map<String, ConstellationFigure> = emptyMap(),
    /** 大三角などの結び */
    val asterisms: List<Asterism> = emptyList(),
    /** 天の川の帯の縁（J2000 の赤経・赤緯）。歳差 0.36° は帯の幅 20° に対して無視できる */
    val milkyWay: List<List<DoubleArray>> = emptyList(),
) {
    companion object {
        fun load(context: Context): StarCatalog = parse { context.readAsset(it) }

        /**
         * 読み口を差し替えられる本体。
         *
         * **Android から切り離してあるのは、JVM でも同じ星表を読めるようにするため。**
         * 実機に入れる assets と `data/` の JSON は同じものなので、
         * ドキュメント用の見本画像（`DocumentImagesTest`）も同じ経路で描ける。
         */
        fun parse(readAsset: (String) -> String): StarCatalog {
            val stars = ArrayList<Star>(2000)
            JSONObject(readAsset("stars.json")).getJSONArray("stars").let { arr ->
                for (i in 0 until arr.length()) {
                    val s = arr.getJSONArray(i)
                    // 固有運動の列は #45 で足した。**古い星表でも読めるようにしておく**
                    stars += Star(
                        hip = s.getInt(0),
                        raDeg = s.getDouble(1),
                        decDeg = s.getDouble(2),
                        magnitude = s.getDouble(3),
                        pmRaMasPerYear = if (s.length() > 4) s.getDouble(4) else 0.0,
                        pmDecMasPerYear = if (s.length() > 5) s.getDouble(5) else 0.0,
                    )
                }
            }

            val constellations = ArrayList<Constellation>(88)
            JSONObject(readAsset("constellations.json")).getJSONArray("constellations").let { arr ->
                for (i in 0 until arr.length()) {
                    val c = arr.getJSONObject(i)
                    val lines = c.getJSONArray("lines")
                    val polylines = ArrayList<List<DoubleArray>>(lines.length())
                    for (j in 0 until lines.length()) {
                        val seg = lines.getJSONArray(j)
                        val pts = ArrayList<DoubleArray>(seg.length())
                        for (k in 0 until seg.length()) {
                            val p = seg.getJSONArray(k)
                            pts += doubleArrayOf(
                                p.getDouble(0),
                                p.getDouble(1),
                                if (p.length() > 2) p.getDouble(2) else 0.0,
                                if (p.length() > 3) p.getDouble(3) else 0.0,
                            )
                        }
                        polylines += pts
                    }
                    constellations += Constellation(c.getString("abbr"), c.getString("nameJa"), polylines)
                }
            }

            val names = HashMap<Int, String>()
            JSONObject(readAsset("bright-stars.json")).getJSONArray("stars").let { arr ->
                for (i in 0 until arr.length()) {
                    val s = arr.getJSONObject(i)
                    names[s.getInt("hip")] = s.getString("nameJa")
                }
            }

            val boundaryJson = JSONObject(readAsset("constellation-boundaries.json"))
            val boundaryRows = ArrayList<ConstellationBoundary>(boundaryJson.getInt("count"))
            boundaryJson.getJSONArray("boundaries").let { arr ->
                for (i in 0 until arr.length()) {
                    val row = arr.getJSONArray(i)
                    boundaryRows += ConstellationBoundary(
                        raLowHours = row.getDouble(0),
                        raUpHours = row.getDouble(1),
                        decLowDeg = row.getDouble(2),
                        abbreviation = row.getString(3),
                    )
                }
            }
            val boundaryNames = HashMap<String, String>()
            boundaryJson.getJSONObject("namesJa").let { obj ->
                for (key in obj.keys()) boundaryNames[key] = obj.getString(key)
            }

            // 星座絵は無くても動く。**追加し忘れても星図は出る**ようにしておく
            val figures = HashMap<String, ConstellationFigure>()
            runCatching {
                JSONObject(readAsset("constellation-figures.json"))
                    .getJSONObject("figures")
            }.getOrNull()?.let { obj ->
                for (abbr in obj.keys()) {
                    val strokes = obj.getJSONArray(abbr)
                    val figure = ArrayList<List<DoubleArray>>(strokes.length())
                    for (i in 0 until strokes.length()) {
                        val stroke = strokes.getJSONArray(i)
                        val points = ArrayList<DoubleArray>(stroke.length())
                        for (j in 0 until stroke.length()) {
                            val point = stroke.getJSONArray(j)
                            points += doubleArrayOf(point.getDouble(0), point.getDouble(1))
                        }
                        figure += points
                    }
                    figures[abbr] = figure
                }
            }

            // 結びと天の川も無くても動く（データを足し忘れても星図は出る）
            val asterisms = ArrayList<Asterism>()
            val milkyWay = ArrayList<List<DoubleArray>>()
            runCatching { JSONObject(readAsset("asterisms.json")) }.getOrNull()?.let { json ->
                json.optJSONArray("asterisms")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val a = arr.getJSONObject(i)
                        val hipsArray = a.getJSONArray("hips")
                        asterisms += Asterism(
                            nameJa = a.getString("nameJa"),
                            hips = (0 until hipsArray.length()).map { hipsArray.getInt(it) },
                            closed = a.getBoolean("closed"),
                        )
                    }
                }
                json.optJSONArray("milkyWay")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val edge = arr.getJSONArray(i)
                        val points = ArrayList<DoubleArray>(edge.length())
                        for (j in 0 until edge.length()) {
                            val p = edge.getJSONArray(j)
                            points += doubleArrayOf(p.getDouble(0), p.getDouble(1))
                        }
                        milkyWay += points
                    }
                }
            }

            return StarCatalog(
                stars,
                constellations,
                names,
                ConstellationBoundaryCatalog(boundaryRows, boundaryNames),
                figures,
                asterisms,
                milkyWay,
            )
        }

        private fun Context.readAsset(name: String): String =
            assets.open(name).use { it.readBytes().toString(Charsets.UTF_8) }
    }
}
