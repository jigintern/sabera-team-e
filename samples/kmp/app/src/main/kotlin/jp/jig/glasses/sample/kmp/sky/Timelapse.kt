package jp.jig.glasses.sample.kmp.sky

import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs

/**
 * 時代を送る途中の空を作る。**Android に触らないので JVM テストで固定できる。**
 *
 * **年だけを補間し、月日と時刻は目的地のものに固定する。** 経過ミリ秒をそのまま等分すると、
 * 途中の枚は 1 日のどこかの時刻になり、**日周回転（1 日 1 回転）が歳差を完全に飲み込む**。
 * 1 万年をそのまま送れば 365 万回転で、流れではなく雑音になる。
 *
 * 年だけを送ると残るのは歳差だけで、1 万年ぶんで
 * **空全体の回り込みが約 140°・天の北極の移動が約 44°**。これが「遡っている感」の実体。
 */
data class TimelapseFrame(
    val epochMillis: Long,
    /** 表示に出す年。**天文の年番号のまま**なので、読ませるときは [SkyCommandParser.yearLabel] を通す */
    val year: Int,
)

object Timelapse {

    /**
     * 約 3.2 秒ぶん（45ms × 72）。
     *
     * **出すのは年号だけ**なので、枚数はそのまま「数字がめくれる速さ」になる。
     * 星図を出していたときは「1 枚で空がどれだけ回るか」で頭打ちになっていたが、
     * その縛りが無くなったぶん増やしてある。
     */
    const val FRAMES = 72

    /** これ未満しか動かないなら流さずに飛ぶ。**数年ぶんでは何も見えないのに待たせるだけ** */
    const val MIN_YEARS = 5

    /**
     * [fromEpochMillis] から [toEpochMillis] へ送る途中の空。**最後の 1 枚は必ず目的地ちょうど。**
     *
     * 着地がずれると、そのあとに出す星図と食い違って「演出と本番で空が違う」ことになる。
     * 流すほどでもないときは空を返すので、呼ぶ側はそのまま飛ばせばよい。
     */
    fun frames(
        fromEpochMillis: Long,
        toEpochMillis: Long,
        zoneId: ZoneId,
        count: Int = FRAMES,
    ): List<TimelapseFrame> {
        if (count <= 0) return emptyList()
        val target = Instant.ofEpochMilli(toEpochMillis).atZone(zoneId).toLocalDateTime()
        val startYear = Instant.ofEpochMilli(fromEpochMillis).atZone(zoneId).year
        val endYear = target.year
        if (abs(endYear.toLong() - startYear.toLong()) < MIN_YEARS) return emptyList()

        return (1..count).map { step ->
            if (step == count) {
                TimelapseFrame(toEpochMillis, endYear)
            } else {
                val year = startYear + Math.round((endYear.toLong() - startYear) * ease(step, count))
                // うるう日は withYear が 2/28 へ寄せる。**途中の枚の精度は要らない**
                val local = target.withYear(year.toInt())
                TimelapseFrame(local.atZone(zoneId).toInstant().toEpochMilli(), year.toInt())
            }
        }
    }

    /**
     * 出だしと着地を緩める（smoothstep）。
     *
     * 等速だと**始まった瞬間が最高速**で、動き出しが飛んだように見える。
     * 真ん中を速くしたほうが「ブワー」と流れて止まった感じになる。
     */
    internal fun ease(step: Int, count: Int): Double {
        val t = step.toDouble() / count
        return t * t * (3.0 - 2.0 * t)
    }
}
