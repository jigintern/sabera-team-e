package jp.jig.glasses.sample.kmp.ai

import jp.jig.glasses.sample.kmp.starmap.GlassTextPage
import jp.jig.glasses.sample.kmp.starmap.NAKED_EYE_MAGNITUDE
import jp.jig.glasses.sample.kmp.starmap.ObservedStarFact
import jp.jig.glasses.sample.kmp.starmap.cardinalDirection16
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 解説 1 回ぶんの進み具合。スマホ画面に出すためだけに持つ */
enum class NarrationPhase {
    IDLE,

    /** 解説を読み上げている */
    SPEAKING,

    FAILED,
}

data class NarrationState(
    val phase: NarrationPhase = NarrationPhase.IDLE,
    /** 直近に喋った内容。画面にはこれを出す（音が使えない環境ではテキストが主役になる） */
    val text: String = "",
    /**
     * 何について喋ったか。星座モードなら星座名、**人工衛星モードなら機体名**。
     * 名前は星座モードのときのままにしてある（画面側のコードを触らずに済むため）。
     */
    val constellation: String = "",
)

/** 解説を頼むときに画面から渡すもの */
class NarrationInput(
    val calibrated: Boolean,
    val altDeg: Double,
    val azDeg: Double,
    val constellations: List<String>,
    val latDeg: Double,
    val lonDeg: Double,
    val localTime: String,
    val visibleStars: List<ObservedStarFact> = emptyList(),
    /** 視野内の月・惑星。端末が計算した確定値 */
    val visibleBodies: List<ObservedStarFact> = emptyList(),
)

/** 人工衛星モードで喋るときに渡すもの。端末が計算した確定値だけ */
class SatellitePass(
    val name: String,
    val azDeg: Double,
    val altDeg: Double,
    /** 日が当たっているか。当たっていなければ肉眼では見えない */
    val sunlit: Boolean,
    /** 最接近までの分。マイナスなら過ぎている。分からなければ null */
    val closestInMinutes: Double? = null,
    /** 静止軌道のようにほとんど動かないか */
    val stationary: Boolean = false,
)

/**
 * 「あれは何？」に答える。**通信は要らない。**
 *
 * 解説は端末が持っている（`data/constellation-lore.json`）。**星を見に行く場所は電波が届かない。**
 * その場で AI に作らせていたときは、圏外だと一言も出せなかった。
 * 神話も豆知識も星座ごとに決まっていて変わらないので、持って行けばよい。
 *
 * **タップして無反応が一番よくない**（app-flow.md）ので、どの経路を通っても必ず何か喋る。
 */
