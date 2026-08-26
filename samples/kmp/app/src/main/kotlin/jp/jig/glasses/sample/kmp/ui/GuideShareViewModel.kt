package jp.jig.glasses.sample.kmp.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import jp.jig.glasses.sample.kmp.guide.StarGuide

/**
 * 配る前の選び直し。**原本は変えない**（外した段は QR に入らないだけで台本には残る）。
 *
 * 段の ON/OFF と編集の可否だけを持ち、圧縮と QR の生成は画面側が [shared] から作る
 * （**押すたびに圧縮し直さない**ため、Compose の remember に鍵として渡す）。
 */
internal class GuideShareViewModel(private val guide: StarGuide) : ViewModel() {

    /** 段ごとに配るかどうか。並びは [StarGuide.steps] と同じ */
    var enabled by mutableStateOf(guide.steps.map { it.enabled })
        private set

    /** 受け取った人に編集させないか。**客がうっかり直すと、その 1 台だけ違うツアーになる** */
    var locked by mutableStateOf(guide.locked)

    /** 書き出しの結果 */
    var notice by mutableStateOf<String?>(null)

    /** いま配ろうとしている中身 */
    val shared: StarGuide
        get() = guide.copy(
            steps = guide.steps.mapIndexed { i, step -> step.copy(enabled = enabled.getOrElse(i) { true }) },
            locked = locked,
        )

    fun toggleStep(index: Int, on: Boolean) {
        enabled = enabled.mapIndexed { i, old -> if (i == index) on else old }
    }
}
