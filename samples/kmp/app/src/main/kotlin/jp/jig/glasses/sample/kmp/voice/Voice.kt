package jp.jig.glasses.sample.kmp.voice

import kotlinx.coroutines.flow.StateFlow

/**
 * 喋る先。**解説側（narration.Narrator）を JVM テストで回すために切ってある**
 * （`TextToSpeech` は端末が要るので、テストでは差し替える）。
 */
interface Voice {
    /** 言い直す。前の発話は捨てる */
    fun say(text: String)

    /** 前の発話に続ける */
    fun add(text: String)

    fun stop()
}

/**
 * 読み上げの状態。**画面に出すためだけ**にある。
 *
 * [Voice] と分けているのは、JVM テストの差し替え（narration.Narrator の検算）に状態が要らないから。
 */
interface VoiceStatus {
    val speaking: StateFlow<Boolean>

    /**
     * **実際に音が出ているか。**
     *
     * [speaking] は「これから喋る」も含む（積んだ時点で true にしないと、画面側が
     * 合成を待っている間を「もう終わった」と読む）。**字幕を音に合わせるにはこちらが要る。**
     * 合成に数百 ms〜数秒かかるので、[speaking] を起点にめくると音より先に進む（#40）。
     */
    val sounding: StateFlow<Boolean>

    /** 喋れるか。**使えないまま黙るのがいちばん困る**ので、分からない間は null */
    val available: StateFlow<Boolean?>
}
