package jp.jig.glasses.sample.kmp.ui

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.jigglass.glass.GlassClient
import app.jigglass.glass.GlassManager
import jp.jig.glasses.sample.kmp.ui.component.ConstellationBackground
import jp.jig.glasses.sample.kmp.ui.component.SaberaGreen
import jp.jig.glasses.sample.kmp.ui.component.SaberaOnAccent
import jp.jig.glasses.sample.kmp.ui.component.SaberaSurface
import jp.jig.glasses.sample.kmp.ui.component.SaberaWarning
import jp.jig.glasses.sample.kmp.ui.component.SeasonalConstellationBackground

@Composable
fun ConnectionCheckScreen(
    manager: GlassManager,
    client: GlassClient?,
    constellation: ConstellationBackground,
    onContinue: () -> Unit,
    onHome: () -> Unit,
    onDisconnect: () -> Unit,
) {
    // Activity が要るのは端末選択ダイアログだけ。取れない文脈（プレビュー等）では何も出さない
    val activity = LocalActivity.current ?: return
    val vm = viewModel<ConnectionViewModel>()
    val connected = client != null
    val deviceName = client?.deviceName?.takeIf { it.isNotBlank() }
        ?: client?.deviceIdentifier?.let { "SABERA (${it.takeLast(6)})" }

    LaunchedEffect(client) {
        if (client != null) vm.onConnected()
    }
    // 入り直したら真っさら（remember に載っていたころと同じ見え方）
    DisposableEffect(Unit) { onDispose { vm.reset() } }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        val outerPadding = if (landscape) 12.dp else 24.dp
        val sectionGap = if (landscape) 12.dp else 24.dp
        SeasonalConstellationBackground(
            constellation = constellation,
            modifier = Modifier.fillMaxSize(),
        )

        Column(
            modifier = Modifier.fillMaxSize().padding(outerPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("接続確認", style = MaterialTheme.typography.headlineMedium, color = Color.White)
            Spacer(Modifier.height(sectionGap))

            Card(
                modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = SaberaSurface),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(if (landscape) 16.dp else 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = if (connected) "●  接続済み" else "グラスが接続されていません",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (connected) SaberaGreen else Color.White,
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

            vm.error?.let { message ->
                Spacer(Modifier.height(16.dp))
                Card(
                    modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth(),
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

            Spacer(Modifier.height(sectionGap))
            if (connected) {
                Button(
                    onClick = onContinue,
                    modifier = Modifier.widthIn(max = 320.dp).fillMaxWidth().height(52.dp),
                    colors = connectionButtonColors(),
                ) {
                    Text("星図へ進む")
                }
                // **ここで確認は挟まない。** つないだだけの画面には畳まれるものが無く、
                // 取り消しは「SABERAを接続する」を押し直す 1 タップで済む。
                // **違う機体につながっていると気づくのはたいていここ**（#129）
                TextButton(
                    onClick = onDisconnect,
                    colors = ButtonDefaults.textButtonColors(contentColor = SaberaWarning),
                ) {
                    Text("別のSABERAにつなぎ直す")
                }
            } else {
                Button(
                    onClick = { vm.connect { manager.showAutomaticSelectionDialog(activity) } },
                    enabled = !vm.scanning,
                    modifier = Modifier.widthIn(max = 320.dp).fillMaxWidth().height(52.dp),
                    colors = connectionButtonColors(),
                ) {
                    Text(if (vm.scanning) "接続中…" else "SABERAを接続する")
                }
            }

            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick = onHome,
                colors = ButtonDefaults.textButtonColors(contentColor = SaberaGreen),
            ) {
                Text("ホーム")
            }
        }
    }
}

@Composable
private fun connectionButtonColors() = ButtonDefaults.buttonColors(
    containerColor = SaberaGreen,
    contentColor = SaberaOnAccent,
    disabledContainerColor = SaberaGreen.copy(alpha = 0.45f),
    disabledContentColor = SaberaOnAccent.copy(alpha = 0.65f),
)
