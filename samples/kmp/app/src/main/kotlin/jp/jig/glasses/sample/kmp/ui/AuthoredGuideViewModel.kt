package jp.jig.glasses.sample.kmp.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import jp.jig.glasses.sample.kmp.guide.AuthoredGuide
import jp.jig.glasses.sample.kmp.guide.GuideAsk
import jp.jig.glasses.sample.kmp.guide.GuideSchedule
import jp.jig.glasses.sample.kmp.guide.GuideStep
import jp.jig.glasses.sample.kmp.guide.GuideStore
import jp.jig.glasses.sample.kmp.guide.StarGuide
import jp.jig.glasses.sample.kmp.openai.GuideChatTurn
import jp.jig.glasses.sample.kmp.sky.GuidanceTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** AI の返事。**通信は画面側が包む**ので、ここは結果の形だけ知っていればよい */
internal class GuideChatReply(
    val reply: String,
    val steps: List<GuideStep>?,
    val dropped: Int,
)

/**
 * 詳細ガイド（toB）の編集の状態。
 *
 * 星表・測位・通信は Android に寄るので、画面側が [targetsAtFactory] と [askAi] で包んで渡す。
 * **「候補外の名前は端末が先に断る」判断はここにある**（通信させないための関所）。
 */
internal class AuthoredGuideViewModel(
    initialDraft: AuthoredGuide,
    private val store: GuideStore,
    /** その日その時間の空を返す工場。**候補も判定もここから出す**（1 か所で計算する） */
    private val targetsAtFactory: suspend () -> (Long) -> List<GuidanceTarget>,
    /** 同梱の解説。段を足すときの初期値（白紙から書かせない） */
    private val loreOf: suspend (String) -> String,
    /** AI に相談する。**候補外を弾いたあとにだけ呼ばれる** */
    private val askAi: suspend (String, List<GuidanceTarget>, List<GuideStep>, List<GuideChatTurn>) -> GuideChatReply?,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val worker: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

    var draft by mutableStateOf(initialDraft)

    var candidates by mutableStateOf<List<GuidanceTarget>>(emptyList())
        private set

    var loadingCandidates by mutableStateOf(false)
        private set

    var chat by mutableStateOf<List<GuideChatTurn>>(emptyList())
        private set

    var chatInput by mutableStateOf("")

    var chatBusy by mutableStateOf(false)
        private set

    var notice by mutableStateOf<String?>(null)

    /** 通信できるか。**圏外なら相談の口を出さない**（押しても何も起きないより先に言う） */
    var online by mutableStateOf(false)

    fun reloadCandidates() {
        loadingCandidates = true
        viewModelScope.launch {
            candidates = runCatching {
                val factory = targetsAtFactory()
                withContext(worker) {
                    GuideSchedule.candidates(draft.window, factory)
                        .sortedByDescending { it.aim.altDeg }
                }
            }.getOrElse {
                notice = "その日の空を調べられませんでした: ${it.message}"
                emptyList()
            }
            loadingCandidates = false
        }
    }

    /** 段を足す。**同梱の解説を初期値にする**（白紙から書かせない） */
    fun addTarget(target: GuidanceTarget) {
        viewModelScope.launch {
            val body = runCatching { loreOf(target.nameJa) }.getOrNull().orEmpty()
            draft = draft.plus(
                GuideStep(targetName = target.nameJa, kind = target.kind, intro = "", body = body),
            )
        }
    }

    fun toggleTarget(target: GuidanceTarget) {
        val at = draft.steps.indexOfFirst { it.targetName == target.nameJa }
        if (at >= 0) draft = draft.removedAt(at) else addTarget(target)
    }

    /** 日時を選び直したあと。候補は空ごと変わるので取り直す */
    fun onWindowStartPicked(startMillis: Long) {
        draft = draft.copy(window = draft.window.copy(startMillis = startMillis))
        reloadCandidates()
    }

    /**
     * AI へ渡す前に、端末が先に断る。
     *
     * **打たれた文に候補外の名前があれば、通信せずに返す。** 渡してしまうと AI が
     * 気を利かせて台本に載せ、再生で全部飛んで「何も起きないガイド」になる。
     *
     * [formatTime] は「いつなら出ているか」の表示（端末の書式なので画面側が渡す）。
     */
    fun send(formatTime: (Long) -> String) {
        val instruction = GuideAsk.sanitizeInput(chatInput) ?: return
        if (chatBusy) return
        chatBusy = true
        chatInput = ""
        viewModelScope.launch {
            try {
                val factory = targetsAtFactory()
                val known = withContext(worker) { factory(draft.window.startMillis).map { it.nameJa } }
                val allowed = candidates.map { it.nameJa }.toSet()
                val asked = GuideAsk.namesIn(instruction, known)
                val blocked = asked.filterNot { it in allowed }
                if (blocked.isNotEmpty()) {
                    val name = blocked.first()
                    val from = withContext(worker) {
                        GuideSchedule.availableFrom(name, null, draft.window, factory)
                    }
                    chat = chat + GuideChatTurn(true, instruction) +
                        GuideChatTurn(false, GuideAsk.rejection(name, from?.let(formatTime)))
                    return@launch
                }
                val reply = withContext(io) { askAi(instruction, candidates, draft.steps, chat) }
                if (reply == null) {
                    chat = chat + GuideChatTurn(true, instruction) +
                        GuideChatTurn(false, "うまく答えられませんでした。もう一度お願いします。")
                    return@launch
                }
                reply.steps?.let { draft = draft.copy(steps = it) }
                val dropped = if (reply.dropped > 0) "（${reply.dropped} 件は空に無いので外しました）" else ""
                chat = chat + GuideChatTurn(true, instruction) + GuideChatTurn(false, reply.reply + dropped)
            } catch (cancelled: CancellationException) {
                // 画面を離れたときの中断。通信の失敗ではないので、履歴に断りを積まずに上へ流す
                throw cancelled
            } catch (error: Throwable) {
                // **断って続ける。** 通信は落ちるものなので、落ちても台本は残る
                chat = chat + GuideChatTurn(true, instruction) +
                    GuideChatTurn(false, "通信できませんでした: ${error.message}")
            } finally {
                chatBusy = false
            }
        }
    }

    fun save(then: ((StarGuide) -> Unit)? = null) {
        val guide = draft.toGuide()
        viewModelScope.launch {
            val saved = withContext(io) { store.save(guide) }
            if (!saved) {
                notice = "台本を保存できませんでした"
                return@launch
            }
            notice = "「${guide.title}」を保存しました"
            then?.invoke(guide)
        }
    }
}
