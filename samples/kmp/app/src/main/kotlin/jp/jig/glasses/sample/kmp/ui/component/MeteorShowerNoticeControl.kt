package jp.jig.glasses.sample.kmp.ui.component

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import jp.jig.glasses.sample.kmp.notification.MeteorShowerAlarm
import jp.jig.glasses.sample.kmp.notification.NotificationPrefs

/** 流星群の予告（#70）の入／切。[rememberMeteorShowerNotice] が作る */
@Stable
class MeteorShowerNoticeState(val enabled: Boolean, val onChange: (Boolean) -> Unit)

/**
 * 流星群の予告の入／切を、**ホームと設定パネルの両方から同じ手順で**動かす。
 *
 * 置き場所が 2 つあるのは、通知を決めるのが**出かける前**（ホーム）である一方、
 * 屋外で「うるさいから切る」も要るため。**手順を 2 か所に書くと必ず食い違う**ので、
 * 覚える・許可を聞く・予約を取り直すの 3 つはここだけが持つ。
 */
@Composable
fun rememberMeteorShowerNotice(): MeteorShowerNoticeState {
    val context = LocalContext.current
    val prefs = remember(context) { NotificationPrefs(context) }
    var enabled by remember { mutableStateOf(prefs.meteorShowerEnabled) }

    fun store(on: Boolean) {
        prefs.meteorShowerEnabled = on
        enabled = on
        if (on) MeteorShowerAlarm.reschedule(context) else MeteorShowerAlarm.cancel(context)
    }

    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        // **断られたらトグルも戻す。** 入っているのに鳴らない状態を残さない
        store(it)
    }

    return MeteorShowerNoticeState(enabled) { wanted ->
        when {
            !wanted -> store(false)
            // Android 12 以前に POST_NOTIFICATIONS は無い。**聞くと必ず断られたことになる**
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU -> store(true)
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED -> store(true)

            else -> ask.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
