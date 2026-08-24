package jp.jig.glasses.sample.kmp.support

import android.content.Context
import jp.jig.glasses.sample.kmp.catalog.ConstellationLore
import jp.jig.glasses.sample.kmp.catalog.MeteorShowers
import jp.jig.glasses.sample.kmp.catalog.StarCatalog
import jp.jig.glasses.sample.kmp.glass.StarMapRenderer
import jp.jig.glasses.sample.kmp.satellite.SatelliteScene
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 同梱データを**アプリの生きている間 1 回だけ**読む。
 *
 * 読む対象は起動中ずっと変わらない（`data/` は生成物で、実行中に差し替わらない）のに、
 * **観測画面に入るたび読み直していた**。星表の JSON・TLE 10,748 機・88 星座の解説文が
 * 毎回で、加えて [StarMapRenderer] は星座ごとの最輝星を全星と突き合わせて作るので、
 * 画面を作り直すたびにその総当たりまでやり直していた。
 *
 * **画面は 1 回きりの入り口ではない。** 「方位を合わせ直す」で観測画面は捨てられ、
 * 瞬断から戻っても作り直される。夜の屋外で方位を合わせ直すのは珍しくないので、
 * そのたびに数秒黙るのは体感でいちばん重い。
 *
 * 失敗は覚えない。星表が読めなかったときに空の結果を握ると、
 * **以降どれだけ入り直しても二度と星が出ない**（読み直せば直るかもしれないものを閉じてしまう）。
 */
object BundledData {

    private val starLock = Mutex()
    private val loreLock = Mutex()
    private val satelliteLock = Mutex()
    private val showerLock = Mutex()

    @Volatile
    private var stars: StarCatalog? = null

    @Volatile
    private var starRenderer: StarMapRenderer? = null

    @Volatile
    private var lore: ConstellationLore? = null

    @Volatile
    private var satellites: SatelliteScene? = null

    @Volatile
    private var showers: MeteorShowers? = null

    /**
     * 星図を描くもの。**星表を読むのと同じ手間で作れるので組で持つ。**
     *
     * 中の覚え書き（最輝星・HIP 索引・歳差）はどれも入れ直しても同じ値になるので、
     * 1 つを使い回してよい。
     */
    suspend fun renderer(context: Context): StarMapRenderer {
        starRenderer?.let { return it }
        return starLock.withLock {
            starRenderer?.let { return@withLock it }
            val catalog = stars ?: withContext(Dispatchers.IO) { StarCatalog.load(context) }
            stars = catalog
            StarMapRenderer(catalog).also { starRenderer = it }
        }
    }

    suspend fun lore(context: Context): ConstellationLore {
        lore?.let { return it }
        return loreLock.withLock {
            lore ?: withContext(Dispatchers.IO) { ConstellationLore.load(context) }.also { lore = it }
        }
    }

    /** 流星群。日付で引くだけなので小さいが、読むのはやはり 1 回でよい */
    suspend fun showers(context: Context): MeteorShowers {
        showers?.let { return it }
        return showerLock.withLock {
            showers ?: withContext(Dispatchers.IO) { MeteorShowers.load(context) }.also { showers = it }
        }
    }

    suspend fun satellites(context: Context): SatelliteScene {
        satellites?.let { return it }
        return satelliteLock.withLock {
            satellites ?: withContext(Dispatchers.IO) { SatelliteScene.load(context) }.also {
                // 読めていない（assets に TLE が無い）ときも覚える。**入り直しても直らない**ので、
                // 10,748 機ぶんの読み込みを空振りで繰り返す意味がない
                satellites = it
            }
        }
    }
}
