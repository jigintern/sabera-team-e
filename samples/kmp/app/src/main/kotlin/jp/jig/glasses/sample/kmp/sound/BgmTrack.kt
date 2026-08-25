package jp.jig.glasses.sample.kmp.sound

import androidx.annotation.RawRes
import jp.jig.glasses.sample.kmp.R
import jp.jig.glasses.sample.kmp.sky.SkyDarkness
import kotlin.random.Random

/**
 * 曲を選ぶ単位。**時計ではなく、太陽高度とガイドの有無で決まる。**
 *
 * 時計で切り替えないのは、同じ 19 時が夏は明るくて冬は暗いから。
 * 太陽高度なら季節と緯度を勝手に吸収する（[SkyDarkness] は星図と衛星で既に使っている）。
 *
 * **場面と曲を分けてある。** 以前は「薄暮の曲」「夜の曲」が 1 曲ずつで、enum が
 * 場面と曲を兼ねていた。**120 秒を延々繰り返すので長い観測で飽きる**（#69）ため、
 * 1 つの場面が複数の曲を持てる形にした。
 */
enum class BgmScene(val label: String) {

    /** 日没前後から薄明まで。明るさが移り変わる時間に合う曲 */
    TWILIGHT("薄暮"),

    /** 天文薄明から先。広くて何も無い感じの曲 */
    NIGHT("夜"),

    /**
     * ガイドを再生している間（[jp.jig.glasses.sample.kmp.guide.GuideProgress]）。
     *
     * **喋っている時間が長く、その間ずっと 28% に絞られている**ので、
     * 絞っても存在が消えない低音を持つ曲を当てる。
     */
    GUIDE("ガイド"),
    ;

    /** この場面で鳴らしてよい曲。**空にしない**（空だと鳴らす曲が無くなる。`BgmTrackTest` が検査） */
    val tracks: List<BgmTrack> get() = BgmTrack.entries.filter { this in it.scenes }

    companion object {
        /** 空の暗さから場面を決める。ガイド中かどうかは呼ぶ側が上書きする */
        fun of(darkness: SkyDarkness): BgmScene =
            if (darkness == SkyDarkness.NIGHT) NIGHT else TWILIGHT
    }
}

/**
 * 同梱している曲。
 *
 * **どれも 120 秒。末尾を先頭にクロスフェードして焼いてある**ので、繰り返しても継ぎ目が出ない。
 * 作り方は `tools/build-bgm.sh`（切り出し位置・音量の揃え方・エンコード条件）。
 *
 * 出処は incompetech.com（Kevin MacLeod）の **CC BY 4.0**。
 * **帰属の表示がライセンスの条件**なので、[credit] を設定パネルに出す。
 * ここに曲を足せば表示も増えるので、**文言を別に書き直さなくてよい**。
 */
enum class BgmTrack(
    @param:RawRes val res: Int,
    /** 原題。**帰属に使うので訳さない** */
    val title: String,
    /** 選ぶときの手がかり。曲名だけでは、どれを選べばよいか分からない */
    val mood: String,
    val scenes: Set<BgmScene>,
) {
    SILVER_BLUE_LIGHT(
        R.raw.bgm_silver_blue_light,
        "Silver Blue Light",
        "静かで明るい",
        setOf(BgmScene.TWILIGHT),
    ),
    LIGHT_AWASH(
        R.raw.bgm_light_awash,
        "Light Awash",
        "ゆっくり満ちる",
        setOf(BgmScene.TWILIGHT),
    ),
    FLUIDSCAPE(
        R.raw.bgm_fluidscape,
        "Fluidscape",
        "広くて何も無い",
        setOf(BgmScene.NIGHT),
    ),
    AMBIMENT(
        R.raw.bgm_ambiment,
        "Ambiment",
        "遠くでピアノが鳴る",
        setOf(BgmScene.NIGHT),
    ),
    DRONE_IN_D(
        R.raw.bgm_drone_in_d,
        "Drone in D",
        "低音が途切れない",
        setOf(BgmScene.GUIDE),
    ),
    CONCENTRATION(
        R.raw.bgm_concentration,
        "Concentration",
        "言葉の邪魔をしない",
        setOf(BgmScene.GUIDE),
    ),
    ;

    companion object {

        /** CC BY 4.0 は帰属の表示が条件。**曲を足したら勝手に伸びる**ので書き直さなくてよい */
        val credit: String =
            entries.joinToString("・") { it.title } +
                " by Kevin MacLeod (incompetech.com) CC BY 4.0"

        /**
         * 覚えてある名前から曲を引く。
         *
         * **知らない名前は null**（＝おまかせ）にする。曲を差し替えたあとに
         * 古い名前が残っていても落ちないようにするため。
         */
        fun byName(name: String?): BgmTrack? = entries.firstOrNull { it.name == name }
    }
}

/**
 * 次に鳴らす曲を選ぶ。
 *
 * `MediaPlayer` を触る [Bgm] は JVM のテストで動かせないので、**選ぶところだけを分けてある**。
 */
object BgmPlaylist {

    /**
     * [scene] の曲から 1 つ選ぶ。**いま鳴っている曲は続けて選ばない**（同じ曲が 2 回続くと
     * 曲を渡り歩いている意味が無くなる）。ただし場面に 1 曲しか無ければそれを返す。
     *
     * 曲が 1 つも無い場面では null。**呼ぶ側はいまの曲を続ける**（黙るよりよい）。
     */
    fun next(scene: BgmScene, playing: BgmTrack?, random: Random = Random): BgmTrack? {
        val tracks = scene.tracks
        if (tracks.isEmpty()) return null
        val others = tracks.filter { it != playing }
        return (others.ifEmpty { tracks }).random(random)
    }
}
