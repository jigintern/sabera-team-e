package jp.jig.glasses.sample.kmp.ui

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.jigglass.glass.GlassClient
import app.jigglass.glass.GlassManager
import jp.jig.glasses.sample.kmp.alignment.CalibrationResult
import jp.jig.glasses.sample.kmp.ui.component.SaberaGreen
import jp.jig.glasses.sample.kmp.ui.component.SaberaOnAccent
import jp.jig.glasses.sample.kmp.ui.component.SaberaSurface
import jp.jig.glasses.sample.kmp.ui.component.SaberaWarning
import jp.jig.glasses.sample.kmp.ui.component.rememberSeasonalConstellation
import kotlinx.coroutines.delay

/**
 * ホームから観測を始め、未接続なら接続確認、接続済みなら方位合わせを経て星図へ進む。
 *
 * SDKの汎用サンプル画面は撤去済み。APIの使い方は上流SDKの公開ドキュメントを参照する。
 */
@Composable
fun GlassesApp(manager: GlassManager) {
    var screen by rememberSaveable { mutableStateOf(AppScreen.HOME) }
    var headingOffset by rememberSaveable { mutableDoubleStateOf(0.0) }
    var pitchOffset by rememberSaveable { mutableDoubleStateOf(0.0) }
    var headingStd by rememberSaveable { mutableDoubleStateOf(0.0) }
    var pitchStd by rememberSaveable { mutableDoubleStateOf(0.0) }
    var calibrationSamples by rememberSaveable { mutableLongStateOf(0L) }
    var calibratedAt by rememberSaveable { mutableLongStateOf(0L) }
    val connectedClient by manager.connectedDevice.collectAsState(initial = null)
    var observingClient by remember { mutableStateOf<GlassClient?>(null) }
    var connectionLost by rememberSaveable { mutableStateOf(false) }
    val constellation = rememberSeasonalConstellation()

    /** 戻るキーで観測をやめようとしているか。**一度の誤操作で観測を畳まない** */
    var confirmLeaving by rememberSaveable { mutableStateOf(false) }

    /**
     * 戻るキーで 1 つ前の画面へ戻す。
     *
     * **既定のままだと戻るキーでアプリが終わる。** 観測中に終わると方位合わせからやり直しなので、
     * 星図の画面だけは確認を挟む（設定パネルを開いているときは
     * [StarMapScreen] 側の `BackHandler` が先に受けて、パネルを閉じるだけになる）。
     * ホームでは受けない。**そこは終わってよい場所**で、握るとアプリを閉じられなくなる。
     */
    BackHandler(enabled = screen != AppScreen.HOME) {
        when (screen) {
            AppScreen.HOME -> Unit
            AppScreen.CONNECTION -> screen = AppScreen.HOME
            AppScreen.CALIBRATION -> screen = AppScreen.CONNECTION
            AppScreen.STAR_MAP -> confirmLeaving = true
        }
    }

    // connectedDeviceは切断直後にnullになるため、猶予時間中もconnectedを確認できるよう最後のClientを保持する。
    LaunchedEffect(connectedClient) {
        if (connectedClient != null) observingClient = connectedClient
    }

    // 瞬断で観測画面を追い出さないよう、観測中だけ一定間隔でconnectedを確認する。
    LaunchedEffect(screen, observingClient) {
        if (screen != AppScreen.CALIBRATION && screen != AppScreen.STAR_MAP) {
            connectionLost = false
            return@LaunchedEffect
        }
        val client = observingClient ?: return@LaunchedEffect
        var disconnectedAt: Long? = null
        while (true) {
            if (client.connected.value) {
                disconnectedAt = null
            } else {
                val now = SystemClock.elapsedRealtime()
                val startedAt = disconnectedAt ?: now.also { disconnectedAt = it }
                if (now - startedAt >= CONNECTION_LOST_GRACE_MS) connectionLost = true
            }
            delay(CONNECTION_CHECK_INTERVAL_MS)
        }
    }

    when (screen) {
        AppScreen.HOME -> HomeScreen(
            constellation = constellation,
            onStart = { screen = AppScreen.CONNECTION },
        )
        AppScreen.CONNECTION -> ConnectionCheckScreen(
            manager = manager,
            client = connectedClient,
            constellation = constellation,
            onContinue = { screen = AppScreen.CALIBRATION },
            onHome = { screen = AppScreen.HOME },
        )
        AppScreen.CALIBRATION -> {
            val currentClient = observingClient
            if (currentClient == null) {
                ConnectionCheckScreen(
                    manager = manager,
                    client = null,
                    constellation = constellation,
                    onContinue = { screen = AppScreen.CALIBRATION },
                    onHome = { screen = AppScreen.HOME },
                )
            } else {
                CalibrationScreen(
                    client = currentClient,
                    constellation = constellation,
                    onCalibrated = { result ->
                        headingOffset = result.headingOffsetDeg
                        pitchOffset = result.pitchOffsetDeg
                        headingStd = result.headingStdDeg
                        pitchStd = result.pitchStdDeg
                        calibrationSamples = result.sampleCount.toLong()
                        calibratedAt = result.calibratedAt
                        screen = AppScreen.STAR_MAP
                    },
                    onHome = { screen = AppScreen.HOME },
                )
            }
        }
        AppScreen.STAR_MAP -> {
            val currentClient = observingClient
            if (currentClient == null) {
                ConnectionCheckScreen(
                    manager = manager,
                    client = null,
                    constellation = constellation,
                    onContinue = { screen = AppScreen.CALIBRATION },
                    onHome = { screen = AppScreen.HOME },
                )
            } else {
                StarMapScreen(
                    client = currentClient,
                    initialCalibration = calibratedAt.takeIf { it > 0L }?.let {
                        CalibrationResult(
                            headingOffsetDeg = headingOffset,
                            pitchOffsetDeg = pitchOffset,
                            calibratedAt = it,
                            headingStdDeg = headingStd,
                            pitchStdDeg = pitchStd,
                            sampleCount = calibrationSamples.toInt(),
                        )
                    },
                    constellation = constellation,
                    onRecalibrate = { screen = AppScreen.CALIBRATION },
                )
            }
        }
    }

    if (connectionLost) {
        ConnectionLostDialog(
            onConnectionCheck = {
                connectionLost = false
                observingClient = null
                screen = AppScreen.CONNECTION
            },
        )
    }

    // 切断のダイアログが出ているなら、そちらが先。重ねて出さない
    if (confirmLeaving && !connectionLost) {
        LeaveObservationDialog(
            onLeave = {
                confirmLeaving = false
                screen = AppScreen.HOME
            },
            onStay = { confirmLeaving = false },
        )
    }
}

