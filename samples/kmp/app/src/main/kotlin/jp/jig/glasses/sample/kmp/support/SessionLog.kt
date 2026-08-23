package jp.jig.glasses.sample.kmp.support

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 開発用の記録をファイルに残す。
 *
 * 画面のログは 40 行しか持たず、画面を出た時点で消える。**ドリフト率のように
 * 「30 分回して初めて分かる」計測がこれでは取れない**（1 分おきに 1 行入れるだけで枠が尽きる）。
 * しかも実測できるのは夜に屋外で実機を持っている人だけなので、その場で残らないと
 * データが取れないまま終わる。
 *
 * logcat では代替できない。**adb の有線接続が要る**ので、夜の屋外では現実的でない。
 *
 * 追記と消去は同じ Channel に流して 1 本のコルーチンで処理する。順が入れ替わると
 * 経過時間の系列として読めなくなるため。
 */
class SessionLog(context: Context, scope: CoroutineScope) {

    private sealed interface Command {
        class Line(val text: String) : Command
        data object Clear : Command
    }

    private val file = File(context.filesDir, FILE_NAME)
    private val queue = Channel<Command>(Channel.UNLIMITED)
    private val clock = SimpleDateFormat("MM-dd HH:mm:ss", Locale.JAPAN)

    init {
        scope.launch(Dispatchers.IO) {
            for (command in queue) {
                runCatching {
                    when (command) {
                        is Command.Line -> write(command.text)
                        Command.Clear -> file.delete()
                    }
                }
            }
        }
    }

    /** 記録の大きさ。設定パネルに出して、消し忘れに気づけるようにする */
    val bytes: Long get() = if (file.exists()) file.length() else 0L

    fun append(text: String) {
        queue.trySend(Command.Line(clock.format(Date()) + "  " + text))
    }

    /** 計測を仕切り直すときに呼ぶ。前回のセッションが混ざると系列として読めない */
    fun clear() {
        queue.trySend(Command.Clear)
    }

    /**
     * Slack に貼れる形で外に出す。FileProvider を足さずに済むよう本文に載せる。
     *
     * Binder に上限があるので丸ごとは載せない。**古いほうから捨てる**
     * （ドリフト計測は直近ほど知りたい）。
     */
    fun shareIntent(): Intent? {
        val text = runCatching { tail(SHARE_BYTES) }.getOrNull()
        if (text.isNullOrBlank()) return null
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "星図の記録")
            putExtra(Intent.EXTRA_TEXT, text)
        }
    }

    private fun write(line: String) {
        // 転送のログは数秒おきに出るので、放っておくと際限なく育つ。
        // 上限を超えたら後ろ半分だけ残す
        if (file.length() > MAX_BYTES) {
            val kept = tail(MAX_BYTES / 2)
            file.writeText("--- ここより前は大きさの上限で捨てた ---\n" + kept)
        }
        file.appendText(line + "\n")
    }

    /** 末尾から [limit] バイトぶん。切り出したときは行の途中から始まらないよう最初の改行まで捨てる */
    private fun tail(limit: Long): String {
        if (!file.exists()) return ""
        if (file.length() <= limit) return file.readText()
        val bytes = file.readBytes()
        val text = String(bytes, bytes.size - limit.toInt(), limit.toInt())
        return text.substringAfter('\n', text)
    }

    private companion object {
        const val FILE_NAME = "starmap-log.txt"
        const val MAX_BYTES = 512L * 1024
        const val SHARE_BYTES = 256L * 1024
    }
}
