package jp.jig.glasses.sample.kmp.sky

import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** 再現する場所。都市を言わなかったときは、いまいる場所をそのまま使う。 */
data class SkyPlace(val site: Site, val zoneId: ZoneId, val nameJa: String) {
    companion object {
        fun of(city: City): SkyPlace = SkyPlace(city.site, city.zoneId, city.nameJa)
    }
}

/**
 * 音声から実行してよい操作。これ以外の設定を表す型を作らない。
 *
 * **時間の連続再生は入れていない。** 2 秒ごとに全画面を焼き直すことになり、
 * 1 枚 279〜390ms の転送のたびにパネルが消えるので**原理的に点滅する**。
 * 時刻はスマホのつまみで動かす（離したときの 1 回だけ送る）。
 */
sealed interface SkyCommand {
    data class ShowSky(val place: SkyPlace, val epochMillis: Long) : SkyCommand
    data object ReturnToLive : SkyCommand
}

/**
 * 聞き取れたところまで。**次の `HOLD` の答えと合流させる**ために持ち越す。
 *
 * 足りない条件で断ってしまうと、利用者は最初から全部言い直すことになる（#45）。
 */
data class PendingSkyRequest(
    val place: SkyPlace? = null,
    val date: LocalDate? = null,
    val time: LocalTime? = null,
)

sealed interface SkyCommandResult {
    data class Accepted(val command: SkyCommand, val confirmation: String) : SkyCommandResult
    data class Rejected(val reason: String) : SkyCommandResult

    /** 足りないぶんを聞き返す。[pending] を次の聞き取りへ渡す */
    data class NeedMore(val pending: PendingSkyRequest, val question: String) : SkyCommandResult
    data object NotACommand : SkyCommandResult
}

/**
 * 聞き取った文字を許可済みの操作だけへ変換する。
 *
 * AIへ命令の判断を委ねず、対応する日本語を列挙する。都市は [CityCatalog]、日時はIANAタイムゾーンで
 * 解決し、夏時間で存在しない・二重になる時刻は勝手に補正しない。
 *
 * **深い時代は「何年前」で受ける。** 1 万年前まで遡れるのは [longTermPrecess] が入っているから
 * （[docs/team-e/38_sky-simulation.md](../../../../../../../../../docs/team-e/38_sky-simulation.md)）。
 */
object SkyCommandParser {

    /**
     * @param livePlace 都市を言わなかったときに使う、いまいる場所
     * @param pending 前回の聞き返しで持ち越した条件。**あれば、操作語が無くても続きとして読む**
     */
    fun parse(
        raw: String,
        nowMillis: Long,
        livePlace: SkyPlace = DEFAULT_LIVE_PLACE,
        pending: PendingSkyRequest? = null,
    ): SkyCommandResult {
        val text = normalizeNumbers(CityCatalog.normalize(raw))
        if (text.isBlank()) return SkyCommandResult.NotACommand

        if (RETURN_PATTERNS.any { it in text }) {
            return SkyCommandResult.Accepted(SkyCommand.ReturnToLive, "現在の空に戻します")
        }

        val city = CityCatalog.findIn(text)
        val place = city?.let(SkyPlace::of) ?: pending?.place
        val zone = place?.zoneId ?: livePlace.zoneId
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val nowTime = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalTime()

        // **持ち越しと合流する前の値を別に持つ。** 合流後で「続き」を判定すると、
        // 持ち越しに日付か時刻が 1 つでも入っているだけで、空の再現と関係のない発話まで
        // 続きとみなされ、時刻が埋まるまで聞き返しから抜けられなくなる
        val spokenDate = parseDate(text, today)
        val spokenTime = parseTime(text, nowTime)
        val date = spokenDate ?: pending?.date
        val time = spokenTime ?: pending?.time

        // 操作語が無くても、聞き返しの続きなら読む。**「20時30分」だけで答えられるように**
        val continuing = pending != null && (city != null || spokenDate != null || spokenTime != null)
        val showIntent = "空" in text && SHOW_PATTERNS.any { it in text }
        if (!showIntent && !continuing) return SkyCommandResult.NotACommand

        if (time == null) {
            return SkyCommandResult.NeedMore(
                PendingSkyRequest(place, date, null),
                "いつ頃の夜空でしょうか。「20時30分」のように言ってください。",
            )
        }
        val resolvedPlace = place ?: livePlace
        val resolvedDate = date ?: today
        if (resolvedDate.year !in MIN_YEAR..MAX_YEAR) {
            return SkyCommandResult.Rejected(
                "再現できるのは${yearLabel(MIN_YEAR)}から${yearLabel(MAX_YEAR)}までです。",
            )
        }

        val local = LocalDateTime.of(resolvedDate, time)
        val offsets = resolvedPlace.zoneId.rules.getValidOffsets(local)
        if (offsets.size != 1) {
            val reason = if (offsets.isEmpty()) {
                "その時刻は夏時間の切り替えで存在しません。別の時刻を指定してください。"
            } else {
                "その時刻は夏時間の切り替えで二通りあります。別の時刻を指定してください。"
            }
            return SkyCommandResult.Rejected(reason)
        }
        val epochMillis = local.toInstant(offsets.single()).toEpochMilli()
        return SkyCommandResult.Accepted(
            command = SkyCommand.ShowSky(resolvedPlace, epochMillis),
            confirmation = "${resolvedPlace.nameJa} ${confirmationOf(local)}を表示します",
        )
    }

