package jp.jig.glasses.sample.kmp.alignment

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import jp.jig.glasses.sample.kmp.sky.Site
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 観測地をスマホから取る。
 *
 * 星の高度は緯度がそのまま効くので、手入力のままだと出先で必ずずれる。
 * 精度は数十メートルあれば十分（1km ずれても星の位置は 0.01° も動かない）ので、
 * 測位が出るまで待つより、直近の値をすぐ使うほうがよい。
 */
class Locator(private val context: Context) {

    private val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    val granted: Boolean
        get() = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** 直近の測位。すぐ返るが古いことがある */
    // granted で権限を確かめたうえ runCatching で SecurityException も拾う。
    // lint は granted 越しの checkSelfPermission を追えないので、この 2 か所だけ黙らせる
    @SuppressLint("MissingPermission")
    fun lastKnown(): Located? {
        if (!granted) return null
        return PROVIDERS.asSequence()
            .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
            ?.let { Located(Site(it.latitude, it.longitude), it.provider ?: "?", it.time) }
    }

    /** いま測り直す。屋内では返らないことがあるので、呼ぶ側でタイムアウトする */
    suspend fun current(): Located? {
        if (!granted) return null
        for (provider in PROVIDERS) {
            if (!runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false)) continue
            val location = awaitLocation(provider) ?: continue
            return Located(Site(location.latitude, location.longitude), provider, location.time)
        }
        return null
    }

    @SuppressLint("MissingPermission")
    private suspend fun awaitLocation(provider: String): Location? = suspendCancellableCoroutine { cont ->
        val signal = CancellationSignal()
        cont.invokeOnCancellation { signal.cancel() }
        runCatching {
            manager.getCurrentLocation(provider, signal, context.mainExecutor) { location ->
                if (cont.isActive) cont.resume(location)
            }
        }.onFailure {
            if (cont.isActive) cont.resume(null)
        }
    }

    private companion object {
        /** 融合 → GPS → 基地局の順。屋内では GPS が返らないので基地局まで落とす */
        val PROVIDERS = listOf(
            LocationManager.FUSED_PROVIDER,
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
        )
    }
}

/** 測位の結果。どの経路でいつ取れたかを画面に出したいので持っておく */
class Located(val site: Site, val provider: String, val atMillis: Long)
