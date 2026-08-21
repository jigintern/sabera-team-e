package jp.jig.glasses.sample.kmp.ai

import android.util.Log
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
    /** 星図の PNG（Base64）。作れなければ null で、そのときは文字だけで頼む */
    val pngBase64: String?,
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
            pngBase64 = input.pngBase64,
        )

        val explanation = try {
            try {
                client.explain(ask)
            } catch (e: EmptyReplyException) {
                // 空応答はモデル側の都合で起きるので、1 回だけ頼み直す。
                // API エラーと通信断では繰り返さない（つながらないものを待たせると無言が倍になる）
                log("応答が空だったので 1 回だけ頼み直す（${e.message}）", true)
                client.explain(ask)
            }
        } catch (e: CancellationException) {
            // 停止トグルで畳まれた場合。失敗ではないので、そのまま上へ流す
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "解説の生成に失敗", e)
            val kind = classifyFailure(e)
            val fallback = fallbackLine(kind, constellation, input.azDeg, input.altDeg)
            speaker.add(fallback)
            _state.value = NarrationState(NarrationPhase.FAILED, "$opening$fallback", constellation)
            // 生のメッセージを必ず載せる。実機で何が起きたかはここだけが頼り
            log("解説を作れない[$kind]: ${e.message}", true)
            return
        }

        speaker.add(explanation)
        _state.value = NarrationState(NarrationPhase.SPEAKING, explanation, constellation)
        log("解説を読み上げ中（${explanation.length} 文字）", false)
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