    /** 確認文。**負の年は「紀元前」で読む**（`yyyy` に任せると `-2999` と出る） */
    fun confirmationOf(local: LocalDateTime): String =
        "${yearLabel(local.year)}${local.monthValue}月${local.dayOfMonth}日 " +
            "%d:%02d".format(local.hour, local.minute)

    /**
     * 天文の年番号 → 読み上げる年。
     *
     * 天文では **1 年の前が 0 年、その前が −1 年**で、紀元前 3000 年は −2999 年にあたる。
     * 表示でそのまま「−2999 年」と出すと誰にも通じないので、ここで直す。
     */
    fun yearLabel(year: Int): String = if (year <= 0) "紀元前${1 - year}年" else "${year}年"

    private fun parseDate(text: String, today: LocalDate): LocalDate? {
        RELATIVE_YEARS.find(text)?.let { match ->
            val amount = match.groupValues[1].toLongOrNull() ?: return@let
            val unit = if (match.groupValues[2] == "万") 10_000L else 1L
            val years = amount * unit
            val signed = if (match.groupValues[3] == "前") -years else years
            return shiftYears(today, signed)
        }
        BC_YEAR.find(text)?.let { match ->
            val bc = match.groupValues[1].toIntOrNull() ?: return@let
            // 紀元前 N 年 = 天文の 1 - N 年
            return safeDate(1 - bc, today.monthValue, today.dayOfMonth)
        }
        if ("明日" in text) return today.plusDays(1)
        if ("昨日" in text) return today.minusDays(1)
        if ("今日" in text || "本日" in text || "今夜" in text || "今晩" in text) return today
        val match = DATE.find(text) ?: DATE_SLASH.find(text) ?: return null
        return safeDate(
            match.groupValues[1].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: today.year,
            match.groupValues[2].toInt(),
            match.groupValues[3].toInt(),
        )
    }

    /** うるう日を持ち越さない。1 万年ずらすと 2/29 はたいてい存在しない */
    private fun shiftYears(today: LocalDate, years: Long): LocalDate? {
        val target = today.year + years
        if (target > Int.MAX_VALUE || target < Int.MIN_VALUE) return null
        return safeDate(target.toInt(), today.monthValue, today.dayOfMonth)
            ?: safeDate(target.toInt(), today.monthValue, 28)
    }

    private fun safeDate(year: Int, month: Int, day: Int): LocalDate? = try {
        LocalDate.of(year, month, day)
    } catch (_: DateTimeException) {
        null
    }

    private fun parseTime(text: String, nowTime: LocalTime): LocalTime? {
        if (SAME_TIME_PATTERNS.any { it in text }) return nowTime.withSecond(0).withNano(0)
        CLOCK.find(text)?.let { match ->
            return validTime(match.groupValues[1].toInt(), match.groupValues[2].toInt())
        }
        val match = JAPANESE_TIME.find(text) ?: return null
        val prefix = match.groupValues[1]
        var hour = match.groupValues[2].toInt()
        val minute = when {
            match.groupValues[3].isNotEmpty() -> match.groupValues[3].toInt()
            match.groupValues[4].isNotEmpty() -> 30
            else -> 0
        }
        // 12 時だけ言い方で指す先が変わる。「午前12時」「夜12時」「深夜12時」は 0 時、
        // 「午後12時」は日本語では正午なので 12 のまま
        if ((prefix == "午前" || prefix == "夜" || prefix == "深夜") && hour == 12) hour = 0
        if ((prefix == "午後" || prefix == "夜" || prefix == "深夜") && hour in 1..11) hour += 12
        return validTime(hour, minute)
    }

