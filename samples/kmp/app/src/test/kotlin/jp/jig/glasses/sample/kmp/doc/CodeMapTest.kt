package jp.jig.glasses.sample.kmp.doc

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **ドキュメントが指しているコードが実在するか。**
 *
 * `50_code-map.md` は「どこに何のコードがあるか」の正本で、[CONTEXT.md](../../../../../../../../CONTEXT.md) は
 * 言葉とコードの対応表。**どちらも人が読む地図なので、指し先が消えても壊れたことに気づけない。**
 *
 * 実際に 4 件残っていた（2026-09-02 に発見）。
 *
 * | 書いてあったもの | 実体 |
 * |---|---|
 * | `sky/Timelapse.kt` / `glass/TimelapseSender.kt` | 撤去済み（`aee6ba5`）。`01_status.md` は「撤去した」と書いていた |
 * | `narration/AskGuard.kt` | `openai/AskGuard.kt` へ移動（`4ee861f`） |
 * | `support/BundledData.kt` | `glass/BundledData.kt` へ移動（`ee9d8d8`） |
 * | `SatelliteScene.Pass` | 計算ごと撤去済み |
 *
 * どれも**撤去・移動のコミットが docs を追随させ損ねた**もので、
 * 同じ文書の別の行が正しいパスを書いている「自己矛盾」まで起きていた。
 *
 * **`app/build.gradle.kts` の `inputs.files(...)` と対で効く。** docs は `data/` と違って
 * Gradle の入力に入っていないので、宣言しないと**docs だけ戻したときに UP-TO-DATE で
 * 緑のまま素通りする**（`72_pitfalls.md`「リポジトリのファイルを読むテストを入力宣言せずに置く」）。
 *
 * **見るのはこの 2 つの文書だけ。** ほかの文書は「撤去した」経緯そのものを説明するために
 * 消えたファイル名を挙げていることがある（`74_alignment-accuracy.md` など）ので、広げない。
 */
class CodeMapTest {

    /** テストの作業ディレクトリは app/。別の場所から実行してもリポジトリを見つける */
    private val repoRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "samples/kmp/app").isDirectory }

    private val sourceRoot = File(repoRoot, "samples/kmp/app/src")

    /** `.kt` の実ファイル。パスの末尾一致で引くので、パッケージの並びを二重に書かない */
    private val sourcePaths: List<String> = sourceRoot.walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .map { it.path.replace(File.separatorChar, '/') }
        .toList()

    /** main 配下のソースを 1 本につないだもの。識別子が生き残っているかだけを見る */
    private val mainSources: String = sourceRoot.walkTopDown()
        .filter { it.isFile && it.extension == "kt" && "/src/main/" in it.path.replace(File.separatorChar, '/') }
        .joinToString("\n") { it.readText() }

    /** バックティックで囲まれた `pkg/Name.kt` 形（`/` を含み `.kt` で終わるもの） */
    private val ktPath = Regex("`([A-Za-z0-9_./-]+/[A-Za-z0-9_]+\\.kt)`")

    /** 表の最後の桁（`CONTEXT.md` の「コード」列） */
    private val lastCell = Regex("^\\|.*\\|([^|]*)\\|\\s*$")

    private fun exists(token: String): Boolean =
        File(repoRoot, token).isFile || sourcePaths.any { it.endsWith("/$token") }

    /**
     * `50_code-map.md` が挙げる `.kt` は全部実在する。
     *
     * **壊れている行は全部集めてから 1 回で落とす。** ループの中で assert すると
     * 最初の 1 個で止まって広がりが見えない（`72_pitfalls.md`）。
     */
    @Test
    fun `コードマップが指すファイルは実在する`() {
        val doc = File(repoRoot, "docs/team-e/50_code-map.md")
        assertTrue("50_code-map.md が見つからない: $doc", doc.isFile)

        val broken = mutableListOf<String>()
        for ((index, line) in doc.readLines().withIndex()) {
            for (match in ktPath.findAll(line)) {
                val token = match.groupValues[1]
                if (!exists(token)) broken += "50_code-map.md:${index + 1} `$token`"
            }
        }

        assertTrue(
            "ドキュメントが指しているファイルが無い（撤去したなら記述も消す・移動したならパスを直す）:\n" +
                broken.joinToString("\n"),
            broken.isEmpty(),
        )
    }

    /**
     * `CONTEXT.md` の「コード」列に出てくる名前は、いまもソースにある。
     *
     * `X.y` は `X` と `y` を別々に見る（`SatelliteScene.Pass` は `Pass` が消えていた）。
     * 撤去した言葉を用語集に残したいときは、コード列を `—` にする。
     */
    @Test
    fun `用語集が指す名前はソースにある`() {
        val doc = File(repoRoot, "CONTEXT.md")
        assertTrue("CONTEXT.md が見つからない: $doc", doc.isFile)

        val broken = mutableListOf<String>()
        for ((index, line) in doc.readLines().withIndex()) {
            val cell = lastCell.find(line)?.groupValues?.get(1) ?: continue
            for (match in Regex("`([^`]+)`").findAll(cell)) {
                val token = match.groupValues[1].trim()
                if (token.endsWith(".kt")) {
                    if (!exists(token)) broken += "CONTEXT.md:${index + 1} `$token`"
                    continue
                }
                // `ObservationMode.Simulation` は両方の名前が生きていて初めて意味がある
                for (part in token.split('.').filter { it.isNotBlank() }) {
                    val word = Regex("\\b" + Regex.escape(part) + "\\b")
                    if (!word.containsMatchIn(mainSources)) {
                        broken += "CONTEXT.md:${index + 1} `$token` の `$part`"
                    }
                }
            }
        }

        assertTrue(
            "用語集が指している名前がソースに無い（撤去したならコード列を `—` にする）:\n" +
                broken.joinToString("\n"),
            broken.isEmpty(),
        )
    }
}
