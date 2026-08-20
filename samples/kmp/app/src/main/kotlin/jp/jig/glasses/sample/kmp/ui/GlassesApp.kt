package jp.jig.glasses.sample.kmp.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import app.jigglass.glass.GlassManager

/**
 * 画面はスキャンと星図の 2 つだけ。繋がったらそのまま星図に入る。
 *
 * 上流サンプルの各画面（CommandScreen など）は SDK の使い方の参照として残してあるが、
 * team-e のアプリからは開かない。
 */
@Composable
fun GlassesApp(manager: GlassManager) {
    val client by manager.connectedDevice.collectAsState(initial = null)

    val currentClient = client
    if (currentClient == null) {
        ScanScreen(manager = manager)
        return
    }

    StarMapScreen(client = currentClient)
}
