package jp.jig.glasses.sample.kmp.ui

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import jp.jig.glasses.sample.kmp.guide.GuideCodec
import jp.jig.glasses.sample.kmp.guide.StarGuide
import jp.jig.glasses.sample.kmp.ui.component.ACTION_BUTTON_MAX_WIDTH
import jp.jig.glasses.sample.kmp.support.QrCode
import jp.jig.glasses.sample.kmp.ui.component.ConstellationBackground
import jp.jig.glasses.sample.kmp.ui.component.QrBudgetBar
import jp.jig.glasses.sample.kmp.ui.component.SaberaDarkColorScheme
import jp.jig.glasses.sample.kmp.ui.component.SaberaFinePrint
import jp.jig.glasses.sample.kmp.ui.component.SaberaGreen
import jp.jig.glasses.sample.kmp.ui.component.SaberaSurface
import jp.jig.glasses.sample.kmp.ui.component.SaberaTypography
import jp.jig.glasses.sample.kmp.ui.component.SaberaWarning
import jp.jig.glasses.sample.kmp.ui.component.SeasonalConstellationBackground

/**
 * 台本を配る画面（toB）。
 *
 * **ここで版が決まる。** 「曇っていたら別の話」を台本の分岐で持たせず、
 * 配る直前に段を外して今日の版にする。外した段は原本に残るので翌週そのまま戻せる。
 *
 * **QR には外した段を入れない。** 1 枚に入るのは [GuideCodec.QR_CAPACITY_BYTES] しかなく、
 * 読めない段のために枠を食う余裕がない。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuideShareScreen(
    constellation: ConstellationBackground,
    guide: StarGuide,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val vm = viewModel(key = guide.id) { GuideShareViewModel(guide) }
    val shared = vm.shared
    // **押すたびに圧縮し直さない。** 段の ON/OFF と編集の可否が変わったときだけ
    val packed = remember(vm.enabled, vm.locked) { GuideCodec.pack(shared) }
    val qr: Bitmap? = remember(packed) {
        if (packed.size <= GuideCodec.QR_CAPACITY_BYTES) QrCode.encode(packed, QR_SIZE_PX) else null
    }

    val saveFile = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        vm.notice = runCatching {
            context.contentResolver.openOutputStream(uri)?.use {
                it.write(GuideCodec.json(shared).toByteArray(Charsets.UTF_8))
            }
            "ファイルに書き出しました"
        }.getOrElse { "書き出せませんでした: ${it.message}" }
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
                        title = { Text("配る") },
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
                BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
                    val landscape = maxWidth > maxHeight
                    if (landscape) {
                        val contentHeight = maxHeight
                        val laneWidth = maxWidth / 2 - 32.dp
                        val qrImage = minOf(
                            laneWidth - QR_CARD_PADDING * 2,
                            contentHeight - QR_LANE_RESERVE - QR_CARD_PADDING * 2,
                        ).coerceAtLeast(QR_MIN_SIDE)
                        Row(
                            Modifier.fillMaxSize().padding(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            Column(
                                Modifier.weight(1f).fillMaxHeight()
                                    .verticalScroll(rememberScrollState()),
                            ) {
                                GuideEditionContent(
                                    guide = guide,
                                    enabled = vm.enabled,
                                    locked = vm.locked,
                                    packedSize = packed.size,
                                    onToggleStep = vm::toggleStep,
                                    onLockedChange = { vm.locked = it },
                                )
                            }
                            Column(
                                Modifier.weight(1f).fillMaxHeight()
                                    .verticalScroll(rememberScrollState()),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                // QR の中心を右半分の中心へ合わせ、説明と書き出しはその下へ流す。
                                Spacer(
                                    Modifier.height(
                                        ((contentHeight - qrImage - QR_CARD_PADDING * 2) / 2)
                                            .coerceAtLeast(0.dp),
                                    ),
                                )
                                GuideHandoverContent(
                                    qr = qr,
                                    qrImage = qrImage,
                                    notice = vm.notice,
                                    buttonModifier = Modifier.widthIn(max = ACTION_BUTTON_MAX_WIDTH)
                                        .fillMaxWidth(),
                                    onSave = { saveFile.launch(fileName(guide)) },
                                )
                            }
                        }
                    } else {
                        Column(
                            Modifier.fillMaxSize().padding(16.dp)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            GuideEditionContent(
                                guide = guide,
                                enabled = vm.enabled,
                                locked = vm.locked,
                                packedSize = packed.size,
                                onToggleStep = vm::toggleStep,
                                onLockedChange = { vm.locked = it },
                            )
                            Spacer(Modifier.height(16.dp))
                            GuideHandoverContent(
                                qr = qr,
                                qrImage = null,
                                notice = vm.notice,
                                buttonModifier = Modifier.fillMaxWidth(),
                                onSave = { saveFile.launch(fileName(guide)) },
                            )
                            Spacer(Modifier.height(24.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.GuideEditionContent(
    guide: StarGuide,
    enabled: List<Boolean>,
    locked: Boolean,
    packedSize: Int,
    onToggleStep: (Int, Boolean) -> Unit,
    onLockedChange: (Boolean) -> Unit,
) {
    Text(guide.title, style = MaterialTheme.typography.titleMedium)
    Text(
        "${enabled.count { it }} / ${guide.size} 段を配ります",
        style = MaterialTheme.typography.bodySmall,
        color = SaberaFinePrint,
    )
    Spacer(Modifier.height(12.dp))
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = SaberaSurface)) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text("今日やる段", style = MaterialTheme.typography.titleSmall)
            Text(
                "外した段は QR に入りません。原本には残ります",
                style = MaterialTheme.typography.bodySmall,
                color = SaberaFinePrint,
            )
            Spacer(Modifier.height(4.dp))
            for ((index, step) in guide.steps.withIndex()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("${index + 1}. ${step.targetName}", Modifier.weight(1f))
                    Switch(
                        checked = enabled.getOrElse(index) { true },
                        onCheckedChange = { on -> onToggleStep(index, on) },
                    )
                }
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = SaberaSurface)) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("受け取った人に編集させない", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "客がうっかり直してしまうのを防ぎます",
                        style = MaterialTheme.typography.bodySmall,
                        color = SaberaFinePrint,
                    )
                }
                Switch(checked = locked, onCheckedChange = onLockedChange)
            }
            if (locked) {
                Spacer(Modifier.height(4.dp))
                // **守れないものを守れると書かない**（AGENTS.md と同じ筋）
                Text(
                    "これは鍵ではありません。QR は誰でも作れるので、" +
                        "作り直せば編集できてしまいます",
                    style = MaterialTheme.typography.bodySmall,
                    color = SaberaWarning,
                )
            }
        }
    }
    Spacer(Modifier.height(16.dp))
    QrBudgetBar(
        usedRatio = (packedSize.toFloat() / GuideCodec.QR_CAPACITY_BYTES).coerceIn(0f, 1f),
        remainingBytes = GuideCodec.QR_CAPACITY_BYTES - packedSize,
    )
}

@Composable
private fun ColumnScope.GuideHandoverContent(
    qr: Bitmap?,
    qrImage: Dp?,
    notice: String?,
    buttonModifier: Modifier,
    onSave: () -> Unit,
) {
    if (qr != null) {
        Card(
            modifier = if (qrImage == null) {
                Modifier.fillMaxWidth().aspectRatio(1f)
            } else {
                Modifier.size(qrImage + QR_CARD_PADDING * 2)
            },
            colors = CardDefaults.cardColors(containerColor = Color.White),
        ) {
            Image(
                bitmap = qr.asImageBitmap(),
                contentDescription = "台本の QR コード",
                filterQuality = FilterQuality.None,
                modifier = Modifier.fillMaxSize().padding(QR_CARD_PADDING),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "相手のアプリで「ガイドを受け取る」を開いて、これを写してもらいます",
            style = MaterialTheme.typography.bodySmall,
            color = SaberaFinePrint,
        )
    } else {
        Text(
            "QR に入りません。段を外すか、解説を短くしてください",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
    notice?.let {
        Spacer(Modifier.height(12.dp))
        Text(it, style = MaterialTheme.typography.bodyMedium, color = SaberaGreen)
    }
    Spacer(Modifier.height(16.dp))
    OutlinedButton(onClick = onSave, modifier = buttonModifier) {
        Text("ファイルに書き出す")
    }
    Spacer(Modifier.height(4.dp))
    Text(
        "ファイルはパソコンでも開けます。文面を直してから配れます",
        style = MaterialTheme.typography.bodySmall,
        color = SaberaFinePrint,
    )
}

/** 記号を落として、どの端末でも作れるファイル名にする */
private fun fileName(guide: StarGuide): String =
    guide.title.replace(Regex("[^\\p{L}\\p{N}]+"), "-").trim('-').ifEmpty { "guide" } + ".json"

/** 画面に出して別の端末で読ませる大きさ。version 40 は 177 モジュールあるので粗いと読めない */
private const val QR_SIZE_PX = 720
private val QR_CARD_PADDING = 12.dp
private val QR_LANE_RESERVE = 56.dp
private val QR_MIN_SIDE = 180.dp
