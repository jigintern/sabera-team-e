package jp.jig.glasses.sample.kmp.satellite

import jp.jig.glasses.sample.kmp.support.DAY_MILLIS
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor

/**
 * TLE（2 行軌道要素）1 件。
 *
 * **中の値は SGP4 が使う単位に直してある**（角度はラジアン、平均運動は rad/分）。
 * 生の TLE のまま持つと、使う側が単位を間違える。
 *
 * 変換の仕方は Vallado の参照実装（`twoline2rv`）に合わせてある。
 */
class Tle(
    val name: String,
    val noradId: Int,
    /** 元期のユリウス日（整数部と小数部を分けて持つ。足すと桁が落ちる） */
    val jdEpoch: Double,
    val jdEpochFrac: Double,
    /** 軌道傾斜角[rad] */
    val inclo: Double,
    /** 昇交点赤経[rad] */
    val nodeo: Double,
    /** 離心率 */
    val ecco: Double,
    /** 近地点引数[rad] */
    val argpo: Double,
    /** 平均近点角[rad] */
    val mo: Double,
    /** 平均運動[rad/分]。ケプラー平均運動ではなく Kozai の平均運動 */
    val noKozai: Double,
    /** 抗力項 */
    val bstar: Double,
    /** 平均運動の 1 階微分[rad/分^2] */
    val ndot: Double,
    /** 平均運動の 2 階微分[rad/分^3] */
    val nddot: Double,
) {
    /** 1950.0 元期からの日数。**深宇宙の日月項がこの基準を取る**ので、J2000 に直さない */
    val epochDaysSince1950: Double get() = (jdEpoch - 2433281.5) + jdEpochFrac

    /** 元期の UNIX ミリ秒。**軌道要素の古さはこれで測る**（取得日ではない） */
    val epochUnixMillis: Long get() = ((jdEpoch - 2440587.5 + jdEpochFrac) * DAY_MILLIS).toLong()

    /** 元期から `epochMillis` までの経過分。SGP4 に渡す時刻はこれ */
    fun minutesSinceEpoch(epochMillis: Long): Double {
        val jd = 2440587.5 + epochMillis / DAY_MILLIS.toDouble()
        return ((jd - jdEpoch) - jdEpochFrac) * 1440.0
    }

    companion object {
        private const val DEG2RAD = PI / 180.0

        /** 1 日の分数を 2π で割ったもの。rev/day を rad/min に直すのに使う */
        private const val XPDOTP = 1440.0 / (2.0 * PI)

        /**
         * 3 行（名前・1 行目・2 行目）から作る。名前が無い 2 行だけの形式にも対応する。
         *
         * 壊れた行は例外ではなく null を返す。**10,748 行の中に 1 行おかしいものが
         * あっても全部が読めなくなると困る**ため。
         */
        fun parse(name: String, line1: String, line2: String): Tle? {
            if (line1.length < 64 || line2.length < 63) return null
            if (line1[0] != '1' || line2[0] != '2') return null
            return runCatching {
                val noradId = line1.substring(2, 7).trim().toInt()
                val epochYear = line1.substring(18, 20).trim().toInt()
                val epochDays = line1.substring(20, 32).trim().toDouble()
                val ndot = line1.substring(33, 43).trim().toDouble()
                val nddot = decimalPoint(line1.substring(44, 52))
                val bstar = decimalPoint(line1.substring(53, 61))

                val year = if (epochYear < 57) epochYear + 2000 else epochYear + 1900
                val (jd, jdFrac) = julianDate(year, epochDays)

                Tle(
                    name = name.trim(),
                    noradId = noradId,
                    jdEpoch = jd,
                    jdEpochFrac = jdFrac,
                    inclo = line2.substring(8, 16).trim().toDouble() * DEG2RAD,
                    nodeo = line2.substring(17, 25).trim().toDouble() * DEG2RAD,
                    ecco = ("0." + line2.substring(26, 33).trim()).toDouble(),
                    argpo = line2.substring(34, 42).trim().toDouble() * DEG2RAD,
                    mo = line2.substring(43, 51).trim().toDouble() * DEG2RAD,
                    noKozai = line2.substring(52, 63).trim().toDouble() / XPDOTP,
                    bstar = bstar,
                    ndot = ndot / (XPDOTP * 1440.0),
                    nddot = nddot / (XPDOTP * 1440.0 * 1440.0),
                )
            }.getOrNull()
        }

        /** 名前つき 3 行が続くテキストを丸ごと読む。`#` で始まる行は読み飛ばす */
        fun parseAll(text: String): List<Tle> {
            val lines = text.lineSequence()
                .map { it.trimEnd() }
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .toList()
            val result = ArrayList<Tle>()
            var i = 0
            while (i < lines.size) {
                val line = lines[i]
                when {
                    line.startsWith("1 ") && i + 1 < lines.size && lines[i + 1].startsWith("2 ") -> {
                        parse("", line, lines[i + 1])?.let { result += it }
                        i += 2
                    }
                    i + 2 < lines.size && lines[i + 1].startsWith("1 ") && lines[i + 2].startsWith("2 ") -> {
                        parse(line, lines[i + 1], lines[i + 2])?.let { result += it }
                        i += 3
                    }
                    else -> i += 1
                }
            }
            return result
        }

        /**
         * TLE の「小数点が省略された指数表記」を読む。
         * `12808-3` は 0.12808e-3、` 00000-0` は 0。**先頭の符号は仮数に付く**
         */
        private fun decimalPoint(field: String): Double {
            val s = field.trim()
            if (s.isEmpty()) return 0.0
            val sign = if (s.startsWith("-")) -1.0 else 1.0
            val body = s.removePrefix("-").removePrefix("+")
            val expIndex = body.indexOfLast { it == '-' || it == '+' }
            if (expIndex <= 0) return sign * ("0." + body).toDouble()
            val mantissa = ("0." + body.substring(0, expIndex)).toDouble()
            val exponent = body.substring(expIndex).toInt()
            return sign * mantissa * Math.pow(10.0, exponent.toDouble())
        }

        /** 年と「その年の通日（小数つき）」からユリウス日を出す。参照実装の `days2mdhms` ＋ `jday` */
        private fun julianDate(year: Int, dayOfYear: Double): Pair<Double, Double> {
            val leap = if (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)) 1 else 0
            val monthDays = intArrayOf(31, 28 + leap, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
            val dayInt = floor(dayOfYear).toInt()
            var month = 1
            var remaining = dayInt
            while (month <= 12 && remaining > monthDays[month - 1]) {
                remaining -= monthDays[month - 1]
                month++
            }
            val day = remaining
            val fraction = dayOfYear - dayInt
            val hours = fraction * 24.0
            val hr = floor(hours).toInt()
            val minutes = (hours - hr) * 60.0
            val minute = floor(minutes).toInt()
            val sec = (minutes - minute) * 60.0

            var jd = 367.0 * year -
                floor((7 * (year + floor((month + 9) / 12.0))) * 0.25) +
                floor(275 * month / 9.0) +
                day + 1721013.5
            var jdFrac = (sec + minute * 60.0 + hr * 3600.0) / 86400.0
            if (abs(jdFrac) > 1.0) {
                val whole = floor(jdFrac)
                jd += whole
                jdFrac -= whole
            }
            return jd to jdFrac
        }
    }
}
