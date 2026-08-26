package jp.jig.glasses.sample.kmp.satellite

import jp.jig.glasses.sample.kmp.sky.EARTH_EQUATORIAL_RADIUS_KM
import jp.jig.glasses.sample.kmp.sky.RAD
import jp.jig.glasses.sample.kmp.sky.sunPosition
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** 影の判定は赤道半径で少し大きめに見る */
private const val EARTH_RADIUS_KM = EARTH_EQUATORIAL_RADIUS_KM

/**
 * 衛星に日が当たっているか。
 *
 * 当たっていなければ**肉眼では絶対に見えない**（衛星は自分で光らないので）。
 * 円柱の影で判定する。本影・半影を分けるほどの精度は要らない。
 */
fun isSunlit(state: TemeState, epochMillis: Long): Boolean {
    val sun = sunPosition(epochMillis)
    // 太陽の方向（赤道座標 → 直交座標）。TEME との差は秒角なので影の判定には効かない
    val ra = sun.raDeg * RAD
    val dec = sun.decDeg * RAD
    val sx = cos(dec) * cos(ra)
    val sy = cos(dec) * sin(ra)
    val sz = sin(dec)

    // 太陽と同じ側にいるなら必ず日が当たっている
    val alongSun = state.x * sx + state.y * sy + state.z * sz
    if (alongSun > 0.0) return true

    // 影の側にいるなら、地球の影の柱から外れているかを見る
    val px = state.x - alongSun * sx
    val py = state.y - alongSun * sy
    val pz = state.z - alongSun * sz
    return sqrt(px * px + py * py + pz * pz) > EARTH_RADIUS_KM
}
