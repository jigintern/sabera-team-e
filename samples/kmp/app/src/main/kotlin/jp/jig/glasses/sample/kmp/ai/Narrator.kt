package jp.jig.glasses.sample.kmp.ai

import android.util.Log
import jp.jig.glasses.sample.kmp.starmap.ObservedStarFact
import jp.jig.glasses.sample.kmp.starmap.cardinalDirection16
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 解説 1 回ぶんの進み具合。スマホ画面に出すためだけに持つ */
enum class NarrationPhase {
    IDLE,

    /** 星座名だけ喋って、AI の返事を待っている */
    GENERATING,

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
    val headingUncertaintyDeg: Double? = null,
    val pitchUncertaintyDeg: Double? = null,
    val knownBrightStarNames: Set<String> = emptySet(),
    /** 星図の PNG（Base64）。作れなければ null で、そのときは文字だけで頼む */
    val pngBase64: String? = null,
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
 * 「あれは何？」に答える。
 *
 * **タップして無反応が一番よくない**（app-flow.md）ので、どの経路を通っても必ず何か喋る。
 * 星座名は端末が既に知っているため、**LLM を待たずに最初の一言を返せる**。
 * 生成に 1〜3 秒かかっても、その間ずっと黙っていることにはならない。
 */
class Narrator(
    private val speaker: Voice,
    private val client: OpenAiClient,
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

        // 端末が知っている事実なので即座に喋る。LLM を待たない
        val opening = opening(constellation)
        speaker.say(opening)
        _state.value = NarrationState(NarrationPhase.GENERATING, opening, constellation)
        log("解説を頼む: $constellation", false)

        val ask = ExplainRequest(
            constellations = input.constellations,
            latDeg = input.latDeg,
            lonDeg = input.lonDeg,
            azDeg = input.azDeg,
            altDeg = input.altDeg,
            localTime = input.localTime,
            visibleStars = input.visibleStars,
            headingUncertaintyDeg = input.headingUncertaintyDeg,
            pitchUncertaintyDeg = input.pitchUncertaintyDeg,
            knownBrightStarNames = input.knownBrightStarNames,
            pngBase64 = input.pngBase64,
        )
        val guard = ExplanationGuard(
            visibleStarNames = input.visibleStars.mapTo(mutableSetOf()) { it.nameJa },
            knownStarNames = input.knownBrightStarNames,
        )

        val received = StringBuilder()
        val completedText = StringBuilder()
        var completedSentences = 0
        var sentenceAccumulator = SentenceAccumulator()

        /** 受信した文をその場で画面へ出し、句点まで揃ったものから読み上げへ積む。 */
        fun accept(delta: String) {
            received.append(delta)
            _state.value = NarrationState(NarrationPhase.GENERATING, received.toString(), constellation)
            for (sentence in sentenceAccumulator.append(delta)) {
                val rejection = guard.rejectionReason(sentence)
                if (rejection != null) {
                    log("根拠のない解説文を除外[$rejection]: $sentence", true)
                    continue
                }
                completedText.append(sentence)
                speaker.add(sentence)
                completedSentences++
            }
        }

        val explanation = try {
            try {
                client.explain(ask, ::accept)
            } catch (e: EmptyReplyException) {
                // 空応答はモデル側の都合で起きるので、1 回だけ頼み直す。
                // API エラーと通信断では繰り返さない
                // （つながらないものを待たせると無言が倍になる）
                log("応答が空だったので 1 回だけ頼み直す（${e.message}）", true)
                received.clear()
                completedText.clear()
                completedSentences = 0
                sentenceAccumulator = SentenceAccumulator()
                client.explain(ask, ::accept)
            }
        } catch (e: CancellationException) {
            // 停止トグルで畳まれた場合。失敗ではないので、そのまま上へ流す
            throw e
        } catch (e: Throwable) {
            // JVMテストでも通信断経路を通せるよう、Androidログ自体の失敗は本処理へ影響させない
            runCatching { Log.e(TAG, "解説の生成に失敗", e) }
            val kind = classifyFailure(e)
            // 句点まで届いた文は既に読み上げキューに入っている。途中で切れても、
            // 不完全な末尾だけ捨ててそのまま終える。失敗案内を足すと余韻が壊れる。
            sentenceAccumulator.discard()
            val hasPartialResponse = received.isNotBlank() || completedSentences > 0
            val completed = completedText.toString().trim()
            val shown = if (hasPartialResponse) {
                listOf(opening, completed).filter { it.isNotEmpty() }.joinToString("\n")
            } else {
                val fallback = fallbackLine(kind, constellation, input.azDeg, input.altDeg)
                speaker.add(fallback)
                "$opening$fallback"
            }
            _state.value = NarrationState(
                if (hasPartialResponse) NarrationPhase.IDLE else NarrationPhase.FAILED,
                shown,
                constellation,
            )
            // 生のメッセージを必ず載せる。実機で何が起きたかはここだけが頼り
            log(
                "解説を作れない[$kind] 受信${received.length}文字・" +
                    "完了${completedSentences}文: ${e.message}",
                true,
            )
            return
        }

        // モデルが最後の句点を省いたときだけ残りをここで積む。
        // 既に積んだ文は二重に送らない
        sentenceAccumulator.flush().takeIf { it.isNotEmpty() }?.let {
            val rejection = guard.rejectionReason(it)
            if (rejection == null) {
                completedText.append(it)
                speaker.add(it)
                completedSentences++
            } else {
                log("根拠のない解説文を除外[$rejection]: $it", true)
            }
        }
        if (completedSentences == 0) {
            val fallback = groundedFallback(constellation, input)
            speaker.add(fallback)
            _state.value = NarrationState(NarrationPhase.FAILED, fallback, constellation)
            log("AI解説に根拠のある文が無いため端末の観測事実へ切り替え", true)
            return
        }
        val accepted = completedText.toString().trim()
        _state.value = NarrationState(NarrationPhase.SPEAKING, accepted, constellation)
        log(
            "解説を読み上げ中（受信${explanation.length}文字・採用${accepted.length}文字・${completedSentences}文）",
            false,
        )
    }