/**
 * 観測をやめるかの確認。
 *
 * **方位合わせをやり直すことになるので、一度の戻るキーでは畳まない。**
 * ホームへ戻ってもう一度観測に入るには、接続確認と方位合わせを通る必要がある。
 */
@Composable
private fun LeaveObservationDialog(onLeave: () -> Unit, onStay: () -> Unit) {
    Dialog(onDismissRequest = onStay) {
        Card(
            modifier = Modifier.fillMaxWidth().widthIn(max = 360.dp),
            colors = CardDefaults.cardColors(containerColor = SaberaSurface),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "観測をやめますか",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "ホームへ戻ると、方位合わせからやり直しになります",
                    color = Color.White.copy(alpha = 0.68f),
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = onStay,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SaberaGreen,
                        contentColor = SaberaOnAccent,
                    ),
                ) {
                    Text("観測を続ける")
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onLeave, modifier = Modifier.fillMaxWidth()) {
                    Text("やめてホームへ", color = SaberaWarning)
                }
            }
        }
    }
}

@Composable
private fun ConnectionLostDialog(onConnectionCheck: () -> Unit) {
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
    ) {
        Card(
            modifier = Modifier.fillMaxWidth().widthIn(max = 360.dp),
            colors = CardDefaults.cardColors(containerColor = SaberaSurface),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "SABERAとの接続が切れました",
                    style = MaterialTheme.typography.titleLarge,
                    color = SaberaWarning,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "SABERAの電源とBluetoothを確認してください",
                    color = Color.White,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "接続確認画面に戻って接続状態を確認してください",
                    color = Color.White.copy(alpha = 0.68f),
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = onConnectionCheck,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SaberaGreen,
                        contentColor = SaberaOnAccent,
                    ),
                ) {
                    Text("接続確認へ")
                }
            }
        }
    }
}

private enum class AppScreen {
    HOME,
    CONNECTION,
    CALIBRATION,
    STAR_MAP,
}

private const val CONNECTION_CHECK_INTERVAL_MS = 1_000L
private const val CONNECTION_LOST_GRACE_MS = 2_000L
