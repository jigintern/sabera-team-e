package jp.jig.glasses.sample.kmp

import android.app.Application
import android.util.Log
import app.jigglass.glass.GlassesSDK
import app.jigglass.glass.SdkActivityHost

class SaberaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        GlassesSDK.setLogger { tag, message -> Log.d(tag, message) }
        GlassesSDK.setProd(true)
        GlassesSDK.setDevicePersistence(SharedPrefsDevicePersistence(this))
        SdkActivityHost.showBleDeviceSelectionDialog = null
    }
}
