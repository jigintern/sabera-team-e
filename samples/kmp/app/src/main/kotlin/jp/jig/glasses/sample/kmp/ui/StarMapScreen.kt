package jp.jig.glasses.sample.kmp.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.jigglass.glass.CommandManager
import app.jigglass.glass.GlassClient
import jp.jig.glasses.sample.kmp.starmap.Aimed
import jp.jig.glasses.sample.kmp.starmap.Compass
import jp.jig.glasses.sample.kmp.starmap.Look
import jp.jig.glasses.sample.kmp.starmap.PANEL_HEIGHT
import jp.jig.glasses.sample.kmp.starmap.PANEL_WIDTH
import jp.jig.glasses.sample.kmp.starmap.Site
import jp.jig.glasses.sample.kmp.starmap.StarCatalog
import jp.jig.glasses.sample.kmp.starmap.StarMap
import jp.jig.glasses.sample.kmp.starmap.StarMapRenderer
import jp.jig.glasses.sample.kmp.starmap.normalizeDeg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * 座標変換パイプラインを実機で試す画面。仕様は docs/team-e/coordinate-system.md。
 *
 * まず「星座を選ぶ」で絵が出ることを確かめ、そのあと「グラスの向き」に切り替えて
 * 空と合っているかを見る、という順で使う。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StarMapScreen(client: GlassClient, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val commandManager = remember(client) { client.createCommandManager() }
    val imuStarted by commandManager.imuDataStarted.collectAsState()

    var renderer by remember { mutableStateOf<StarMapRenderer?>(null) }
    LaunchedEffect(Unit) {
        val loaded = withContext(Dispatchers.IO) { StarCatalog.load(context) }
        renderer = StarMapRenderer(loaded)
    }

    val compass = remember { Compass(context) }
    DisposableEffect(compass) {
        compass.start()
        onDispose { compass.stop() }
    }

    // 観測地は手で入れられるようにしておく。屋内でも試したいので GPS には頼らない
    var latText by remember { mutableStateOf("35.9432") }
    var lonText by remember { mutableStateOf("136.1846") }
    val site = Site(latText.toDoubleOrNull() ?: 35.9432, lonText.toDoubleOrNull() ?: 136.1846)

    // 星座を選ぶモードなら、方位合わせもグラスの姿勢も要らずに絵が出る
    var followGlasses by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf<List<Aimed>>(emptyList()) }
    var aimed by remember { mutableStateOf<Aimed?>(null) }

    var headingOffset by remember { mutableStateOf(0.0) }
    var calibratedAt by remember { mutableStateOf<Long?>(null) }
    var glassYaw by remember { mutableStateOf(0.0) }
    var glassPitch by remember { mutableStateOf(0.0) }

    var fov by remember { mutableStateOf(35f) }
    var limitMag by remember { mutableStateOf(5f) }
    var drawLines by remember { mutableStateOf(true) }
    var showDetails by remember { mutableStateOf(false) }

    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var lastMap by remember { mutableStateOf<StarMap?>(null) }
    var renderMs by remember { mutableStateOf(0L) }
    var sendMs by remember { mutableStateOf(0L) }
    var status by remember { mutableStateOf("") }

    DisposableEffect(commandManager) {
        val job: Job = scope.launch {
            commandManager.imuData.collect { data ->
                glassYaw = data.yawDegrees.toDouble()
                // ピッチは取付補正済みで上向きが負
                glassPitch = -data.pitchDegrees.toDouble()
            }
        }
        onDispose { job.cancel() }
    }

    // 空に出ている星座は時間とともに変わるので、定期的に取り直す
    LaunchedEffect(renderer, latText, lonText) {
        val r = renderer ?: return@LaunchedEffect
        while (true) {
            visible = withContext(Dispatchers.Default) {
                r.visibleConstellations(site, System.currentTimeMillis())
            }
            if (aimed == null) aimed = visible.firstOrNull()
            delay(60_000)
        }
    }

    fun look(): Look {
        if (!followGlasses) {
            val target = aimed ?: return Look(180.0, 45.0)
            return Look(target.azDeg, target.altDeg)
        }
        val az = (normalizeDeg(glassYaw + headingOffset) + 360.0) % 360.0
        return Look(az, glassPitch)
    }

    suspend fun drawAndSend() {
        val r = renderer ?: return
        val started = System.currentTimeMillis()
        val map = withContext(Dispatchers.Default) {
            r.render(
                site = site,
                epochMillis = System.currentTimeMillis(),
                look = look(),
                fovDeg = fov.toDouble(),
                limitMagnitude = limitMag.toDouble(),
                drawLines = drawLines,
            )
        }
        renderMs = System.currentTimeMillis() - started
        lastMap = map
        preview = withContext(Dispatchers.Default) { map.toPreviewBitmap() }

        val sendStarted = System.currentTimeMillis()
        runCatching {
            commandManager.sendCanvasImage(
                x = (PANEL_WIDTH - map.width) / 2,
                y = (PANEL_HEIGHT - map.height) / 2,
                width = map.width,
                height = map.height,
                grayscale = map.gray,
            )
            // テキストは画像の手前に描かれるので、星図を消さずに星座名を重ねられる
            commandManager.sendCanvasElements(
                map.labels.mapIndexed { i, label ->
                    CommandManager.CanvasElement(
                        id = i,
                        x = (label.x - 60).coerceIn(0, PANEL_WIDTH - 120),
                        y = (label.y - 10).coerceIn(0, PANEL_HEIGHT - 22),
                        width = 120,
                        height = 22,
                        text = label.text,
                    )
                },
            )
            status = ""
        }.onFailure { status = "送信に失敗: ${it.message}" }
        sendMs = System.currentTimeMillis() - sendStarted
    }

    // グラスの向きに追従するときだけ描き直し続ける。選んだ星座を見るときは 1 枚でいい
    LaunchedEffect(followGlasses, renderer) {
        if (!followGlasses) return@LaunchedEffect
        if (!imuStarted) commandManager.startImuData()
        while (true) {
            drawAndSend()
            delay(1000)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("星図") },
                navigationIcon = { OutlinedButton(onClick = onBack) { Text("戻る") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
        ) {
            if (renderer == null) {
                Text("星表を読み込み中…")
                return@Column
            }

            Row {
                FilterChip(
                    selected = !followGlasses,
                    onClick = { followGlasses = false },
                    label = { Text("星座を選ぶ") },
                )
                Spacer(Modifier.padding(4.dp))
                FilterChip(
                    selected = followGlasses,
                    onClick = { followGlasses = true },
                    label = { Text("グラスの向き") },
                )
            }
            Spacer(Modifier.height(12.dp))

            if (!followGlasses) {
                Text(
                    "選んだ星座のほうを向いた絵を出す。方位合わせもグラスの姿勢も要らない",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                if (visible.isEmpty()) {
                    Text("いま空に出ている星座がない（観測地か時刻を確かめる）")
                } else {
                    aimed?.let { Text("いま選んでいるのは ${it.nameJa}（${it.where}）") }
                    Spacer(Modifier.height(8.dp))
                    CommandButton("グラスに出す") { scope.launch { drawAndSend() } }
                    Spacer(Modifier.height(4.dp))
                    Text("いま出ている星座（高い順）", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    for (c in visible.take(20)) {
                        OutlinedButton(
                            onClick = {
                                aimed = c
                                scope.launch { drawAndSend() }
                            },
                            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                        ) {
                            Text("${c.nameJa}　${c.where}")
                        }
                    }
                }
            } else {
                Text(
                    "グラスの 6DoF に追従して 1 秒ごとに描き直す。空と合わせるには方位合わせが要る",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                Text("方位 ${look().azDeg.roundToInt()}° / 高度 ${look().altDeg.roundToInt()}°")
                Text("6DoF ${if (imuStarted) "受信中" else "停止中"}")
                calibratedAt?.let { Text("方位合わせから ${(System.currentTimeMillis() - it) / 1000} 秒") }
                    ?: Text("まだ方位を合わせていない")
                Spacer(Modifier.height(8.dp))
                CommandButton("方位を合わせる（スマホを顔の前にかざして押す）") {
                    val trueHeading = compass.trueHeadingDeg(site, System.currentTimeMillis())
                    if (trueHeading == null) {
                        status = "方位センサーが読めない"
                    } else {
                        headingOffset = normalizeDeg(trueHeading - glassYaw)
                        calibratedAt = System.currentTimeMillis()
                        status = "スマホの方位 ${trueHeading.roundToInt()}° で合わせた"
                    }
                }
            }

            preview?.let {
                Spacer(Modifier.height(12.dp))
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "グラスに出しているもの。星座名は画像に焼かず、テキストとして手前に重なる",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            if (status.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(status, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(12.dp))
            Button(onClick = { commandManager.clearCanvas() }, modifier = Modifier.fillMaxWidth()) {
                Text("グラスの表示を消す")
            }

            HorizontalDivider(Modifier.padding(vertical = 16.dp))
            Row {
                Checkbox(checked = showDetails, onCheckedChange = { showDetails = it })
                Text("細かい設定を出す", Modifier.padding(top = 14.dp))
            }

            if (showDetails) {
                Text("画角 ${fov.roundToInt()}°")
                Slider(value = fov, onValueChange = { fov = it }, valueRange = 10f..70f)
                Text("限界等級 ${"%.1f".format(limitMag)}")
                Slider(value = limitMag, onValueChange = { limitMag = it }, valueRange = 2f..5f)
                Row {
                    Checkbox(checked = drawLines, onCheckedChange = { drawLines = it })
                    Text("星座線を描く", Modifier.padding(top = 14.dp))
                }
                Text("描画 $renderMs ms / 送信の呼び出し $sendMs ms")
                Text(
                    "送信は内部でキューイングされるので、呼び出し時間は転送完了までの時間ではない",
                    style = MaterialTheme.typography.bodySmall,
                )
                lastMap?.let { Text("ラベル ${it.labels.size} 個 / ${it.width}×${it.height}") }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = latText,
                    onValueChange = { latText = it },
                    label = { Text("緯度") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = lonText,
                    onValueChange = { lonText = it },
                    label = { Text("経度") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** 実機で見える色に寄せた確認用。3bit へ落としてから緑に写す（順序を逆にすると階調が狂う） */
private fun StarMap.toPreviewBitmap(): Bitmap {
    val pixels = IntArray(width * height)
    for (i in pixels.indices) {
        val v = gray[i].toInt() and 0xFF
        val f = Math.round(v / 255.0 * 7.0) / 7.0
        pixels[i] = (0xFF shl 24) or
            ((56 * f).toInt() shl 16) or
            ((255 * f).toInt() shl 8) or
            (116 * f).toInt()
    }
    return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
}
