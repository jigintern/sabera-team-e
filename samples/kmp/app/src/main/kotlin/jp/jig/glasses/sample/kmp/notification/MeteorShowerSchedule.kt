package jp.jig.glasses.sample.kmp.notification

import jp.jig.glasses.sample.kmp.catalog.MeteorShowers
import java.time.ZonedDateTime

/**
 * 次にいつ何を知らせるか（#70）。
 *
 * **Android に触らない**ので、いまの時刻を渡して JVM テストできる。
 * 予約の実物（`AlarmManager`）は [MeteorShowerAlarm] が持つ。
 */
object MeteorShowerSchedule {

    /**
     * 通知を出す時刻。
     *
     * **日の入りに合わせない。** 合わせるには観測地が要るが、アプリが閉じている間は
     * 測位できない（`ACCESS_BACKGROUND_LOCATION` 無しでは背景の位置が返らない）。
     * 18 時なら冬でも「これから暗くなる」に間に合う。
     */
    const val NOTIFY_HOUR = 18

    /** 予約 1 件。[at] に鳴らして、[nightAt] の夜のことを言う */
    class Planned(val at: ZonedDateTime, val notice: MeteorShowers.Notice) {
        /**
         * 知らせる夜。前日の予告なら翌晩を指す。
         *
         * **月の明るさはこの夜のもの**を使う（[MeteorShowerNotice.of]）。
         * 鳴らす瞬間の月を渡すと、前日の予告で 1 日ぶんずれる。
         */
        val nightAt: ZonedDateTime =
            at.plusDays(if (notice.onPeakDay) 0 else 1).withHour(22).withMinute(0)
    }

    /**
     * [from] より後で最初に鳴らす予約。1 年先まで見て無ければ null。
     *
     * **1 件ずつしか返さない。** まとめて予約しても端末の再起動で全部消えるので、
     * 復元の手間が件数ぶん増えるだけになる（鳴ったら次を取り直す）。
     */
    fun next(showers: MeteorShowers, from: ZonedDateTime): Planned? {
        val today = from.toLocalDate()
        for (offset in 0..DAYS_AHEAD) {
            val date = today.plusDays(offset.toLong())
            val at = date.atTime(NOTIFY_HOUR, 0).atZone(from.zone)
            if (!at.isAfter(from)) continue
            val notice = showers.noticeOn(date.monthValue, date.dayOfMonth) ?: continue
            return Planned(at, notice)
        }
        return null
    }

    /** うるう年でも 1 周するように 1 日多く見る */
    private const val DAYS_AHEAD = 366
}
