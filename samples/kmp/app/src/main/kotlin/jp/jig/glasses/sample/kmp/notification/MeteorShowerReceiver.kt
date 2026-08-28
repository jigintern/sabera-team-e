package jp.jig.glasses.sample.kmp.notification

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import jp.jig.glasses.sample.kmp.MainActivity
import jp.jig.glasses.sample.kmp.R
import jp.jig.glasses.sample.kmp.catalog.MeteorShowers
import jp.jig.glasses.sample.kmp.sky.moonPhase
import java.time.ZonedDateTime

/**
 * 予告の時刻に鳴らし、端末が起きたら予約を取り直す（#70）。
 *
 * **鳴らせなかった回は捨てる。** 観測中（グラス接続中）は通知を出すと星図が消えるので、
 * その回はあきらめて次を予約する。あとから鳴らし直しても、その夜はもう終わっている。
 */
class MeteorShowerReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        when (intent.action) {
            // 端末を再起動すると予約は消える。**次の 1 件を計算し直すだけでよい**
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> MeteorShowerAlarm.reschedule(app)

            MeteorShowerAlarm.ACTION_NOTIFY -> {
                notifyIfPossible(app)
                // **鳴らせても鳴らせなくても次を取り直す。** ここを飛ばすと予約が途切れる
                MeteorShowerAlarm.reschedule(app)
            }
        }
    }

    private fun notifyIfPossible(app: Context) {
        if (!NotificationPrefs(app).meteorShowerEnabled) return
        if (MeteorShowerAlarm.observing) {
            Log.i(TAG, "観測中なので予告を出さない（星図が消えるため）")
            return
        }
        // Android 12 以前に POST_NOTIFICATIONS は無く、聞けば必ず「拒否」が返る
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        // 鳴った日から引き直す。**予約に中身を積まない**ので、寝ている間に日付をまたいでもずれない。
        //
        // **さかのぼる幅と、遅れを許す幅を同じにする。** 使っているのは
        // setAndAllowWhileIdle（不正確アラーム）で数分の粒度でしか鳴らないのに、
        // 1 分しかさかのぼっていなかった。next は「18 時を過ぎた日は飛ばす」ので、
        // 1 分を超えて遅れると翌日の予定が返り、直後の isAfter に引っかかって黙っていた
        // 判定は MeteorShowerSchedule.plannedFor に寄せてある（JVM テストで固定するため）
        val planned = MeteorShowerSchedule.plannedFor(MeteorShowers.load(app), ZonedDateTime.now())
            ?: return

        val text = MeteorShowerNotice.of(
            notice = planned.notice,
            moonIlluminated = moonPhase(planned.nightAt.toInstant().toEpochMilli()).illuminated,
        )
        val manager = app.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(channel())
        manager.notify(NOTIFICATION_ID, build(app, text))
    }

    /**
     * 通知のチャンネル。
     *
     * **1 本だけ立てる。** 年 8 通なので音が鳴っても邪魔にならず、うるさい人は OS 側で
     * このチャンネルだけ黙らせられる（アプリごと切らずに済む）。
     */
    private fun channel() = NotificationChannel(
        CHANNEL_ID,
        "流星群のお知らせ",
        NotificationManager.IMPORTANCE_DEFAULT,
    ).apply {
        description = "いちばんよく流れる夜の前日と当日に、夕方おしらせします"
    }

    private fun build(app: Context, text: MeteorShowerNotice.Text): Notification {
        // **開いた先で「なぜ呼ばれたか」が消えないようにする。** ホームのひとことは
        // 起動ごとに巡回するので、そのままだと惑星の話が出る（#37 と同じ食い違い）
        val open = Intent(app, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_SHOW_METEOR_SHOWER_TIP, true)
        val pending = PendingIntent.getActivity(
            app,
            REQUEST_CODE,
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(app, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_meteor_notification)
            .setContentTitle(text.title)
            .setContentText(text.body)
            // 畳んだ通知は本文 1 行で切れる。**開けば全部読める**ようにしておく
            .setStyle(Notification.BigTextStyle().bigText(text.body))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
    }

    private companion object {
        const val CHANNEL_ID = "meteor_shower"
        const val NOTIFICATION_ID = 70
        const val REQUEST_CODE = 70
        const val TAG = "MeteorShowerReceiver"
    }
}
