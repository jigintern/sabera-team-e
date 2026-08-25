package jp.jig.glasses.sample.kmp.sky

import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** 音声から実行してよい操作。これ以外の設定を表す型を作らない。 */
sealed interface SkyCommand {
    data class ShowSky(val city: City, val epochMillis: Long) : SkyCommand
    data object StartPlayback : SkyCommand
    data object StopPlayback : SkyCommand
    data object ReturnToLive : SkyCommand
}

sealed interface SkyCommandResult {
    data class Accepted(val command: SkyCommand, val confirmation: String) : SkyCommandResult
    data class Rejected(val reason: String) : SkyCommandResult
    data object NotACommand : SkyCommandResult
}

/**
 * 聞き取った文字を許可済みの操作だけへ変換する。
 *
 * AIへ命令の判断を委ねず、対応する日本語を列挙する。都市は [CityCatalog]、日時はIANAタイムゾーンで
 * 解決し、夏時間で存在しない・二重になる時刻は勝手に補正しない。
 */
object SkyCommandParser {
    fun parse(raw: String, nowMillis: Long): SkyCommandResult {
        val text = CityCatalog.normalize(raw)
        if (text.isBlank()) return SkyCommandResult.NotACommand

        if (RETURN_PATTERNS.any { it in text }) {
            return SkyCommandResult.Accepted(SkyCommand.ReturnToLive, "現在の空に戻します")
        }
        if (STOP_PATTERNS.any { it in text }) {
            return SkyCommandResult.Accepted(SkyCommand.StopPlayback, "時間再生を止めます")
        }
        if (PLAY_PATTERNS.any { it in text }) {
            return SkyCommandResult.Accepted(SkyCommand.StartPlayback, "時間を進めます")
        }

        val showIntent = "空" in text && SHOW_PATTERNS.any { it in text }
        if (!showIntent) return SkyCommandResult.NotACommand

        val city = CityCatalog.findIn(text)
            ?: return SkyCommandResult.Rejected("その都市はまだ選べません。対応都市名を入力してください。")
        val time = parseTime(text)
            ?: return SkyCommandResult.Rejected("時刻を「20時30分」のように指定してください。")
        val today = Instant.ofEpochMilli(nowMillis).atZone(city.zoneId).toLocalDate()
        val date = parseDate(text, today)
            ?: return SkyCommandResult.Rejected("日付を確認できませんでした。")
        if (date.year !in MIN_YEAR..MAX_YEAR) {
            return SkyCommandResult.Rejected("指定できる年は${MIN_YEAR}年から${MAX_YEAR}年です。")
        }

        val local = LocalDateTime.of(date, time)
        val offsets = city.zoneId.rules.getValidOffsets(local)
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
            command = SkyCommand.ShowSky(city, epochMillis),
            confirmation = "${city.nameJa} ${CONFIRM_FORMAT.format(local)}を表示します",
        )
    }

    private fun parseDate(text: String, today: LocalDate): LocalDate? {
        if ("明日" in text) return today.plusDays(1)
        if ("今日" in text || "本日" in text) return today
        val match = DATE.find(text) ?: DATE_SLASH.find(text) ?: return today
        return try {
            LocalDate.of(
                match.groupValues[1].takeIf { it.isNotEmpty() }?.toInt() ?: today.year,
                match.groupValues[2].toInt(),
                match.groupValues[3].toInt(),
            )
        } catch (_: DateTimeException) {
            null
        }
    }

    private fun parseTime(text: String): LocalTime? {
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
        if (prefix == "午前" && hour == 12) hour = 0
        if ((prefix == "午後" || prefix == "夜") && hour in 1..11) hour += 12
        return validTime(hour, minute)
    }

    private fun validTime(hour: Int, minute: Int): LocalTime? = try {
        LocalTime.of(hour, minute)
    } catch (_: DateTimeException) {
        null
    }

    private val DATE = Regex("(?:(\\d{4})年)?(\\d{1,2})月(\\d{1,2})日")
    private val DATE_SLASH = Regex("(?:(\\d{4})[/-])?(\\d{1,2})[/-](\\d{1,2})")
    private val CLOCK = Regex("(\\d{1,2}):(\\d{2})")
    private val JAPANESE_TIME = Regex("(午前|午後|朝|夜)?(\\d{1,2})時(?:(\\d{1,2})分|(半))?")
    private val CONFIRM_FORMAT = DateTimeFormatter.ofPattern("yyyy年M月d日 H:mm")
    private val RETURN_PATTERNS = listOf("現在の空に戻", "今の空に戻", "現在地に戻", "現在時刻に戻")
    private val STOP_PATTERNS = listOf("時間を止め", "再生を止め", "時間再生を停止", "動きを止め")
    private val PLAY_PATTERNS = listOf("時間を進め", "時間再生を開始", "再生して", "空を動かして")
    private val SHOW_PATTERNS = listOf("見せ", "表示", "再現", "切り替")
    private const val MIN_YEAR = 1900
    private const val MAX_YEAR = 2100
}
