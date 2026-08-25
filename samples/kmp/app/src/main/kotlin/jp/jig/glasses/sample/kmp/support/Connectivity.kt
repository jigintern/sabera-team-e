package jp.jig.glasses.sample.kmp.support

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * いま通信できるか。
 *
 * **聞くのは台本を作る画面だけ。** 再生中は通信しない設計なので、observation 側には要らない。
 * 圏外で AI の口を出したままにすると、旅行会社が**AI が書いた気でいるのに同梱の文**という
 * 一番説明しづらい状態になる（16_guide.md）。
 *
 * **繋がっていることの保証ではない。** 端末が「使える経路がある」と言っているだけで、
 * 実際に届くかは送ってみるまで分からない。だから失敗したときの退路は別に要る。
 */
object Connectivity {

    fun online(context: Context): Boolean = runCatching {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }.getOrDefault(false)
}
