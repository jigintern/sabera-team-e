package jp.jig.glasses.sample.kmp.sky

// 空と軌道の両方で使う物理定数の正本。同じ値をパッケージごとに書くと、
// 片方だけ直したときに検算が合わなくなる

/** 地球の赤道半径[km]（WGS84 の長半径） */
const val EARTH_EQUATORIAL_RADIUS_KM = 6378.137

/** 1 天文単位[km] */
const val AU_KM = 149_597_870.7