    private fun validTime(hour: Int, minute: Int): LocalTime? = try {
        LocalTime.of(hour, minute)
    } catch (_: DateTimeException) {
        null
    }

    /**
     * 漢数字を算用数字へ直す。**「一万年前」と「1万年前」を同じに扱う**ためだけに置く。
     *
     * 文字起こしがどちらを返すかは選べない。位取り（十・百・千）まで見て、
     * 「万」は年数の単位として後段の正規表現へ残す。
     */
    internal fun normalizeNumbers(text: String): String {
        val out = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            val start = index
            var value = 0L
            var digit = 0L
            var seen = false
            while (index < text.length) {
                val char = text[index]
                val small = KANJI_DIGITS[char]
                val scale = KANJI_SCALES[char]
                when {
                    small != null -> {
                        digit = small
                        seen = true
                    }
                    scale != null -> {
                        value += (if (digit == 0L) 1L else digit) * scale
                        digit = 0L
                        seen = true
                    }
                    else -> break
                }
                index++
            }
            if (seen) {
                out.append(value + digit)
            } else {
                out.append(text[start])
                index = start + 1
            }
        }
        return out.toString()
    }

    /** 「万」は年数の単位として残すので、位取りには入れない */
    private val KANJI_DIGITS = mapOf(
        '一' to 1L, '二' to 2L, '三' to 3L, '四' to 4L, '五' to 5L,
        '六' to 6L, '七' to 7L, '八' to 8L, '九' to 9L, '〇' to 0L, '零' to 0L,
    )
    private val KANJI_SCALES = mapOf('十' to 10L, '百' to 100L, '千' to 1000L)

    private val DATE = Regex("(?:(-?\\d{1,6})年)?(\\d{1,2})月(\\d{1,2})日")
    private val DATE_SLASH = Regex("(?:(-?\\d{1,6})[/-])?(\\d{1,2})[/-](\\d{1,2})")
    private val CLOCK = Regex("(\\d{1,2}):(\\d{2})")
    /**
     * 「20時30分」「夜9時半」。**「時」のうしろに「間」が続くものは取らない。**
     *
     * 見ないと「1時間後の星空を見せて」の「1時」に当たり、頼んでいない今日の 1 時へ飛ぶ。
     * 相対時刻（N 時間前／後）を受ける口はまだ無いので、当たらなければ質問回答へ流れる。
     */
    private val JAPANESE_TIME = Regex("(午前|午後|朝|夜|深夜)?(\\d{1,2})時(?!間)(?:(\\d{1,2})分|(半))?")

    /** 「1万年前」「10000年前」「2000年後」。**万は単位として別に取る** */
    private val RELATIVE_YEARS = Regex("(\\d{1,6})(万)?年(前|後)")
    private val BC_YEAR = Regex("紀元前(\\d{1,6})年")

    private val SAME_TIME_PATTERNS = listOf("今と同じ時刻", "今と同じ", "今頃", "いまごろ", "現在時刻")
    private val RETURN_PATTERNS = listOf("現在の空に戻", "今の空に戻", "現在地に戻", "現在時刻に戻")
    private val SHOW_PATTERNS = listOf("見せ", "みせ", "表示", "再現", "切り替", "見たい", "みたい")

    /**
     * 動かせる範囲。**長期歳差が受け持つのは ±200,000 年**あるが、
     * 固有運動の線形外挿がそれより先に崩れるので 1 万年で切る。
     *
     * 未来側を 13,000 年まで取ってあるのは、**「1 万年後」を選べるようにする**ため。
     * いまが 2026 年なので 12,000 年で切ると、**押しても何も起きない選択肢**になる
     * （`SkyPresetsTest` がそれを見ている）。
     */
    const val MIN_YEAR = -9_999
    const val MAX_YEAR = 13_000

    private val DEFAULT_LIVE_PLACE = SkyPlace(
        ObservationDefaults.site,
        ZoneId.of("Asia/Tokyo"),
        "現在地",
    )
}
