package jp.jig.glasses.sample.kmp.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import app.jigglass.glass.GlassManager

/**
 * ホームから観測を始め、未接続ならスキャン、接続済みなら星図へ進む。
 *
 * 上流サンプルの各画面（CommandScreen など）は SDK の使い方の参照として残してあるが、
 * team-e のアプリからは開かない。
 */
@Composable
fun GlassesApp(manager: GlassManager) {
    var screen by rememberSaveable { mutableIntStateOf(HOME_SCREEN) }
    val client by manager.connectedDevice.collectAsState(initial = null)
    val constellation = rememberSeasonalConstellation()

    when (screen) {
        HOME_SCREEN -> HomeScreen(
            constellation = constellation,
            onStart = { screen = CONNECTION_SCREEN },
        )
        CONNECTION_SCREEN -> ConnectionCheckScreen(
            manager = manager,
            client = client,
            constellation = constellation,
            onContinue = { screen = STAR_MAP_SCREEN },
            onHome = { screen = HOME_SCREEN },
        )
        STAR_MAP_SCREEN -> {
            val currentClient = client
            if (currentClient == null) {
                ConnectionCheckScreen(
                    manager = manager,
                    client = null,
                    constellation = constellation,
                    onContinue = { screen = STAR_MAP_SCREEN },
                    onHome = { screen = HOME_SCREEN },
                )
            } else {
                StarMapScreen(
                    client = currentClient,
                    onHome = { screen = HOME_SCREEN },
                )
            }
        }
    }
}

private const val HOME_SCREEN = 0
private const val CONNECTION_SCREEN = 1
private const val STAR_MAP_SCREEN = 2
