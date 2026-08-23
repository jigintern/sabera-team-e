package jp.jig.glasses.sample.kmp.satellite

/**
 * いま空にいる人工衛星 1 機。星図と同じ座標系に乗せるために使う。
 *
 * **軌跡の線は描かない**（決定。`docs/team-e/satellite-drawing.md`）。線を引くと画面が線で埋まって
 * 「どれが衛星か」が読めなかったので、出すのは
 * **「大体どの辺にいるか」の点と、そのそばに置く輪郭**だけ。
 *
 * 星座と違って**動く**ので、描き分けは残る。
 * - **点と輪郭は画像に焼く。** 送り直しは首が止まってから
 * - **いまの位置と名前はテキストで出す。** 画像を送り直さずに動かせる（1 パケット・数十 ms）
 *
 * 衛星の計算そのものは `satellite` パッケージにある。ここは描画の都合だけを持つ。
 */
/**
 * 動きの見せ方。**「いつ」が無いと、点を見ても待てばいいのか分からない。**
 *
 * 最接近は「観測地からの距離がいちばん小さくなる時刻」。低軌道の機体はここで
 * いちばん高く・いちばん明るくなるので、**待つ価値があるかどうかがこれで決まる**。
 */
class SkyMotion(
    /** 最接近までの分。マイナスなら過ぎている。**分からなければ null** */
    val closestInMinutes: Double?,
    /** いま高度が上がっているか */
    val rising: Boolean,
    /** 静止軌道のようにほとんど動かないか。**最接近という考え方が当てはまらない** */
    val stationary: Boolean,
    /**
     * 30 秒後の位置[度]。**進行方向の矢印を描くために持つ。**
     *
     * 軌跡の線はやめたので、動いていることを見せる手段がこれしかない
     * （静止軌道はいまと同じ位置になるので、矢印は出ない）。
     */
    val nextAzDeg: Double? = null,
    val nextAltDeg: Double? = null,
)

class SkyTrack(
    val name: String,
    /** いまの位置 */
    val nowAzDeg: Double,
    val nowAltDeg: Double,
    /** 日が当たっているか。当たっていなければ肉眼では見えない */
    val sunlit: Boolean,
    /** 名前と輪郭を出すか。スターリンクは数が多いので点だけにする */
    val labelled: Boolean,
    /** 動きの見せ方。名前を出さない機体では計算しないので null */
    val motion: SkyMotion? = null,
)
