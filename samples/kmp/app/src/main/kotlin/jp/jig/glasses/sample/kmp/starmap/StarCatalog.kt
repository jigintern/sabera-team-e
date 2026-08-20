package jp.jig.glasses.sample.kmp.starmap

import android.content.Context
import org.json.JSONObject

/**
 * 同梱の星表を読む。夜空の下は電波が悪いので外部 API は使わない。
 * 実体は data/ にあり、build.gradle.kts で assets に足している。
 */

/** 星表の 1 行。座標は J2000.0 なので、使う前に歳差をかける */
class Star(val hip: Int, val raDeg: Double, val decDeg: Double, val magnitude: Double)

/** 星座線は折れ線の集まり。1 頂点は [赤経, 赤緯] */
class Constellation(val abbr: String, val nameJa: String, val lines: List<List<DoubleArray>>)

class StarCatalog(
    val stars: List<Star>,
    val constellations: List<Constellation>,
    val brightNames: Map<Int, String>,
) {
    companion object {
        fun load(context: Context): StarCatalog {
            val stars = ArrayList<Star>(2000)
            JSONObject(context.readAsset("stars.json")).getJSONArray("stars").let { arr ->
                for (i in 0 until arr.length()) {
                    val s = arr.getJSONArray(i)
                    stars += Star(s.getInt(0), s.getDouble(1), s.getDouble(2), s.getDouble(3))
                }
            }

            val constellations = ArrayList<Constellation>(88)
            JSONObject(context.readAsset("constellations.json")).getJSONArray("constellations").let { arr ->
                for (i in 0 until arr.length()) {
                    val c = arr.getJSONObject(i)
                    val lines = c.getJSONArray("lines")
                    val polylines = ArrayList<List<DoubleArray>>(lines.length())
                    for (j in 0 until lines.length()) {
                        val seg = lines.getJSONArray(j)
                        val pts = ArrayList<DoubleArray>(seg.length())
                        for (k in 0 until seg.length()) {
                            val p = seg.getJSONArray(k)
                            pts += doubleArrayOf(p.getDouble(0), p.getDouble(1))
                        }
                        polylines += pts
                    }
                    constellations += Constellation(c.getString("abbr"), c.getString("nameJa"), polylines)
                }
            }

            val names = HashMap<Int, String>()
            JSONObject(context.readAsset("bright-stars.json")).getJSONArray("stars").let { arr ->
                for (i in 0 until arr.length()) {
                    val s = arr.getJSONObject(i)
                    names[s.getInt("hip")] = s.getString("nameJa")
                }
            }

            return StarCatalog(stars, constellations, names)
        }

        private fun Context.readAsset(name: String): String =
            assets.open(name).use { it.readBytes().toString(Charsets.UTF_8) }
    }
}
