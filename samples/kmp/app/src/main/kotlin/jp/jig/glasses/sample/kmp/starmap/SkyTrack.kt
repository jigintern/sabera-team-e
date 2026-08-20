package jp.jig.glasses.sample.kmp.starmap

/**
 * 空を横切るものの軌跡。人工衛星を星図に重ねるために使う。
 *
 * 星座と違って**動く**ので、描き分けが要る。
 * - **軌跡は画像に焼く。** この先 1〜2 分の道筋は動かない情報なので、星図と一緒に送れる
 * - **いまの位置はテキストで出す。** 画像を送り直さずに動かせる（1 パケット・数十 ms）
 *
 * 衛星の計算そのものは `satellite` パッケージにある。ここは描画の都合だけを持つ。
 */
class SkyTrack(
    val name: String,
    /** 軌跡の点。`[方位°, 高度°]` の並び */
    val points: List<DoubleArray>,
    /** いまの位置 */
    val nowAzDeg: Double,
    val nowAltDeg: Double,
    /** 日が当たっているか。当たっていなければ肉眼では見えない */
    val sunlit: Boolean,
    /** 名前を出すか。スターリンクは数が多いので軌跡だけにする */
    val labelled: Boolean,
)
