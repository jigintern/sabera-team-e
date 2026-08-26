package jp.jig.glasses.sample.kmp.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import jp.jig.glasses.sample.kmp.guide.GuideImport
import jp.jig.glasses.sample.kmp.guide.GuideStore
import jp.jig.glasses.sample.kmp.guide.StarGuide
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 台本の受け取りの状態。**読み込む前に中身を見せる**流れ（pending → 保存）をここで持つ。
 * QR・ファイル・カメラ権限の入口は Android の仕事なので画面側に残る。
 */
internal class GuideImportViewModel(
    private val store: GuideStore,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    /** 中身を見せている台本。**まだ保存していない** */
    var pending by mutableStateOf<StarGuide?>(null)
        private set

    /** 受け取れなかった理由 */
    var rejected by mutableStateOf<String?>(null)
        private set

    var scanning by mutableStateOf(false)

    /** 解読の結果を反映する。**受理と拒否で片方だけが残る**（両方出ると読む人が迷う） */
    fun accept(result: GuideImport) {
        when (result) {
            is GuideImport.Ok -> {
                pending = result.guide
                rejected = null
            }

            is GuideImport.Rejected -> {
                pending = null
                rejected = result.reason
            }
        }
        scanning = false
    }

    /** カメラ権限の答え。断られたら**ファイルの口へ誘導**する（詰ませない） */
    fun onCameraPermission(granted: Boolean) {
        scanning = granted
        if (!granted) rejected = "カメラを使えないので、ファイルから読み込んでください"
    }

    /** 中身を確認した台本を保存する。[onImported] は保存できたときだけ呼ばれる */
    fun save(guide: StarGuide, onImported: (StarGuide) -> Unit) {
        viewModelScope.launch {
            val ok = withContext(io) { store.save(guide) }
            if (ok) onImported(guide) else rejected = "台本を保存できませんでした"
        }
    }

    /** ファイルの中身を読んで解読する。読み出しは Android（ContentResolver）側が [read] で包む */
    fun importFrom(read: suspend () -> GuideImport) {
        viewModelScope.launch {
            accept(withContext(io) { read() })
        }
    }

    /** 中身を見て「やめる」を押したとき。**断りの理由は消さない**（読んだ跡は残す） */
    fun cancelPending() {
        pending = null
    }

    /** 画面を出るときに呼ぶ。**入り直したら真っさら** */
    fun leave() {
        pending = null
        rejected = null
        scanning = false
    }
}
