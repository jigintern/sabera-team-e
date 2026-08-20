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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * 座標変換パイプラインを実機で試す画面。
 * 仕様は docs/team-e/coordinate-system.md で、ここはその素直な実装。
 *
 * 見たいのは「星図が空と合っているか」と「1 枚あたり何 ms かかるか」の 2 つ。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StarMapScreen(client: GlassClient, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val commandManager = remember(client) { client.createCommandManager() }
    val imuStarted by commandManager.imuDataStarted.collectAsState()

    var catalog by remember { mutableStateOf<StarCatalog?>(null) }
    var renderer by remember { mutableStateOf<StarMapRenderer?>(null) }
    LaunchedEffect(Unit) {
        val loaded = withContext(Dispatchers.IO) { StarCatalog.load(context) }
        catalog = loaded
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

    var fov by remember { mutableStateOf(35f) }
    var limitMag by remember { mutableStateOf(5f) }
    var drawLines by remember { mutableStateOf(true) }
    var fullPanel by remember { mutableStateOf(true) }
    var autoSend by remember { mutableStateOf(false) }

    // ヨーは AR 起動基準の相対値なので、絶対方位との差をオフセットとして持つ
    var headingOffset by remember { mutableStateOf(0.0) }
    var calibratedAt by remember { mutableStateOf<Long?>(null) }

    var glassYaw by remember { mutableStateOf(0.0) }
    var glassPitch by remember { mutableStateOf(0.0) }
    var imuCount by remember { mutableStateOf(0) }

    var renderMs by remember { mutableStateOf(0L) }
    var sendMs by remember { mutableStateOf(0L) }
    var lastMap by remember { mutableStateOf<StarMap?>(null) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var status by remember { mutableStateOf("") }

    DisposableEffect(commandManager) {
        val job: Job = scope.launch {
            commandManager.imuData.collect { data ->
                glassYaw = data.yawDegrees.toDouble()
                // ピッチは取付補正済みで上向きが負
                glassPitch = -data.pitchDegrees.toDouble()
                imuCount++
            }
        }
        onDispose { job.cancel() }
    }

    val azimuth = normalizeDeg(glassYaw + headingOffset).let { (it + 360.0) % 360.0 }
    val look = Look(azimuth, glassPitch)

    fun draw() {
        val r = renderer ?: return
        val w = if (fullPanel) PANEL_WIDTH else 196
        val h = if (fullPanel) PANEL_HEIGHT else 196
        val started = System.currentTimeMillis()
        val map = r.render(
            site = site,
            epochMillis = System.currentTimeMillis(),
            look = look,
            fovDeg = fov.toDouble(),
            limitMagnitude = limitMag.toDouble(),
            width = w,
            height = h,
            drawLines = drawLines,
        )
        renderMs = System.currentTimeMillis() - started
        lastMap = map
        preview = map.toPreviewBitmap()
    }

    fun send() {
        val map = lastMap ?: return
        val started = System.currentTimeMillis()
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
        }.onFailure { status = "送信に失敗: ${it.message}" }
        sendMs = System.currentTimeMillis() - started
    }

    // 送信の呼び出しは内部でキューイングされるので、詰まらないよう間隔を空けて回す
    LaunchedEffect(autoSend, renderer) {
        while (autoSend && renderer != null) {
            draw()
            send()
            kotlinx.coroutines.delay(1000)
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
            if (catalog == null) {
                Text("星表を読み込み中…")
                return@Column
            }

            SectionTitle("いまの視線")
            Text("方位 ${azimuth.roundToInt()}° / 高度 ${glassPitch.roundToInt()}°")
            Text("グラスのヨー ${glassYaw.roundToInt()}° ＋ オフセット ${headingOffset.roundToInt()}°")
            Text("6DoF ${if (imuStarted) "受信中" else "停止中"}（$imuCount サンプル）")
            calibratedAt?.let {
                Text("方位合わせから ${(System.currentTimeMillis() - it) / 1000} 秒")
            } ?: Text("まだ方位を合わせていない")
            Spacer(Modifier.height(12.dp))

            CommandButton(if (imuStarted) "6DoF を止める" else "6DoF を開始") {
                if (imuStarted) commandManager.stopImuData() else commandManager.startImuData()
            }
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

            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            SectionTitle("描きかた")
            Text("画角 ${fov.roundToInt()}°")
            Slider(value = fov, onValueChange = { fov = it }, valueRange = 10f..70f)
            Text("限界等級 ${"%.1f".format(limitMag)}")
            Slider(value = limitMag, onValueChange = { limitMag = it }, valueRange = 2f..5f)
            Row {
                Checkbox(checked = drawLines, onCheckedChange = { drawLines = it })
                Text("星座線を描く（転送量の半分以上を占める）", Modifier.padding(top = 14.dp))
            }
            Row {
                Checkbox(checked = fullPanel, onCheckedChange = { fullPanel = it })
                Text("576×360 に描く（外すと 196×196）", Modifier.padding(top = 14.dp))
            }

            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            SectionTitle("送る")
            CommandButton("1 枚だけ描いて送る") {
                draw()
                send()
            }
            Row {
                Checkbox(checked = autoSend, onCheckedChange = { autoSend = it })
                Text("1 秒ごとに描き直して送る", Modifier.padding(top = 14.dp))
            }
            Button(onClick = { commandManager.clearCanvas() }, modifier = Modifier.fillMaxWidth()) {
                Text("キャンバスを消す")
            }
            Spacer(Modifier.height(8.dp))
            Text("描画 $renderMs ms / 送信の呼び出し $sendMs ms")
            Text("※ 送信は内部でキューイングされるので、呼び出し時間は転送完了までの時間ではない")
            lastMap?.let { Text("ラベル ${it.labels.size} 個 / 画素 ${it.width}×${it.height}") }
            if (status.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(status, style = MaterialTheme.typography.bodySmall)
            }

            preview?.let {
                Spacer(Modifier.height(12.dp))
                SectionTitle("グラスに出しているもの")
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("星座名は画像に焼いていない。テキストとして手前に重なる")
            }

            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            SectionTitle("観測地")
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
        val r = (56 * f).toInt()
        val g = (255 * f).toInt()
        val b = (116 * f).toInt()
        pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
    return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
}
