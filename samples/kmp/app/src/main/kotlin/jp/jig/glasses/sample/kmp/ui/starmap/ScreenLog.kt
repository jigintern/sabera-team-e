package jp.jig.glasses.sample.kmp.ui.starmap

import androidx.compose.runtime.mutableStateListOf
import jp.jig.glasses.sample.kmp.ui.component.LogLine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 観測画面のログ。画面には新しい順に 40 行だけ、同じ行をファイルにも残す。
 *
 * 画面のログは画面を出ると消えるので、ドリフト率のような長い計測はファイル側で読む
 * （docs/team-e/03_coordinate-system.md の「実測しないと決められないこと」）。
 */
internal class ScreenLog(
    /** ファイルへの併記（SessionLog.append） */
    private val append: (String) -> Unit,
    /** logcat への出力。**android.util.Log は JVM テストで例外を投げる**ので差し替えられる形にする */
    private val logcat: (text: String, failed: Boolean) -> Unit,
) {

    private val clock = SimpleDateFormat("HH:mm:ss", Locale.JAPAN)

    /** 新しい順。Compose の状態リストなので、画面はそのまま追従する */
    val lines = mutableStateListOf<LogLine>()

    fun log(text: String, failed: Boolean = false) {
        lines.add(0, LogLine(clock.format(Date()), text, failed))
        while (lines.size > LOG_LINES) lines.removeAt(lines.lastIndex)
        append(if (failed) "失敗  " + text else text)
        logcat(text, failed)
    }
}

/** 画面のログはこの行数だけ持つ */
internal const val LOG_LINES = 40
