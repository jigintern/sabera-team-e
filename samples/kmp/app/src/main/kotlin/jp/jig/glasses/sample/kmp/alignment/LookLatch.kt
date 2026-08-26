package jp.jig.glasses.sample.kmp.alignment

/**
 * 少し過去の視線を取り出すための履歴。
 *
 * **ツルをタップすると頭が動く。** タップ時点の視線で星座を判定すると、押した反動で
 * 隣の星座に化けることがある（31_gestures.md）。判定はタップ直前の視線から取る。
 */
class LookLatch(
    /** どれだけ過去の視線で判定するか */
    private val latchMs: Long = LATCH_MS,
    /** 履歴を持つ長さ。ラッチに使うぶんだけあればよい */
    private val historyMs: Long = HISTORY_MS,
) {

    /** (時刻, ヨー, ピッチ)。補正済みのヨーを積む（生のヨーを混ぜると解説の星座がずれる） */
    private val history = ArrayDeque<Triple<Long, Double, Double>>()

    fun record(atMillis: Long, yawDeg: Double, pitchDeg: Double) {
        history.addLast(Triple(atMillis, yawDeg, pitchDeg))
        while (history.isNotEmpty() && atMillis - history.first().first > historyMs) {
            history.removeFirst()
        }
    }

    /** ラッチ時点のヨーとピッチ。履歴が足りなければ null（呼び出し側が現在値でごまかす） */
    fun latched(nowMillis: Long): Pair<Double, Double>? =
        history.lastOrNull { it.first <= nowMillis - latchMs }?.let { it.second to it.third }
}

/** タップの反動を避けて、これだけ過去の視線で判定する */
const val LATCH_MS = 500L

/** 視線の履歴を持つ長さ */
const val HISTORY_MS = 3_000L
