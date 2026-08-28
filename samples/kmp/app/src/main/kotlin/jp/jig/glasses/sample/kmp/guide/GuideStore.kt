package jp.jig.glasses.sample.kmp.guide

import android.content.Context
import android.util.Log
import java.io.File

/**
 * 台本をこの端末に残す。
 *
 * [jp.jig.glasses.sample.kmp.support.NightRecord] や
 * [jp.jig.glasses.sample.kmp.support.AskHistory] と違って**ファイルに書き出す**。
 * ガイドは「事前に作っておいて、あとで空の下で使う」ものなので、
 * アプリを閉じたら消えるのでは役に立たない。
 *
 * **`data/` には置かない。** あそこは生成物で、次の生成で消える（AGENTS.md）。
 *
 * toB の台本（手で書いたもの）も同じ形で置けば読める。**入出力の口はここだけ。**
 */
class GuideStore(private val directory: File) {

    /** 新しいものが先。一覧はそのまま画面に出せる並びで返す */
    fun list(): List<StarGuide> {
        val files = directory.listFiles { file -> file.isFile && file.name.endsWith(SUFFIX) }
            ?: return emptyList()
        // **壊れた 1 本で一覧ごと開けなくならない。** 読めないものは黙って外す
        return files.mapNotNull { file ->
            runCatching { StarGuideJson.decode(file.readText(Charsets.UTF_8)) }
                .onFailure { Log.w(TAG, "台本が読めない: ${file.name}", it) }
                .getOrNull()
        }.sortedByDescending { it.createdAtMillis }
    }

    /** 書けたら true。書けなくてもアプリは止めない（作り直せばよい） */
    fun save(guide: StarGuide): Boolean = runCatching {
        directory.mkdirs()
        val file = fileFor(guide.id) ?: return false
        file.writeText(StarGuideJson.encode(guide), Charsets.UTF_8)
        true
    }.getOrElse {
        Log.w(TAG, "台本を保存できない: ${guide.id}", it)
        false
    }

    fun delete(id: String): Boolean = runCatching { fileFor(id)?.delete() ?: false }
        .getOrDefault(false)

    /**
     * この id で書いてよいファイル。**[directory] の直下に収まらなければ null。**
     *
     * 受け取った台本の id は QR やファイルの JSON から**そのまま**来る。`../autumn` のような
     * id をそのまま繋ぐと `guides/` の外へ書けてしまい、[save] は true を返すのに
     * [list] は拾えない（保存できたと言って消える）。断る理由を人へ出すのは
     * [GuideCodec] の受け取り検査の役目で、ここは最後の砦。
     */
    private fun fileFor(id: String): File? {
        val file = File(directory, id + SUFFIX)
        val inside = runCatching { file.canonicalFile.parentFile == directory.canonicalFile }
            .getOrDefault(false)
        if (!inside) Log.w(TAG, "台本の id が保存名に使えない: $id")
        return file.takeIf { inside }
    }

    companion object {
        private const val SUFFIX = ".json"
        private const val TAG = "GuideStore"

        fun of(context: Context): GuideStore = GuideStore(File(context.filesDir, "guides"))

        /**
         * 台本の id。**作った時刻そのもの**なので、ファイル名の並びが作った順になる。
         *
         * 端末の中だけで使う名前で、人が押して作るものが同じミリ秒に 2 つできることはない。
         */
        fun newId(atMillis: Long): String = "guide-$atMillis"
    }
}