    /**
     * 人工衛星モードの「あれは何？」。
     *
     * **LLM は使わない。** 機体名・方角・高度・日照はすべて端末が計算した確定値で、
     * 生成に投げると待つだけ損をする（星座は由来や探し方があるので LLM が効く）。
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

    /** 読み上げが終わったことを画面へ返す。Speaker の状態を見る側から呼ぶ */
    fun finishedSpeaking() {
        if (_state.value.phase == NarrationPhase.SPEAKING) {
            _state.value = _state.value.copy(phase = NarrationPhase.IDLE)
        }
    }

    /** 解説できないときの案内。null なら解説してよい */
    private fun guidance(input: NarrationInput, constellation: String?): String? = when {
        !input.calibrated ->
            "まだ方位が合っていません。スマホを顔の前にかざして、十字を丸に重ねてください。"

        input.altDeg < 0 ->
            "いまは地面のほうを向いています。空を見上げてください。"

        constellation == null ->
            "星座を割り出せませんでした。星表が読めていないかもしれません。"

        !client.configured ->
            opening(constellation) + "AI の設定がないので、解説はできません。"

        else -> null
    }

    companion object {
        private const val TAG = "Narrator"

        /**
         * 最初の一言。星座名は端末が持っている確定値なので、**LLM を待たずに喋れる**。
         *
         * ここに切り出してあるのは、**タップより先にこの音声を作っておく**ため
         * （[CloudVoice.warm] の鍵は文字列そのものなので、1 文字でも違うと当たらない）。
         */
        fun opening(constellation: String): String = "${constellation}ですね。"
    }
}

/** AI本文が全て棄却されたときも、端末が計算した事実だけで必ず応答する。 */
fun groundedFallback(subject: String, input: NarrationInput): String = buildString {
    append(subject)
    append("の領域を、")
    append(compass(input.azDeg))
    append("の空、高度 ")
    append(input.altDeg.toInt())
    append(" 度あたりで見ています。")
    if (input.visibleStars.isNotEmpty()) {
        append("視野では")
        append(input.visibleStars.take(2).joinToString("と") { it.nameJa })
        append("が目印です。")
    }
}

/**
 * 解説が作れなかったときの逃げ道。端末が知っていることだけで話を閉じる。
 *
 * **原因を取り違えて喋らない。** かつては失敗を全部「いまは通信ができない」と言っていたので、
 * 推論が出力枠を使い切っただけのときまで圏外だと思い込ませていた。
 * トップレベルに出してあるのは、この写像を JVM テストで押さえるため。
 */
fun fallbackLine(kind: FailureKind, subject: String, azDeg: Double, altDeg: Double): String {
    val reason = when (kind) {
        FailureKind.EMPTY -> "解説がうまく作れませんでした。"
        FailureKind.API -> "AI につながりませんでした。"
        FailureKind.NETWORK -> "いまは通信ができません。"
    }
    return reason + "$subject は${compass(azDeg)}の空、高度 ${altDeg.toInt()} 度あたりに出ています。"
}

/** 方位角[度]を 16 方位の日本語に。読み上げるので「南南西」まで刻む */
fun compass(azDeg: Double): String {
    return cardinalDirection16(azDeg)
}
