package jp.jig.glasses.sample.kmp.ui

import android.graphics.Bitmap
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.jigglass.glass.CommandManager
import app.jigglass.glass.GlassClient
import jp.jig.glasses.sample.kmp.starmap.Compass
import jp.jig.glasses.sample.kmp.starmap.Located
import jp.jig.glasses.sample.kmp.starmap.Locator
import jp.jig.glasses.sample.kmp.starmap.Look
import jp.jig.glasses.sample.kmp.starmap.PANEL_HEIGHT
import jp.jig.glasses.sample.kmp.starmap.PANEL_WIDTH
import jp.jig.glasses.sample.kmp.starmap.Site
import jp.jig.glasses.sample.kmp.starmap.StarCatalog
import jp.jig.glasses.sample.kmp.starmap.StarMap
import jp.jig.glasses.sample.kmp.starmap.StarMapRenderer
import jp.jig.glasses.sample.kmp.starmap.normalizeDeg
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 星図をグラスに出す画面。スマホ側はこの 1 枚だけで、役割は 3 つ。
 *
 * - 方位を合わせる（グラスに絶対方位の基準が無いので、ここだけは人手が要る）
 * - いまグラスに映っているものを見る
 * - 送信のログを見る
 *
 * 星図の向きはグラスの 6DoF に追従する。仕様は docs/team-e/coordinate-system.md。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StarMapScreen(client: GlassClient) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val commandManager = remember(client) { client.createCommandManager() }
    val imuStarted by commandManager.imuDataStarted.collectAsState()

    var renderer by remember { mutableStateOf<StarMapRenderer?>(null) }
    val logs = remember { mutableStateListOf<LogLine>() }
    val clock = remember { SimpleDateFormat("HH:mm:ss", Locale.JAPAN) }

    fun log(text: String, failed: Boolean = false) {
        logs.add(0, LogLine(clock.format(Date()), text, failed))
        while (logs.size > LOG_LINES) logs.removeAt(logs.lastIndex)
    }

    LaunchedEffect(Unit) {
        val loaded = runCatching { withContext(Dispatchers.IO) { StarCatalog.load(context) } }
        loaded.onSuccess {
            renderer = StarMapRenderer(it)
            log("星表を読み込んだ")
        }.onFailure {
            // 星表が壊れていると画面が黙って止まるので、理由を出す
            log("星表を読めない: ${it.message}", failed = true)
            Log.e(TAG, "星表の読み込みで失敗", it)
        }
    }

    val compass = remember { Compass(context) }
    DisposableEffect(compass) {
        compass.start()
        onDispose { compass.stop() }
    }

    // 観測地はスマホの測位から取る。取れないときだけ手入力（屋内で試すときの逃げ道）
    var latText by remember { mutableStateOf("%.4f".format(DEFAULT_LAT)) }
    var lonText by remember { mutableStateOf("%.4f".format(DEFAULT_LON)) }
    var siteSource by remember { mutableStateOf("手入力（鯖江）") }
    val site = Site(latText.toDoubleOrNull() ?: DEFAULT_LAT, lonText.toDoubleOrNull() ?: DEFAULT_LON)

    val locator = remember { Locator(context) }
    var locateNow by remember { mutableStateOf(0) }

    fun apply(located: Located?, how: String): Boolean {
        if (located == null) return false
        latText = "%.4f".format(located.site.latDeg)
        lonText = "%.4f".format(located.site.lonDeg)
        siteSource = "$how（${located.provider}）"
        log("観測地 ${latText} / ${lonText} を $how から取った")
        return true
    }

    val askLocation = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        if (granted.values.any { it }) locateNow++ else log("位置の許可が無いので手入力のまま", failed = true)
    }

    LaunchedEffect(locateNow) {
        if (!locator.granted) {
            askLocation.launch(
                arrayOf(
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
            )
            return@LaunchedEffect
        }
        // 直近の値をすぐ使い、測り直しはその裏でやる。1km ずれても星の位置は 0.01° も動かない
        apply(locator.lastKnown(), "直近の測位")
        val fresh = withTimeoutOrNull(LOCATE_TIMEOUT_MS) { locator.current() }
        if (!apply(fresh, "測位") && siteSource.startsWith("手入力")) {
            log("測位できない（屋内かも）。手入力のまま", failed = true)
        }
    }

    var headingOffset by remember { mutableStateOf(0.0) }
    var calibratedAt by remember { mutableStateOf<Long?>(null) }
    var glassYaw by remember { mutableStateOf(0.0) }
    var glassPitch by remember { mutableStateOf(0.0) }

    var fov by remember { mutableStateOf(35f) }
    var limitMag by remember { mutableStateOf(5f) }
    var drawLines by remember { mutableStateOf(true) }
    var imageSize by remember { mutableStateOf(ImageSize.MAX) }
    var showLabels by remember { mutableStateOf(true) }
    var showDetails by remember { mutableStateOf(false) }

    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var lastMap by remember { mutableStateOf<StarMap?>(null) }
    var renderMs by remember { mutableStateOf(0L) }
    // 画像の分割送信にかかる見積り。追従の間隔をこれに合わせる
    var transferMs by remember { mutableStateOf(1000L) }

    /** 実際に待った時間。見積りとどれだけ違うかを見るために出す */
    var waitMs by remember { mutableStateOf(0L) }
    var sending by remember { mutableStateOf(false) }
    var shownLabels by remember { mutableStateOf(0) }

    // 方位合わせ中は星図を出さず、グラスに十字だけを出す
    var calibrating by remember { mutableStateOf(false) }
    var markerShown by remember { mutableStateOf(false) }
    var phoneHeading by remember { mutableStateOf<Double?>(null) }
    var phonePitch by remember { mutableStateOf<Double?>(null) }
    var compassAccuracy by remember { mutableStateOf("") }

    // 6DoF のサンプルが着いた時刻。画像の転送中は BLE が埋まって届かなくなるので、
    // 「また流れ出した ＝ 転送が終わった」の目印に使う
    var lastImuAt by remember { mutableStateOf(0L) }

    DisposableEffect(commandManager) {
        val job: Job = scope.launch {
            commandManager.imuData.collect { data ->
                glassYaw = data.yawDegrees.toDouble()
                // ピッチは取付補正済みで上向きが負
                glassPitch = -data.pitchDegrees.toDouble()
                lastImuAt = System.currentTimeMillis()
            }
        }
        onDispose {
            job.cancel()
            // 切断しない限りグラス側は送り続ける。画面を出るときに止めないと、
            // 他の画面でも 10Hz のサンプルが BLE を流れ続ける
            commandManager.stopImuData()
        }
    }

    LaunchedEffect(Unit) { commandManager.startImuData() }

    // 送信は呼び出しから見ると積むだけで終わる。転送しきる前に次を入れると順番待ちが伸びるので、
    // 見積り時間ぶんは次を入れずに捨てる
    val sendGate = remember { Mutex() }

    fun look(): Look = Look((normalizeDeg(glassYaw + headingOffset) + 360.0) % 360.0, glassPitch)

    suspend fun drawAndSend() {
        val r = renderer ?: return
        if (!sendGate.tryLock()) return
        sending = true
        try {
            val started = System.currentTimeMillis()
            val map = withContext(Dispatchers.Default) {
                r.render(
                    site = site,
                    epochMillis = System.currentTimeMillis(),
                    look = look(),
                    fovDeg = fov.toDouble(),
                    limitMagnitude = limitMag.toDouble(),
                    width = imageSize.width,
                    height = imageSize.height,
                    drawLines = drawLines,
                    maxLabels = if (showLabels) CANVAS_TEXT_SLOTS else 0,
                )
            }
            renderMs = System.currentTimeMillis() - started
            lastMap = map

            // グラスの画像バッファを超えると SDK が例外を投げる。同じ式で先に見て、
            // 落ちる代わりに「1 段下げてくれ」と出す（星の多い空ほど圧縮後が膨らむ）
            val compressed = map.compressedBytes()
            val used = map.width * map.height * 2 + compressed
            transferMs = ((compressed + 199) / 200) * 30L
            if (used > IMAGE_BUFFER_BYTES) {
                preview = withContext(Dispatchers.Default) { map.toPreviewBitmap() }
                log("バッファ超過 $used > $IMAGE_BUFFER_BYTES バイト。大きさを 1 段下げる", failed = true)
                Log.w(TAG, "バッファ超過 ${map.width}x${map.height} used=$used")
                return
            }

            val sendStarted = System.currentTimeMillis()
            // 画像を先に積み、星座名はその後ろに続ける。0.6.0 の直列化でチャンクは混ざらないので、
            // 名前は画像が届き切った直後に載る。順番を逆にすると、1 秒近く名前だけが浮いて見える
            commandManager.sendCanvasImage(
                id = STAR_MAP_IMAGE_ID,
                x = (PANEL_WIDTH - map.width) / 2,
                y = (PANEL_HEIGHT - map.height) / 2,
                width = map.width,
                height = map.height,
                grayscale = map.gray,
            )
            val placed = map.toCanvasElements()
            for (batch in placed.batched(shownLabels)) {
                commandManager.sendCanvasElements(batch)
            }
            shownLabels = placed.size

            // プレビューは転送を待つ間に作る。送信の手前で作ると、そのぶんグラスに出るのが遅れる
            preview = withContext(Dispatchers.Default) { map.toPreviewBitmap() }

            val l = look()
            log(
                "送信 方位${l.azDeg.roundToInt()}° 高度${l.altDeg.roundToInt()}° " +
                    "名前${placed.size}個 描画${renderMs}ms 転送約${transferMs}ms",
            )
            Log.d(TAG, "送信 ${map.width}x${map.height} 圧縮後=${compressed}B 使用=${used}B")
            // 転送し切るまで次を積まない。ただし 30ms/パケットは実測からの見積りでしかないので、
            // 6DoF が戻ってきたら（＝ BLE が空いたら）そこで待つのをやめる
            val deadline = sendStarted + transferMs + SETTLE_MS
            var freed = 0L
            while (System.currentTimeMillis() < deadline) {
                delay(50)
                val elapsed = System.currentTimeMillis() - sendStarted
                // 出だしは直前のサンプルが新しいので、半分は無条件に待つ
                if (elapsed > transferMs / 2 && System.currentTimeMillis() - lastImuAt < IMU_FREE_MS) {
                    freed = elapsed
                    break
                }
            }
            waitMs = if (freed > 0) freed else System.currentTimeMillis() - sendStarted
            Log.d(TAG, "待ち ${waitMs}ms（見積り ${transferMs + SETTLE_MS}ms / 6DoF で打ち切り=${freed > 0}）")
        } catch (e: CancellationException) {
            // 画面を離れたときの中断。送信の失敗ではないので、そのまま上へ流す
            throw e
        } catch (e: Throwable) {
            log("失敗: ${e.message}", failed = true)
            Log.e(TAG, "drawAndSend で失敗", e)
        } finally {
            sending = false
            sendGate.unlock()
        }
    }

    /**
     * 「首が止まったら描き直す」追従。
     *
     * 1 枚に約 1 秒かかり、その間グラスは前の絵を捨てて何も出さない。動くたびに送ると
     * 表示より転送のほうが長く、点いては消えるだけになる（実機で確認）。
     * 動いている間は前の絵を出したままにして、止まってから 1 枚だけ送る。
     */
    var settled by remember { mutableStateOf(true) }
    LaunchedEffect(renderer, imageSize, fov, limitMag, drawLines, showLabels, calibrating) {
        if (renderer == null || calibrating) return@LaunchedEffect
        var drawn: Look? = null
        var previous = look()
        var movedAt = 0L
        while (true) {
            val now = look()
            val step = max(abs(normalizeDeg(now.azDeg - previous.azDeg)), abs(now.altDeg - previous.altDeg))
            if (step > STILL_DEG) movedAt = System.currentTimeMillis()
            previous = now
            settled = System.currentTimeMillis() - movedAt > STILL_MS
            val drift = drawn?.let {
                max(abs(normalizeDeg(now.azDeg - it.azDeg)), abs(now.altDeg - it.altDeg))
            } ?: Double.MAX_VALUE
            if (settled && drift > REDRAW_DEG) {
                drawAndSend()
                drawn = look()
            }
            delay(POLL_MS)
        }
    }

    // 十字はキャンバスの別 id に置く（0.6.0 から画像を並べられる）。
    // ただしバッファは全画像の合計で見るので、星図を消してから出す
    LaunchedEffect(calibrating) {
        if (calibrating) {
            commandManager.clearCanvas()
            shownLabels = 0
            commandManager.sendCanvasImage(
                id = MARKER_IMAGE_ID,
                x = (PANEL_WIDTH - MARKER_SIZE) / 2,
                y = (PANEL_HEIGHT - MARKER_SIZE) / 2,
                width = MARKER_SIZE,
                height = MARKER_SIZE,
                grayscale = crossMarker(MARKER_SIZE),
            )
            markerShown = true
            log("方位合わせ: グラスに十字を出した")
        } else if (markerShown) {
            commandManager.removeCanvasImage(MARKER_IMAGE_ID)
            markerShown = false
        }
    }

    // Compass の値は Compose から見えないので、合わせている間だけ読み出す
    LaunchedEffect(calibrating, latText, lonText) {
        while (calibrating) {
            phoneHeading = compass.trueHeadingDeg(site, System.currentTimeMillis())
            phonePitch = compass.pitchDeg
            compassAccuracy = compass.accuracyText()
            delay(200)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("星図") })
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
        ) {
            if (renderer == null) {
                Text("星表を読み込み中…")
                Spacer(Modifier.height(12.dp))
            }

            Text("グラスに映っているもの", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Card(colors = CardDefaults.cardColors(containerColor = Color.Black)) {
                val shot = preview
                if (shot == null) {
                    Column(
                        Modifier.fillMaxWidth().aspectRatio(PANEL_WIDTH / PANEL_HEIGHT.toFloat()),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text("　まだ出していない", color = Color.Gray)
                    }
                } else {
                    Image(
                        bitmap = shot.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxWidth().background(Color.Black),
                    )
                }
            }
            Text(
                "星座名は画像に焼かず、グラス側のテキストとして手前に重なる（ここには出ない）",
                style = MaterialTheme.typography.bodySmall,
            )

            Spacer(Modifier.height(16.dp))

            val l = look()
            StatusRow("視線", "方位 ${l.azDeg.roundToInt()}° / 高度 ${l.altDeg.roundToInt()}°")
            StatusRow("6DoF", if (imuStarted) "受信中" else "停止中（グラスが 2.0.0 未満かも）")
            StatusRow(
                "方位合わせ",
                calibratedAt?.let { "${(System.currentTimeMillis() - it) / 1000} 秒前" } ?: "まだ",
            )
            StatusRow(
                "表示",
                when {
                    sending -> "送信中（約 $transferMs ms／この間グラスは前の絵を消す）"
                    settled -> "止まっている"
                    else -> "動いている（止まると送る）"
                },
            )

            Spacer(Modifier.height(16.dp))
            if (calibrating) {
                Text("方位合わせ", style = MaterialTheme.typography.titleMedium)
                Text(
                    "グラスに十字が出ている。スマホを腕の長さで持ち、" +
                        "十字がこの丸の真ん中に重なるところまで動かす。" +
                        "画面を視線に正対させると、下の仰角の差が 0 に近づく。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                Card(colors = CardDefaults.cardColors(containerColor = Color.Black)) {
                    PhoneMarker(Modifier.fillMaxWidth().height(240.dp))
                }
                Spacer(Modifier.height(8.dp))
                val tilt = phonePitch?.let { abs(it - glassPitch) }
                StatusRow(
                    "仰角",
                    "スマホ ${phonePitch?.roundToInt() ?: "—"}° / グラス ${glassPitch.roundToInt()}°" +
                        (tilt?.let { "（差 ${it.roundToInt()}°）" } ?: ""),
                )
                StatusRow("正対", if (tilt != null && tilt < FACING_DEG) "合っている" else "スマホを立て直す")
                StatusRow("スマホの方位", phoneHeading?.let { "${it.roundToInt()}°（真北基準）" } ?: "読めない")
                StatusRow("磁気センサー", compassAccuracy)
                Spacer(Modifier.height(8.dp))
                CommandButton("この向きで合わせる") {
                    val trueHeading = phoneHeading ?: compass.trueHeadingDeg(site, System.currentTimeMillis())
                    if (trueHeading == null) {
                        log("方位センサーが読めない", failed = true)
                    } else {
                        headingOffset = normalizeDeg(trueHeading - glassYaw)
                        calibratedAt = System.currentTimeMillis()
                        log(
                            "方位合わせ: スマホ ${trueHeading.roundToInt()}° / " +
                                "グラス ${glassYaw.roundToInt()}° / 仰角差 ${tilt?.roundToInt() ?: "—"}° / " +
                                "磁気 $compassAccuracy",
                        )
                        calibrating = false
                    }
                }
                OutlinedButton(
                    onClick = { calibrating = false },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("やめる") }
            } else {
                CommandButton("方位を合わせる") { calibrating = true }
                Row {
                    OutlinedButton(
                        onClick = { scope.launch { drawAndSend() } },
                        modifier = Modifier.weight(1f),
                    ) { Text("いま送る") }
                    Spacer(Modifier.padding(4.dp))
                    OutlinedButton(
                        onClick = {
                            commandManager.clearCanvas()
                            shownLabels = 0
                            log("表示を消した")
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("表示を消す") }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("ログ", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(8.dp)) {
                    if (logs.isEmpty()) {
                        Text("まだ何も送っていない", style = MaterialTheme.typography.bodySmall)
                    }
                    for (line in logs) {
                        Text(
                            "${line.at}  ${line.text}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (line.failed) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Row {
                Checkbox(checked = showDetails, onCheckedChange = { showDetails = it })
                Text("細かい設定を出す", Modifier.padding(top = 14.dp))
            }

            if (showDetails) {
                Text("星図の大きさ", style = MaterialTheme.typography.titleSmall)
                Row {
                    for (sz in ImageSize.entries) {
                        FilterChip(
                            selected = imageSize == sz,
                            onClick = { imageSize = sz },
                            label = { Text(sz.label) },
                        )
                        Spacer(Modifier.padding(2.dp))
                    }
                }
                Row {
                    Checkbox(checked = showLabels, onCheckedChange = { showLabels = it })
                    Text("星座名を出す", Modifier.padding(top = 14.dp))
                }
                Row {
                    Checkbox(checked = drawLines, onCheckedChange = { drawLines = it })
                    Text("星座線を描く（転送量の半分以上を占める）", Modifier.padding(top = 14.dp))
                }
                Text("画角 ${fov.roundToInt()}°")
                Slider(value = fov, onValueChange = { fov = it }, valueRange = 10f..70f)
                Text("限界等級 ${"%.1f".format(limitMag)}")
                Slider(value = limitMag, onValueChange = { limitMag = it }, valueRange = 2f..5f)
                lastMap?.let {
                    Text(
                        "${it.width}×${it.height} / 描画 $renderMs ms / " +
                            "転送 見積り $transferMs ms・実測 $waitMs ms",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                StatusRow("観測地", siteSource)
                OutlinedButton(
                    onClick = { locateNow++ },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("現在地を取り直す") }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = latText,
                    onValueChange = { latText = it },
                    label = { Text("緯度") },
                    isError = latText.toDoubleOrNull() == null,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = lonText,
                    onValueChange = { lonText = it },
                    label = { Text("経度") },
                    isError = lonText.toDoubleOrNull() == null,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * スマホ側のマーカー。グラスの十字をこの中心に重ねてもらう。
 *
 * 重ねる相手が「点」だと合っているか分からないので、外側の輪から中心へ絞り込む形にする。
 */
@Composable
private fun PhoneMarker(modifier: Modifier) {
    Canvas(modifier) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val r = minOf(size.width, size.height) / 2f * 0.85f
        drawCircle(Color.White, radius = r, center = center, style = Stroke(width = 5f))
        drawCircle(Color.White, radius = r * 0.5f, center = center, style = Stroke(width = 3f))
        drawCircle(Color.White, radius = 6f, center = center)
    }
}

/** ログ 1 行。失敗だけ色を変えたいので持っておく */
private data class LogLine(val at: String, val text: String, val failed: Boolean)

@Composable
private fun StatusRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.3f))
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.7f))
    }
}

/** 方位合わせの十字。星図とは別の id に置く（0.6.0 から画像を並べられる） */
private const val MARKER_IMAGE_ID = 1

/** 十字の大きさ。1px の線は 0.07° しかなく実機で見えないので、線は太くする */
private const val MARKER_SIZE = 128
private const val MARKER_THICKNESS = 2

/** スマホの画面が視線に正対しているとみなす仰角の差 */
private const val FACING_DEG = 5.0

/**
 * 方位合わせ用の十字。中心は空けておく。
 * 塗ってしまうとスマホ側のマーカーが緑に潰れて、重なり切ったかが分からない。
 */
private fun crossMarker(size: Int): ByteArray {
    val gray = ByteArray(size * size)
    val c = size / 2
    val gap = size / 8
    val arm = size / 2 - 2
    for (t in -MARKER_THICKNESS..MARKER_THICKNESS) {
        for (d in gap..arm) {
            for (s in intArrayOf(d, -d)) {
                val x = c + s
                val y = c + t
                if (x in 0 until size && y in 0 until size) gray[y * size + x] = 255.toByte()
                if (y in 0 until size && x in 0 until size) gray[x * size + y] = 255.toByte()
            }
        }
    }
    return gray
}

/** 6DoF がこれより新しく届いていれば、BLE は空いたとみなす */
private const val IMU_FREE_MS = 300L

/** 測位を待つ上限。屋内では返らないので、待ち続けない */
private const val LOCATE_TIMEOUT_MS = 8_000L

/** 観測地の既定。鯖江 */
private const val DEFAULT_LAT = 35.9432
private const val DEFAULT_LON = 136.1846

/** ログはこの行数だけ持つ */
private const val LOG_LINES = 40

/** 追従の見張り間隔 */
private const val POLL_MS = 100L

/** この幅を超えて動いたら「動いている」とみなす。6DoF のふらつきは 1 度に届かない */
private const val STILL_DEG = 1.0

/** 動きが止まってからこれだけ待って送る */
private const val STILL_MS = 400L

/**
 * 星図の大きさ。キャンバス（576×360）いっぱいには出せない。
 *
 * 0.6.0 の SDK が要求するのは `width * height * 2 + 圧縮後サイズ <= 380,000`
 * （`r0` の分岐と例外文言を逆アセンブルして確認）。576×360 は画素だけで 414,720 になるので
 * 必ず弾かれる。
 *
 * 圧縮後のサイズは向いている方角で変わる。空を 12 方位 × 高度 5 段 × 季節 4 点で回し、
 * 5 等・星座線ありの最悪値で測ると、
 * 544×340 は上限まで 2,055 バイトしか残らず、少しでも星が増えると弾かれる。
 * 528×330 なら 23,899 バイト残るので、ここをパネルと同じ 16:10 での実用上の最大とする。
 *
 * 転送量は面積でほぼ決まる。3bit RLE は真っ黒でも 32 画素で 1 バイト使うので、
 * 面積 / 32 バイトが下限。大きいほど 1 枚あたりが遅くなる。
 */
private enum class ImageSize(val width: Int, val height: Int, val label: String) {
    MAX(528, 330, "最大 528×330"),
    LARGE(512, 320, "大 512×320"),
    MEDIUM(384, 240, "中 384×240"),
    SMALL(256, 160, "小 256×160"),
}

private const val TAG = "StarMap"

/**
 * 前に送った絵からこれだけ視線がずれたら描き直す。
 *
 * 送り直すたびグラスは前の絵を捨てて約 1 秒黙るので、少し動いたくらいでは送らない。
 * 画角 35° に対しておよそ 1/6。
 */
private const val REDRAW_DEG = 6.0

/**
 * 最後のパケットを送ってからグラスが展開して描き終わるまでの余裕。
 *
 * 0.5.0 までは送信が重なるとどの画像も組み立てられなかったので厚めに取っていたが、
 * 0.6.0 で SDK が直列化したため、次のフレームのパケットは後ろに並ぶだけになった。
 */
private const val SETTLE_MS = 200L

/** 星図は 1 枚だけ置く。0.6.0 で 8 枚まで置けるが、id を固定すると送るたび同じ枠が差し替わる */
private const val STAR_MAP_IMAGE_ID = 0

/** キャンバスのテキスト要素は id 0..7 の 8 個まで */
private const val CANVAS_TEXT_SLOTS = 8

/** 1 パケットに載る内層 TLV の合計。分割送信できないので超えると SDK が弾く */
private const val CANVAS_TEXT_BUDGET = 190

/** 星座名 1 文字ぶんの幅。フォントの大きさは読み出せないので、全角が収まる側に多めに取る */
private const val LABEL_CHAR_WIDTH = 28

/** 矩形の左右の余白 */
private const val LABEL_PADDING = 8

/** 名前 1 行が縦に切れない高さ。上流のコード例も 40 を使っている */
private const val LABEL_HEIGHT = 40

/** グラスの画像バッファ。SDK は width * height * 2 + 圧縮後サイズ がこれを超えると弾く */
private const val IMAGE_BUFFER_BYTES = 380_000

/** 矩形が重なるか。重なった星座名は読めないので、後から来たほうを落とす */
private infix fun CommandManager.CanvasElement.overlaps(o: CommandManager.CanvasElement): Boolean =
    x < o.x + o.width && o.x < x + width && y < o.y + o.height && o.y < y + height

/** 要素 1 個ぶんのバイト数。TLV ヘッダ 3 + id と矩形 9 + 本文 */
private fun CommandManager.CanvasElement.byteSize(): Int = 12 + text.toByteArray(Charsets.UTF_8).size

/**
 * 星座名をキャンバスのテキスト要素にする。
 *
 * 座標は星図の中の位置なので、キャンバス上の置き場所へずらす。
 * 矩形からあふれた文字は切られるので、幅は文字数から取る。
 */
private fun StarMap.toCanvasElements(): List<CommandManager.CanvasElement> {
    val offsetX = (PANEL_WIDTH - width) / 2
    val offsetY = (PANEL_HEIGHT - height) / 2
    val shown = ArrayList<CommandManager.CanvasElement>(CANVAS_TEXT_SLOTS)
    for (label in labels) {
        if (shown.size >= CANVAS_TEXT_SLOTS) break
        // 「みなみのかんむり座」まで入る幅を取る。狭いと名前が途中で切れて読めない
        val w = (label.text.length * LABEL_CHAR_WIDTH + LABEL_PADDING).coerceAtMost(PANEL_WIDTH)
        val e = CommandManager.CanvasElement(
            id = shown.size,
            x = (offsetX + label.x - w / 2).coerceIn(0, PANEL_WIDTH - w),
            y = (offsetY + label.y - LABEL_HEIGHT / 2).coerceIn(0, PANEL_HEIGHT - LABEL_HEIGHT),
            width = w,
            height = LABEL_HEIGHT,
            text = label.text,
        )
        // 重なった名前は互いに潰し合って読めなくなる。labels は視野中心に近い順なので、先に置いたほうを残す
        if (shown.any { it overlaps e }) continue
        shown += e
    }
    return shown
}

/**
 * 190 バイトずつの束に分ける。
 * sendCanvasElements は差分更新なので、前のフレームで使った id は空文字を送って消す。
 */
private fun List<CommandManager.CanvasElement>.batched(
    previousCount: Int,
): List<List<CommandManager.CanvasElement>> {
    val cleared = (size until previousCount.coerceAtMost(CANVAS_TEXT_SLOTS)).map { i ->
        CommandManager.CanvasElement(id = i, x = 0, y = 0, width = 0, height = 0, text = "")
    }

    val batches = ArrayList<List<CommandManager.CanvasElement>>()
    var current = ArrayList<CommandManager.CanvasElement>()
    var used = 0
    for (e in this + cleared) {
        if (current.isNotEmpty() && used + e.byteSize() > CANVAS_TEXT_BUDGET) {
            batches += current
            current = ArrayList()
            used = 0
        }
        current += e
        used += e.byteSize()
    }
    if (current.isNotEmpty()) batches += current
    return batches
}

/**
 * 画像の分割送信にかかるおおよその時間。
 *
 * SDK は 3bit RLE に圧縮してから 200 バイトずつ、10ms 間隔で送る。
 * 実測ではパケット 1 本あたり 30ms 程度かかっていたので、それで見積もる。
 */
/**
 * SDK が送るバイト数。3bit の値と 32 までの連長を 1 バイトに詰める形式で、
 * `r1` の実装（値 = 画素 >>> 5、`(値 shl 5) or (連長 - 1)`）と同じ数え方をしている。
 * バッファ上限の判定にも使うので、SDK の数え方からずらさない。
 */
private fun StarMap.compressedBytes(): Int {
    var bytes = 0
    var i = 0
    val count = width * height
    while (i < count) {
        val v = (gray[i].toInt() and 0xFF) ushr 5
        var run = 1
        while (i + run < count && run < 32 && ((gray[i + run].toInt() and 0xFF) ushr 5) == v) run++
        bytes++
        i += run
    }
    return bytes
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
