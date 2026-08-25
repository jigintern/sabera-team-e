package jp.jig.glasses.sample.kmp.support

// 時間の換算はここが正本。生の 60_000 などが式に混ざると、単位の取り違えを目で追えない

/** 1 分[ms] */
const val MINUTE_MILLIS = 60_000L

/** 1 時間[ms] */
const val HOUR_MILLIS = 3_600_000L

/** 1 日[ms] */
const val DAY_MILLIS = 86_400_000L

/** 1 ミリ秒[ns]。System.nanoTime の差を ms に落とすときに使う */
const val NANOS_PER_MILLI = 1_000_000L
