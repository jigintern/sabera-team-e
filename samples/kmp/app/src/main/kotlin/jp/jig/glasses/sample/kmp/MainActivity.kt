package jp.jig.glasses.sample.kmp

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import app.jigglass.ble.BleCompanionDeviceService
import app.jigglass.ble.BleDeviceSelector
import app.jigglass.glass.SdkActivityHost
import app.jigglass.glass.getGlassManager
import jp.jig.glasses.sample.kmp.ui.GlassesApp
import jp.jig.glasses.sample.kmp.ui.component.SaberaTypography
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.SupervisorJob

class MainActivity : ComponentActivity() {

    private lateinit var deviceSelector: BleDeviceSelector
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /**
     * 流星群の予告（#70）から開かれたか。
     *
     * **ホームのひとことは起動ごとに巡回する**ので、そのままだと「今夜はふたご座流星群」で
     * 呼び出したのに惑星の話が開く。呼んだ理由をそのまま出すために印を渡す（#37 と同じ形の食い違い）。
     */
    private var fromMeteorShowerNotice by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        deviceSelector = BleDeviceSelector(this)
        SdkActivityHost.showBleDeviceSelectionDialog = { _: Context, callback: (String?) -> Unit ->
            deviceSelector.showDialog(scope = scope, singleTarget = false, callback = callback)
        }

        fromMeteorShowerNotice = intent?.hasMeteorShowerTip() == true

        BleCompanionDeviceService.connectToLastDevice(this)
        requestBlePermissionsIfNeeded()

        val manager = getGlassManager(applicationContext)

        setContent {
            MaterialTheme(typography = SaberaTypography) {
                GlassesApp(manager = manager, fromMeteorShowerNotice = fromMeteorShowerNotice)
            }
        }
    }

    /** `launchMode="singleTop"` なので、開いたままの通知タップはここへ来る */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.hasMeteorShowerTip()) fromMeteorShowerNotice = true
    }

    override fun onDestroy() {
        SdkActivityHost.showBleDeviceSelectionDialog = null
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    @Deprecated("Use Activity Result API", ReplaceWith("registerForActivityResult(...)"))
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (deviceSelector.onActivityResult(requestCode, resultCode, data, MainScope())) return
        super.onActivityResult(requestCode, resultCode, data)
    }

    private fun requestBlePermissionsIfNeeded() {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (perms.any { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }) {
            @Suppress("DEPRECATION")
            requestPermissions(perms, REQUEST_BLE_PERMISSIONS)
        }
    }

    private fun Intent.hasMeteorShowerTip(): Boolean =
        getBooleanExtra(EXTRA_SHOW_METEOR_SHOWER_TIP, false)

    companion object {
        /** 流星群の予告から開かれたことを伝える印（#70） */
        const val EXTRA_SHOW_METEOR_SHOWER_TIP = "jp.jig.glasses.sample.kmp.extra.METEOR_SHOWER_TIP"

        private const val REQUEST_BLE_PERMISSIONS = 1001
    }
}
