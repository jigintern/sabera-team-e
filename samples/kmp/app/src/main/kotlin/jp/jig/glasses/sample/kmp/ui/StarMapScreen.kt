package jp.jig.glasses.sample.kmp.ui

import android.graphics.Bitmap
import android.util.Log
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * 座標変換パイプラインを実機で確かめる画面。仕様は docs/team-e/coordinate-system.md。
 *
 * まず「星座を選ぶ」で絵が出ることを確かめ、そのあと「グラスの向き」に切り替えて
 * 空と合っているかを見る、という順で使う。
 *
 * 出し先はキャンバス（576×360）に固定。星図を画像で置き、星座名をテキストで手前に重ねる。
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
    var imageSize by remember { mutableStateOf(ImageSize.MEDIUM) }
    // 星座名を送ると星図が消える疑いがあるので、既定では出さない
    var showLabels by remember { mutableStateOf(false) }
    var showDetails by remember { mutableStateOf(false) }

    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var lastMap by remember { mutableStateOf<StarMap?>(null) }
    var renderMs by remember { mutableStateOf(0L) }
    var sendMs by remember { mutableStateOf(0L) }
    // 画像の分割送信にかかる見積り。追従の間隔をこれに合わせる
    var transferMs by remember { mutableStateOf(1500L) }
    var status by remember { mutableStateOf("") }
    // 差分更新なので、前のフレームで使った id を消すために覚えておく。
    // 名前が変わらない限り送り直さない（テキストを送るたびに星図が消えるため）
    var shownNames by remember { mutableStateOf<List<String>>(emptyList()) }

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

    // パケットの混ざりは SDK 0.6.0 の直列化で解消したが、送信は呼び出しから見ると
    // 積むだけで終わる。追従で描き直すたび投げると転送しきれないフレームが溜まるので、
    // 見積り時間ぶんは次を入れずに捨てる
    val sendGate = remember { Mutex() }

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
        // 送信中なら捨てる。積んでも出るころには視線が変わっている
        if (!sendGate.tryLock()) return
        // 描画で落ちても黙って消えないよう、送信まで含めて丸ごと拾う
        runCatching {
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
            preview = withContext(Dispatchers.Default) { map.toPreviewBitmap() }

            val sendStarted = System.currentTimeMillis()
            // 0.6.0 から SDK が分割送信を直列化するので、テキストと画像の順序は問わない。
            // 名前を先にしているのは、画像の転送待ちのぶんラベルが早く出るため
            val names = map.labels.map { it.text }
            if (names != shownNames) {
                for (batch in map.toElementBatches(shownNames.size)) {
                    commandManager.sendCanvasElements(batch)
                }
                shownNames = names
            }
            commandManager.sendCanvasImage(
                id = STAR_MAP_IMAGE_ID,
                x = (PANEL_WIDTH - map.width) / 2,
                y = (PANEL_HEIGHT - map.height) / 2,
                width = map.width,
                height = map.height,
                grayscale = map.gray,
            )
            transferMs = map.transferMillis()
            sendMs = System.currentTimeMillis() - sendStarted
            status = ""
            Log.d(TAG, "送信 ${map.width}x${map.height} 名前=${names.size} 転送見積り=${transferMs}ms")
            // 最後のパケットが出てグラスが描き終わるまで、次の送信を入れない
            delay(transferMs + SETTLE_MS)
        }.onFailure {
            status = "失敗: ${it.message}"
            Log.e(TAG, "drawAndSend で失敗", it)
        }
        sendGate.unlock()
    }

    // 開いたらボタンを探さずに 1 枚出る。座標変換が通っているかをまず目で見るため
    var autoSent by remember { mutableStateOf(false) }
    LaunchedEffect(aimed, followGlasses) {
        if (autoSent || followGlasses || aimed == null) return@LaunchedEffect
        autoSent = true
        drawAndSend()
    }

    // グラスの向きに追従するときだけ描き直し続ける。選んだ星座を見るときは 1 枚でいい
    LaunchedEffect(followGlasses, renderer) {
        if (!followGlasses) return@LaunchedEffect
        if (!imuStarted) commandManager.startImuData()
        var drawn: Look? = null
        while (true) {
            val now = look()
            val moved = drawn == null ||
                kotlin.math.abs(normalizeDeg(now.azDeg - drawn!!.azDeg)) > REDRAW_DEG ||
                kotlin.math.abs(now.altDeg - drawn!!.altDeg) > REDRAW_DEG
            // 待ちは drawAndSend が持っている。ここでは動いたかどうかだけ見る
            if (moved) {
                drawAndSend()
                drawn = now
            } else {
                delay(150)
            }
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

            CommandButton("テスト画像（塗りつぶし）") {
                scope.launch {
                    if (!sendGate.tryLock()) {
                        status = "まだ前の画像を送っている"
                        return@launch
                    }
                    runCatching {
                        val w = imageSize.width
                        val h = imageSize.height
                        val gray = solidBlock(w, h)
                        // 星図と同じ id に送って差し替える。経路だけを切り分けたいので位置も揃える
                        commandManager.sendCanvasImage(
                            id = STAR_MAP_IMAGE_ID,
                            x = (PANEL_WIDTH - w) / 2,
                            y = (PANEL_HEIGHT - h) / 2,
                            width = w,
                            height = h,
                            grayscale = gray,
                        )
                        val wait = StarMap(w, h, gray, emptyList()).transferMillis()
                        status = "テスト画像を送った（約 $wait ms）。出ないなら経路の問題"
                        Log.d(TAG, "テスト画像 ${w}x$h 転送見積り=${wait}ms")
                        delay(wait + SETTLE_MS)
                    }.onFailure {
                        status = "テスト画像で失敗: ${it.message}"
                        Log.e(TAG, "テスト画像で失敗", it)
                    }
                    sendGate.unlock()
                }
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
            Spacer(Modifier.height(8.dp))
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
                Text("星座名も出す", Modifier.padding(top = 14.dp))
            }
            Text(
                "小さいほど転送が速く、なめらかに動く。いまの見積り 約 $transferMs ms/枚",
                style = MaterialTheme.typography.bodySmall,
            )
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
                    "グラスの 6DoF に追従して描き直す。空と合わせるには方位合わせが要る",
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
                    "グラスに出している画像。星座名は焼き込まず、テキストとして手前に重なる",
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
                Text("描画 $renderMs ms / 送信の呼び出し $sendMs ms / 画像の転送 約 $transferMs ms")
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

/**
 * 星座名をキャンバスのテキスト要素にする。
 *
 * sendCanvasElements は差分更新なので、前のフレームで使った id は消さないと残る。
 * 空文字を送るとその id が消えるので、余った枠は常に空で埋める。
 */
/**
 * 星図の大きさ。キャンバス（576×360）いっぱいには出せない。
 *
 * SDK が `width * height * 2 + 圧縮後サイズ <= 380,000` を要求するので、576×360 は
 * 画素だけで 414,720 になって必ず弾かれる。縦横比 16:10 のまま一回り小さくする。
 *
 * 転送量は面積でほぼ決まる。3bit RLE は真っ黒でも 32 画素で 1 バイト使うので、
 * 面積 / 32 バイトが下限。大きいほど 1 枚あたりが遅くなる。
 */
private enum class ImageSize(val width: Int, val height: Int, val label: String) {
    LARGE(512, 320, "大 512×320"),
    MEDIUM(384, 240, "中 384×240"),
    SMALL(256, 160, "小 256×160"),
}

private const val TAG = "StarMap"

/** これだけ視線が動いたら描き直す。止まっているのに送り直すと、そのたび画面が消えて点滅する */
private const val REDRAW_DEG = 2.0

/** 最後のパケットを送ってからグラスが展開して描き終わるまでの余裕 */
private const val SETTLE_MS = 600L

/** 星図は 1 枚だけ置く。0.6.0 で 8 枚まで置けるが、id を固定すると送るたび同じ枠が差し替わる */
private const val STAR_MAP_IMAGE_ID = 0

/** キャンバスのテキスト要素は id 0..7 の 8 個まで */
private const val CANVAS_TEXT_SLOTS = 8

/** 1 パケットに載る内層 TLV の合計。分割送信できないので超えると SDK が弾く */
private const val CANVAS_TEXT_BUDGET = 190

/** 要素 1 個ぶんのバイト数。TLV ヘッダ 3 + id と矩形 9 + 本文 */
private fun CommandManager.CanvasElement.byteSize(): Int = 12 + text.toByteArray(Charsets.UTF_8).size

/**
 * 星座名をキャンバスのテキスト要素にして、190 バイトずつの束に分ける。
 *
 * 座標は星図の中の位置なので、キャンバス上の置き場所へずらす。
 * sendCanvasElements は差分更新なので、前のフレームで使った id は空文字を送って消す。
 */
private fun StarMap.toElementBatches(previousCount: Int): List<List<CommandManager.CanvasElement>> {
    val offsetX = (PANEL_WIDTH - width) / 2
    val offsetY = (PANEL_HEIGHT - height) / 2
    val shown = labels.take(CANVAS_TEXT_SLOTS).mapIndexed { i, label ->
        CommandManager.CanvasElement(
            id = i,
            x = (offsetX + label.x - 60).coerceIn(0, PANEL_WIDTH - 120),
            y = (offsetY + label.y - 10).coerceIn(0, PANEL_HEIGHT - 22),
            width = 120,
            height = 22,
            text = label.text,
        )
    }
    val cleared = (shown.size until previousCount.coerceAtMost(CANVAS_TEXT_SLOTS)).map { i ->
        CommandManager.CanvasElement(id = i, x = 0, y = 0, width = 0, height = 0, text = "")
    }

    val batches = ArrayList<List<CommandManager.CanvasElement>>()
    var current = ArrayList<CommandManager.CanvasElement>()
    var used = 0
    for (e in shown + cleared) {
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

/** 中央に最大輝度の塊を置いただけの画像。見えるかどうかだけを確かめる */
private fun solidBlock(w: Int, h: Int): ByteArray {
    val gray = ByteArray(w * h)
    for (y in h / 4 until h * 3 / 4) {
        for (x in w / 4 until w * 3 / 4) gray[y * w + x] = 255.toByte()
    }
    return gray
}

/**
 * 画像の分割送信にかかるおおよその時間。
 *
 * SDK は 3bit RLE に圧縮してから 200 バイトずつ、10ms 間隔で送る。
 * 実測ではパケット 1 本あたり 30ms 程度かかっていたので、それで見積もる。
 */
private fun StarMap.transferMillis(): Long {
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
    return ((bytes + 199) / 200) * 30L
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