class Narrator(
    private val speaker: Voice,
    /**
     * 星座名 → 解説文。同梱の星表と同じ扱いで、無ければ端末が計算した事実だけで話を閉じる。
     * Android に触らせないため、読み込みは呼ぶ側に任せて関数で受ける。
     */
    private val lore: (String) -> String?,
    /** 画面のログへ流す。実機で何が起きたかはログだけが頼り */
    private val log: (String, Boolean) -> Unit,
) {

    private val _state = MutableStateFlow(NarrationState())
    val state: StateFlow<NarrationState> = _state

    val busy: Boolean get() = _state.value.phase != NarrationPhase.IDLE &&
        _state.value.phase != NarrationPhase.FAILED

    suspend fun narrate(input: NarrationInput) {
        val constellation = input.constellations.firstOrNull()

        // 喋れない理由がある経路。ここで返しても「無反応」にはならない
        guidance(input, constellation)?.let { guide ->
            speaker.say(guide)
            _state.value = NarrationState(NarrationPhase.FAILED, guide, constellation.orEmpty())
            log("解説せず案内: $guide", true)
            return
        }
        constellation!!

        // 名前は端末が知っている確定値なので、まず名乗る
        speaker.say(opening(constellation))
        val script = script(constellation, input)
        // 句点ごとに積む。1 文目の音が早く鳴り、作り直しも 1 文ぶんで済む
        val accumulator = SentenceAccumulator()
        val sentences = accumulator.append(script) +
            listOf(accumulator.flush()).filter { it.isNotBlank() }
        for (sentence in sentences) speaker.add(sentence)

        _state.value = NarrationState(NarrationPhase.SPEAKING, script, constellation)
        log("解説: $constellation（${script.length}文字・${sentences.size}文）", false)
    }

    /**
     * 喋る中身。
     *
     * **話すのは神話と豆知識だけ。** どんな形でどこに見えるかは、グラスの星図がそのまま見せている。
     * 言葉で形をなぞっても、聞いている人は目の前の空と突き合わせられない。
     * 視野に月や惑星があるときだけ、**入る範囲で**一言足す（惑星は日によって違うので言う価値がある）。
     */
    private fun script(constellation: String, input: NarrationInput): String {
        val text = lore(constellation) ?: return groundedFallback(constellation, input)
        val extra = bodyLine(input) ?: return text
        // グラスの解説画面（#40）に入らないなら足さない。溢れたぶんは音では聞けても文字では読めない
        return if (text.length + extra.length <= GlassTextPage.bodyChars) text + extra else text
    }

    /** 視野の月・惑星を一言だけ。**肉眼で見えないものは言わない**（探させても見つからない） */
    private fun bodyLine(input: NarrationInput): String? {
        val visible = input.visibleBodies.filter { it.magnitude <= NAKED_EYE_MAGNITUDE }.take(2)
        if (visible.isEmpty()) return null
        return "いま近くに${visible.joinToString("と") { it.nameJa }}が出ています。"
    }

    /**
     * 人工衛星モードの「あれは何？」。
     *
     * 星座と同じで**通信は要らない**。機体名・方角・高度・日照はすべて端末が計算した確定値。
     */
    fun narrateSatellites(inView: List<SatellitePass>) {
        val lead = inView.firstOrNull()
        if (lead == null) {
            val guide = "いま視野には人工衛星がいません。空の別のほうを向いてください。"
            speaker.say(guide)
            _state.value = NarrationState(NarrationPhase.FAILED, guide, "")
            log("衛星が視野にいない", false)
            return
        }

        val text = buildString {
            append("いま視野には")
            append(lead.name)
            append("が入っています。")
            append(compass(lead.azDeg))
            append("の空、高度 ")
            append(lead.altDeg.toInt())
            append(" 度あたりです。")
            // **「いつ」を言う。** 点の位置だけでは、待てばいいのか過ぎたのかが分からない
            append(timing(lead))
            append(
                if (lead.sunlit) {
                    "日が当たっているので、動く光として肉眼でも見えるかもしれません。"
                } else {
                    "地球の影に入っているので、肉眼では見えません。"
                },
            )
            val others = inView.drop(1)
            if (others.isNotEmpty()) {
                append("ほかに")
                append(others.take(2).joinToString("、") { it.name })
                append("も同じ視野にいます。")
            }
        }
        speaker.say(text)
        _state.value = NarrationState(NarrationPhase.SPEAKING, text, lead.name)
        log("衛星を案内: ${lead.name}（視野に ${inView.size} 機）", false)
    }

    /** 「あと 3 分で最接近します。」のような一言。言えることが無ければ空 */
    private fun timing(pass: SatellitePass): String {
        if (pass.stationary) return "ほとんど動かないので、しばらく同じ場所に見えます。"
        val minutes = pass.closestInMinutes ?: return ""
        return when {
            minutes > 0.5 -> "あと ${kotlin.math.ceil(minutes).toInt()} 分でいちばん近づきます。"
            minutes > -0.5 -> "いまがいちばん近いところです。"
            else -> "いちばん近いところは過ぎて、遠ざかっています。"
        }
    }

    fun stop() {
        speaker.stop()
        _state.value = _state.value.copy(phase = NarrationPhase.IDLE)
    }

    /**
     * 次の解説に備えて前回の内容を捨てる。
     *
     * **グラスの解説画面（#40）は [state] をそのまま映す**ので、消しておかないと
     * タップした直後に前回の解説文が一瞬出る。喋り始める前に呼ぶ。
     */
    fun reset() {
        _state.value = NarrationState()
    }

    /** 読み上げが終わったことを画面へ返す。Speaker の状態を見る側から呼ぶ */
    fun finishedSpeaking() {
        if (_state.value.phase == NarrationPhase.SPEAKING) {
            _state.value = _state.value.copy(phase = NarrationPhase.IDLE)
        }
    }

    /**
     * 解説できないときの案内。null なら解説してよい。
     *
     * **仰角では断らない。** 「地平線より下だから地面だ」と断っていたが、
     * 水平あたりを見ているだけでも仰角は負に振れる（方位合わせに残る誤差もそのまま乗る）。
     * 断られた人には理由が分からず、**見えているものの名前も出ないまま黙る**ことになる。
     * グラスには視線の先の星図が出ているので、**出ているものをそのまま解説する。**
     */
    private fun guidance(input: NarrationInput, constellation: String?): String? = when {
        !input.calibrated ->
            "まだ方位が合っていません。スマホを顔の前にかざして、十字を丸に重ねてください。"

        constellation == null ->
            "星座を割り出せませんでした。星表が読めていないかもしれません。"

        else -> null
    }

    companion object {
        /**
         * 最初の一言。**タップされたことがすぐ音で返る**ようにするための名乗り。
         *
         * ここに切り出してあるのは、**タップより先にこの音声を作っておく**ため
         * （[CloudVoice.warm] の鍵は文字列そのものなので、1 文字でも違うと当たらない）。
         */
        fun opening(constellation: String): String = "${constellation}ですね。"
    }
}

/**
 * 解説文を持っていない星座のときに、端末が計算した事実だけで話を閉じる。
 *
 * 88 星座ぶん同梱してあるので普段は通らないが、**黙るよりは方角と目印を言うほうがよい**。
 */
fun groundedFallback(subject: String, input: NarrationInput): String = buildString {
    append(subject)
    append("の領域を、")
    append(compass(input.azDeg))
    append("の空、高度 ")
    append(input.altDeg.toInt())
    append(" 度あたりで見ています。")
    // 月と惑星は恒星より目印になる。あるなら先に言う
    if (input.visibleBodies.isNotEmpty()) {
        append("視野には")
        append(input.visibleBodies.take(2).joinToString("と") { it.nameJa })
        append("が入っています。")
    }
    if (input.visibleStars.isNotEmpty()) {
        append("視野では")
        append(input.visibleStars.take(2).joinToString("と") { it.nameJa })
        append("が目印です。")
    }
}

/** 方位角[度]を 16 方位の日本語に。読み上げるので「南南西」まで刻む */
fun compass(azDeg: Double): String {
    return cardinalDirection16(azDeg)
}
