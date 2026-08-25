package jp.jig.glasses.sample.kmp.ui

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import jp.jig.glasses.sample.kmp.guide.GuideCodec
import jp.jig.glasses.sample.kmp.guide.StarGuide
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
    var enabled by remember(guide.id) { mutableStateOf(guide.steps.map { it.enabled }) }
    var locked by remember(guide.id) { mutableStateOf(guide.locked) }
    var notice by remember { mutableStateOf<String?>(null) }

    val shared = guide.copy(
        steps = guide.steps.mapIndexed { i, step -> step.copy(enabled = enabled.getOrElse(i) { true }) },
        locked = locked,
    )
    // **押すたびに圧縮し直さない。** 段の ON/OFF と編集の可否が変わったときだけ
    val packed = remember(enabled, locked) { GuideCodec.pack(shared) }
    val qr: Bitmap? = remember(packed) {
        if (packed.size <= GuideCodec.QR_CAPACITY_BYTES) QrCode.encode(packed, QR_SIZE_PX) else null
    }

    val saveFile = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        notice = runCatching {
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
                Column(
                    Modifier.fillMaxSize().padding(padding).padding(16.dp)
                        .verticalScroll(rememberScrollState()),
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
                                        onCheckedChange = { on ->
                                            enabled = enabled.mapIndexed { i, old -> if (i == index) on else old }
                                        },
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
                                Switch(checked = locked, onCheckedChange = { locked = it })
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
                        usedRatio = (packed.size.toFloat() / GuideCodec.QR_CAPACITY_BYTES).coerceIn(0f, 1f),
                        remainingBytes = GuideCodec.QR_CAPACITY_BYTES - packed.size,
                    )

                    Spacer(Modifier.height(16.dp))
                    if (qr != null) {
                        Card(
                            Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = Color.White),
                        ) {
                            Image(
                                bitmap = qr.asImageBitmap(),
                                contentDescription = "台本の QR コード",
                                modifier = Modifier.fillMaxWidth().aspectRatio(1f).padding(12.dp),
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
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { saveFile.launch(fileName(guide)) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("ファイルに書き出す")
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "ファイルは中身が読める JSON です。PC で文面を直してから配れます",
                        style = MaterialTheme.typography.bodySmall,
                        color = SaberaFinePrint,
                    )
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

/** 記号を落として、どの端末でも作れるファイル名にする */
private fun fileName(guide: StarGuide): String =
    guide.title.replace(Regex("[^\\p{L}\\p{N}]+"), "-").trim('-').ifEmpty { "guide" } + ".json"

/** 画面に出して別の端末で読ませる大きさ。version 40 は 177 モジュールあるので粗いと読めない */
private const val QR_SIZE_PX = 720
