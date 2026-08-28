package jp.jig.glasses.sample.kmp.guide

import jp.jig.glasses.sample.kmp.sky.GuidanceTargetKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * 台本の置き場。
 *
 * **受け取った台本の id はそのままファイル名になる**ので、`../` を含む id が来ると
 * `guides/` の外へ書けてしまい、保存は成功したのに一覧に出ない状態になった。
 * 「[GuideStore.save] が true を返したら [GuideStore.list] で拾える」を固定する。
 *
 * 断ったときに [android.util.Log] へ理由を残すので、素の JVM では動かない（Robolectric で回す）。
 */
@RunWith(RobolectricTestRunner::class)
class GuideStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun guide(id: String) = StarGuide(
        id = id,
        title = "秋のツアー",
        summary = "",
        createdAtMillis = 1_789_000_000_000L,
        origin = GuideOrigin.RECEIVED,
        steps = listOf(
            GuideStep(
                targetName = "はくちょう座",
                kind = GuidanceTargetKind.CONSTELLATION,
                intro = "",
                body = "夏の大三角の一つです。",
            ),
        ),
    )

    @Test
    fun `普通の id は保存して読み直せる`() {
        val root = temp.newFolder("files")
        val store = GuideStore(File(root, "guides"))

        assertTrue(store.save(guide("guide-1789000000000")))
        assertEquals(listOf("guide-1789000000000"), store.list().map { it.id })
    }

    @Test
    fun `親をさかのぼる id は保存しない`() {
        val root = temp.newFolder("files")
        val store = GuideStore(File(root, "guides"))

        assertFalse(store.save(guide("../autumn-tour")))
        // **guides の外に書き残さない。** 以前はここに書けてしまい、list が拾えなかった
        assertFalse(File(root, "autumn-tour.json").exists())
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun `区切りを含む id は保存しない`() {
        val root = temp.newFolder("files")
        val store = GuideStore(File(root, "guides"))

        assertFalse(store.save(guide("tours/autumn")))
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun `保存できた id は必ず一覧に出る`() {
        val root = temp.newFolder("files")
        val store = GuideStore(File(root, "guides"))
        val ids = listOf("guide-1", "guide-2", "../escape", "a/b", "guide-3")

        val saved = ids.filter { store.save(guide(it)) }
        assertEquals(saved.sorted(), store.list().map { it.id }.sorted())
    }
}
