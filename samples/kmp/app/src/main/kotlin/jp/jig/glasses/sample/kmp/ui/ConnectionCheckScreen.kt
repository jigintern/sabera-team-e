package jp.jig.glasses.sample.kmp.ui

import android.app.Activity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.jigglass.glass.GlassClient
import app.jigglass.glass.GlassManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun ConnectionCheckScreen(
    manager: GlassManager,
    client: GlassClient?,
    constellation: ConstellationBackground,
    onContinue: () -> Unit,
    onHome: () -> Unit,
) {
    val activity = LocalContext.current as Activity
    val scope = rememberCoroutineScope()
    var scanning by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val connected = client != null
    val deviceName = client?.deviceName?.takeIf { it.isNotBlank() }
        ?: client?.deviceIdentifier?.let { "SABERA (${it.takeLast(6)})" }

    LaunchedEffect(client) {
        if (client != null) error = null
    }

    Box(modifier = Modifier.fillMaxSize()) {
        SeasonalConstellationBackground(
            constellation = constellation,
            modifier = Modifier.fillMaxSize(),
        )

        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("接続確認", style = MaterialTheme.typography.headlineMedium, color = Color.White)
            Spacer(Modifier.height(24.dp))

            Card(
                modifier = Modifier.fillMaxWidth().widthIn(max = 360.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xE6152028)),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = if (connected) "●  接続済み" else "グラスが接続されていません",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (connected) Color(0xFF75E6A3) else Color.White,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(10.dp))
                    if (connected) {
                        Text(
                            text = deviceName.orEmpty(),
                            style = MaterialTheme.typography.headlineSmall,
                            color = Color.White,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "このSABERAで観測を始めます",
                            color = Color.White.copy(alpha = 0.68f),
                            textAlign = TextAlign.Center,
                        )
                    } else {
                        Text(
                            text = "SABERAの電源と、スマホのBluetoothを確認してください",
                            color = Color.White.copy(alpha = 0.68f),
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }

            error?.let { message ->
                Spacer(Modifier.height(16.dp))
                Card(
                    modifier = Modifier.fillMaxWidth().widthIn(max = 360.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xE65A2026)),
                ) {
                    Text(
                        text = message,
                        modifier = Modifier.padding(16.dp),
                        color = Color.White,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
            if (connected) {
                Button(
                    onClick = onContinue,
                    modifier = Modifier.fillMaxWidth().widthIn(max = 320.dp).height(52.dp),
                    colors = connectionButtonColors(),
                ) {
                    Text("星図へ進む")
                }
            } else {
                Button(
                    onClick = {
                        error = null
                        scanning = true
                        scope.launch {
                            try {
                                val selected = manager.showAutomaticSelectionDialog(activity)
                                if (selected == null) {
                                    error = "SABERAが選択されませんでした。もう一度接続をお試しください。"
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Throwable) {
                                error = connectionErrorMessage(e)
                            } finally {
                                scanning = false
                            }
                        }
                    },
                    enabled = !scanning,
                    modifier = Modifier.fillMaxWidth().widthIn(max = 320.dp).height(52.dp),
                    colors = connectionButtonColors(),
                ) {
                    Text(if (scanning) "接続中…" else "SABERAを接続する")
                }
            }

            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick = onHome,
                colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF75E6A3)),
            ) {
                Text("ホームへ戻る（一時ボタン）")
            }
        }
    }
}

@Composable
private fun connectionButtonColors() = ButtonDefaults.buttonColors(
    containerColor = Color(0xFF75E6A3),
    contentColor = Color(0xFF052010),
    disabledContainerColor = Color(0xFF75E6A3).copy(alpha = 0.45f),
    disabledContentColor = Color(0xFF052010).copy(alpha = 0.65f),
)

private fun connectionErrorMessage(error: Throwable): String {
    val detail = error.message.orEmpty().lowercase()
    return when {
        error is SecurityException || "permission" in detail || "denied" in detail ->
            "Bluetoothの権限がありません。スマホの設定からSABERAアプリの「付近のデバイス」を許可してください。"
        "bluetooth" in detail && ("off" in detail || "disabled" in detail) ->
            "Bluetoothがオフになっています。Bluetoothをオンにしてから、もう一度お試しください。"
        "timeout" in detail || "timed out" in detail ->
            "接続が時間切れになりました。SABERAをスマホの近くに置き、電源を確認してもう一度お試しください。"
        "bond" in detail || "pair" in detail ->
            "SABERAとのペアリングに失敗しました。端末を近づけて、もう一度お試しください。"
        else ->
            "SABERAに接続できませんでした。電源とBluetoothを確認して、もう一度お試しください。"
    }
}
