package jp.jig.glasses.sample.kmp.notification

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import jp.jig.glasses.sample.kmp.catalog.MeteorShowers
import java.time.ZonedDateTime

/**
 * 流星群の予告を予約する（#70）。
 *
 * **正確アラームは使わない。** `setExactAndAllowWhileIdle` には `SCHEDULE_EXACT_ALARM` が要り、
 * 「目覚まし時計かカレンダー」以外は許可されない。`setAndAllowWhileIdle` なら権限が要らず、
 * 端末が寝ていても数分の粒度で鳴る。**夕方の予告に分単位の正確さは要らない。**
 *
 * **予約するのは常に次の 1 件だけ。** 取り直す口は 3 か所（鳴ったとき・端末が起きたとき・
 * トグルを入れたとき）で、どれも [reschedule] を呼ぶ。
 */
object MeteorShowerAlarm {

    /** 自前のアラームであることの目印。`BOOT_COMPLETED` と同じ受信器で受ける */
    const val ACTION_NOTIFY = "jp.jig.glasses.sample.kmp.action.METEOR_SHOWER_NOTIFY"

    /**
     * 星図をグラスへ出している最中か。
     *
     * **通知が出るとグラスの星図が消える**（`docs/team-e/02_glass-output.md`）ので、
     * 観測中は鳴らさずに捨てる。**そもそも観測中の人は既に空の下にいる。**
     *
     * `SharedPreferences` に持たせない。アプリが落ちたときに true が残ると、
     * 二度と通知が出なくなる。**プロセスが死ねば false に戻る（＝観測していない）のが正しい。**
     * 受信器は同じプロセスで動くので、生の値がそのまま読める。
     */
    @Volatile
    var observing: Boolean = false

    /**
     * 次の 1 件を予約し直す。切ってあれば取り消すだけ。
     *
     * **同じ [PendingIntent] を使い回す**ので、二重に予約されることはない（後から上書きされる）。
     */
    fun reschedule(context: Context) {
        val app = context.applicationContext
        val alarms = app.getSystemService(AlarmManager::class.java) ?: return
        val pending = pendingIntent(app)

        if (!NotificationPrefs(app).meteorShowerEnabled) {
            alarms.cancel(pending)
            return
        }

        // 受信器は冷えたプロセスで動くことがあるので、BundledData のキャッシュは当てにしない。
        // 2KB の JSON なので、その場で読んでも数ミリ秒で済む
        val planned = MeteorShowerSchedule.next(MeteorShowers.load(app), ZonedDateTime.now())
        if (planned == null) {
            alarms.cancel(pending)
            return
        }

        alarms.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            planned.at.toInstant().toEpochMilli(),
            pending,
        )
        Log.i(TAG, "次の予告を予約した: ${planned.notice.shower.nameJa} ${planned.at}")
    }

    /** 予約を取り消す。トグルを切ったときに呼ぶ */
    fun cancel(context: Context) {
        val app = context.applicationContext
        app.getSystemService(AlarmManager::class.java)?.cancel(pendingIntent(app))
    }

    private fun pendingIntent(app: Context): PendingIntent = PendingIntent.getBroadcast(
        app,
        REQUEST_CODE,
        Intent(app, MeteorShowerReceiver::class.java).setAction(ACTION_NOTIFY),
        // **可変にする理由が無い。** 中身は action だけで、受け取る側は日付を引き直す
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private const val REQUEST_CODE = 70
    private const val TAG = "MeteorShowerAlarm"
}
