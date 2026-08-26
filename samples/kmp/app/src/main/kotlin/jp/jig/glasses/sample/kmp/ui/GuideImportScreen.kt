package jp.jig.glasses.sample.kmp.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import jp.jig.glasses.sample.kmp.guide.GuideCodec
import jp.jig.glasses.sample.kmp.guide.GuideImport
import jp.jig.glasses.sample.kmp.guide.GuideStore
import jp.jig.glasses.sample.kmp.guide.StarGuide
import jp.jig.glasses.sample.kmp.ui.component.ConstellationBackground
import jp.jig.glasses.sample.kmp.ui.component.QrScanner
import jp.jig.glasses.sample.kmp.ui.component.SaberaDarkColorScheme
import jp.jig.glasses.sample.kmp.ui.component.SaberaFinePrint
import jp.jig.glasses.sample.kmp.ui.component.SaberaGreen
import jp.jig.glasses.sample.kmp.ui.component.SaberaOnAccent
import jp.jig.glasses.sample.kmp.ui.component.SaberaSurface
import jp.jig.glasses.sample.kmp.ui.component.SaberaTypography
import jp.jig.glasses.sample.kmp.ui.component.SaberaWarning
import jp.jig.glasses.sample.kmp.ui.component.SeasonalConstellationBackground

/**
 * 台本を受け取る画面。**QR とファイルの両方から。**
 *
 * カメラを断った人が詰まないよう、ファイルの口も必ず出す。
 *
 * **読み込む前に中身を見せる。** 「何を喋るのか分からないまま外へ持ち出させない」を
 * 受け取り側にも当てる（39_guide-authoring.md）。QR は誰でも作れるので、
 * **端末が読み上げる文がどこから来たのか、入れる前に本人が見られるようにする。**
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuideImportScreen(
    constellation: ConstellationBackground,
    onBack: () -> Unit,
    onImported: (StarGuide) -> Unit,
) {
    val context = LocalContext.current
    val store = remember(context) { GuideStore.of(context) }
    val vm = viewModel { GuideImportViewModel(store) }
    // 入り直したら真っさら（remember に載っていたころと同じ見え方）
    DisposableEffect(Unit) { onDispose { vm.leave() } }

    var cameraGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }

    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        cameraGranted = granted
        vm.onCameraPermission(granted)
    }

    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        vm.importFrom {
            runCatching {
                val text = context.contentResolver.openInputStream(uri)?.use { stream ->
                    // **丸ごと読まない。** 上限より大きければその時点で断る
                    String(stream.readNBytes(GuideCodec.MAX_INFLATED_BYTES + 1), Charsets.UTF_8)
                } ?: return@runCatching GuideImport.Rejected("ファイルを開けませんでした")
                GuideCodec.fromJson(text)
            }.getOrElse { GuideImport.Rejected("ファイルを読めませんでした: ${it.message}") }
        }
    }

    MaterialTheme(colorScheme = SaberaDarkColorScheme, typography = SaberaTypography) {
        Box(Modifier.fillMaxSize()) {
            SeasonalConstellationBackground(constellation, Modifier.fillMaxSize())
            Scaffold(
                containerColor = Color.Transparent,
                // **透ける下地には文字色が付いてこない。** Scaffold は containerColor から
                // 文字色を引くので、Transparent だと既定の黒のまま——カードの外に置いた
                // 見出しが夜空に溶けて読めなくなる
                contentColor = MaterialTheme.colorScheme.onSurface,
                topBar = {
                    TopAppBar(
                        title = { Text("ガイドを受け取る") },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = Color(0xA608111B),
                            titleContentColor = Color.White,
                        ),
                        navigationIcon = {
                            TextButton(onClick = onBack) { Text("戻る", color = Color.White) }
                        },
                    )
                },
            ) { padding ->
                Column(
                    Modifier.fillMaxSize().padding(padding).padding(16.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    val preview = vm.pending
                    if (preview != null) {
                        ImportPreview(
                            guide = preview,
                            onCancel = { vm.cancelPending() },
                            onAccept = { vm.save(preview, onImported) },
                        )
                        Spacer(Modifier.height(24.dp))
                        return@Column
                    }

                    Text(
                        "旅行会社などが作った台本を、QR かファイルから入れます",
                        style = MaterialTheme.typography.bodyMedium,
                    )

                    Spacer(Modifier.height(12.dp))
                    if (vm.scanning && cameraGranted) {
                        Card(
                            Modifier.fillMaxWidth().aspectRatio(1f),
                            colors = CardDefaults.cardColors(containerColor = Color.Black),
                        ) {
                            QrScanner(
                                onDecoded = { bytes -> vm.accept(GuideCodec.unpack(bytes)) },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "QR を枠に入れてください",
                            style = MaterialTheme.typography.bodySmall,
                            color = SaberaFinePrint,
                        )
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = { vm.scanning = false }) { Text("カメラを閉じる") }
                    } else {
                        Button(
                            onClick = {
                                if (cameraGranted) vm.scanning = true else askCamera.launch(Manifest.permission.CAMERA)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = SaberaGreen,
                                contentColor = SaberaOnAccent,
                            ),
                        ) {
                            Text("QR を読み取る")
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { openFile.launch(arrayOf("application/json", "text/plain", "*/*")) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("ファイルから読み込む")
                    }

                    vm.rejected?.let {
                        Spacer(Modifier.height(12.dp))
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = SaberaWarning)
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

/** 入れる前に中身を見せる。**何を喋るのか分からないまま入れさせない** */
@Composable
private fun ImportPreview(guide: StarGuide, onCancel: () -> Unit, onAccept: () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = SaberaSurface)) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(guide.title, style = MaterialTheme.typography.titleMedium)
            Text(
                "${guide.size} 段" + if (guide.locked) "・編集できません" else "",
                style = MaterialTheme.typography.bodySmall,
                color = SaberaFinePrint,
            )
            if (guide.summary.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(guide.summary, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(8.dp))
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                for ((index, step) in guide.steps.withIndex()) {
                    Text(
                        "${index + 1}. ${step.targetName}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        step.body,
                        style = MaterialTheme.typography.bodySmall,
                        color = SaberaFinePrint,
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("やめる") }
                Button(
                    onClick = onAccept,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SaberaGreen,
                        contentColor = SaberaOnAccent,
                    ),
                ) {
                    Text("この台本を入れる")
                }
            }
        }
    }
}
