package jp.jig.glasses.sample.kmp.ui

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import jp.jig.glasses.sample.kmp.guide.GuideDraft
import jp.jig.glasses.sample.kmp.guide.GuideStore
import jp.jig.glasses.sample.kmp.guide.GuideTheme
import jp.jig.glasses.sample.kmp.guide.StarGuide
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ガイド一覧の状態と、台本を 1 本作る進行。
 *
 * 下書きの材料（星表・測位・API キー）は Android に寄るので、[makeDraft] として
 * 画面側が包んで渡す。**進行と文言はここにあり、JVM テストで固定できる。**
 */
internal class GuideListViewModel(
    private val store: GuideStore,
    private val makeDraft: suspend (GuideTheme) -> GuideDraft?,
    /** 保存と一覧の読み書き先。テストでは仮想時間のディスパッチャを渡す */
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    var guides by mutableStateOf<List<StarGuide>>(emptyList())
        private set

    var making by mutableStateOf(false)
        private set

    /** 直前に何が起きたか。**作ったのに何も言わないと、できたのか分からない** */
    var notice by mutableStateOf<String?>(null)
        private set

    /** 詳細エディタは畳んでおく。**ふだんは目に入らないが、探せば見つかる** */
    var advanced by mutableStateOf(false)

    /** 選んであるテーマ。**選ぶと作るを分けた**ので、押すまで作らない */
    var theme by mutableStateOf(GuideTheme.entries.first())

    private var makeJob: Job? = null

    private companion object {
        const val TAG = "GuideList"
    }

    fun reload() {
        viewModelScope.launch { guides = withContext(io) { store.list() } }
    }

    fun make(theme: GuideTheme) {
        if (making) return
        making = true
        notice = null
        makeJob = viewModelScope.launch {
            try {
                val draft = makeDraft(theme)
                if (draft == null) {
                    notice = "いまの空には案内できる星座がありません。日が暮れてから試してください"
                    return@launch
                }
                val saved = withContext(io) { store.save(draft.guide) }
                // **どう作ったか（AI か同梱か）は言わない。** 使う人が決めるのは
                // 「この台本を使うか」だけで、作り方を知っても選び方は変わらない
                notice = if (saved) {
                    "「${draft.guide.title}」を作りました"
                } else {
                    "台本を保存できませんでした"
                }
                reload()
            } catch (error: Throwable) {
                // **落ちるより断って続ける**（05_app-flow.md）。星表が読めないこともある
                Log.w(TAG, "台本を作れなかった", error)
                notice = "台本を作れませんでした。もう一度試してください"
            } finally {
                making = false
            }
        }
    }

    fun delete(guide: StarGuide) {
        store.delete(guide.id)
        notice = "「${guide.title}」を消しました"
        reload()
    }

    /**
     * 画面を出るときに呼ぶ。作りかけは打ち切り、表示も真っさらへ
     * （remember に載っていたころは画面を出ると必ずこうなっていた）。
     */
    fun leave() {
        makeJob?.cancel()
        makeJob = null
        making = false
        notice = null
        advanced = false
    }
}
