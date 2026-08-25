package jp.jig.glasses.sample.kmp.glass

import jp.jig.glasses.sample.kmp.catalog.StarCatalog
import jp.jig.glasses.sample.kmp.sky.Look
import jp.jig.glasses.sample.kmp.sky.ObservationDefaults
import jp.jig.glasses.sample.kmp.sky.Site
import org.junit.Test
import java.io.File

/**
 * 視野に入る固有名つきの星（[StarMapRenderer.visibleNamedStars]）。
 *
 * 星表を頭から走る代わりに固有名の側から索引で引くようにしたので、
 * **答えが変わっていないこと**を焼き付けておく（解説と声の質問がこの並びを読む）。
 */
class VisibleNamedStarsTest {

    private val dataDir: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "data") }
        .first { File(it, "stars.json").exists() }

    private val site: Site = ObservationDefaults.site
    private val epoch = 1767268800000L

    @Test
    fun `視線ごとの並びを焼き付ける`() {
        val renderer = StarMapRenderer(StarCatalog.parse { name -> File(dataDir, name).readText() })
        val lines = buildString {
            for (az in 0 until 360 step 45) {
                for (alt in 10..70 step 20) {
                    val names = renderer
                        .visibleNamedStars(site, epoch, Look(az.toDouble(), alt.toDouble()), fovDeg = 35.0, max = 5)
                        .joinToString(",") { it.nameJa }
                    append(az).append('/').append(alt).append('=').append(names).append('\n')
                }
            }
        }
        org.junit.Assert.assertEquals(EXPECTED.trim(), lines.trim())
    }

    private companion object {
        /** 索引引きへ変える前の実装が返していた並び（2026-01-01 21:00 JST・鯖江） */
        const val EXPECTED = """0/10=
0/30=
0/50=
0/70=カペラ
45/10=
45/30=
45/50=
45/70=カペラ
90/10=レグルス
90/30=ポルックス,プロキオン,カストル
90/50=カストル,ポルックス
90/70=カペラ
135/10=アダーラ,シリウス
135/30=シリウス,リゲル
135/50=ベテルギウス,リゲル
135/70=アルデバラン
180/10=
180/30=
180/50=
180/70=アルデバラン
225/10=
225/30=
225/50=
225/70=
270/10=
270/30=
270/50=
270/70=
315/10=デネブ
315/30=デネブ
315/50=
315/70="""
    }
}
