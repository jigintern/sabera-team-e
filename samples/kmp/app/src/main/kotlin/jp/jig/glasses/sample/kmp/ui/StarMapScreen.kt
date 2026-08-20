package jp.jig.glasses.sample.kmp.ui

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.jigglass.glass.CommandManager
import app.jigglass.glass.GestureType
import app.jigglass.glass.GlassClient
import jp.jig.glasses.sample.kmp.BuildConfig
import jp.jig.glasses.sample.kmp.ai.NarrationInput
import jp.jig.glasses.sample.kmp.ai.NarrationPhase
import jp.jig.glasses.sample.kmp.ai.Narrator
import jp.jig.glasses.sample.kmp.ai.OpenAiClient
import jp.jig.glasses.sample.kmp.ai.Speaker
import jp.jig.glasses.sample.kmp.satellite.Observer
import jp.jig.glasses.sample.kmp.satellite.SatelliteScene
import jp.jig.glasses.sample.kmp.starmap.Compass
import jp.jig.glasses.sample.kmp.starmap.Label
import jp.jig.glasses.sample.kmp.starmap.Located
import jp.jig.glasses.sample.kmp.starmap.Locator
import jp.jig.glasses.sample.kmp.starmap.Look
import jp.jig.glasses.sample.kmp.starmap.PANEL_HEIGHT
import jp.jig.glasses.sample.kmp.starmap.PANEL_WIDTH
import jp.jig.glasses.sample.kmp.starmap.Site
import jp.jig.glasses.sample.kmp.starmap.StarCatalog
import jp.jig.glasses.sample.kmp.starmap.StarMap
import jp.jig.glasses.sample.kmp.starmap.StarMapRenderer
import jp.jig.glasses.sample.kmp.starmap.SkyDarkness
import jp.jig.glasses.sample.kmp.starmap.normalizeDeg
import jp.jig.glasses.sample.kmp.starmap.sunAltitudeDeg
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
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
fun StarMapScreen(
    client: GlassClient,
    initialHeadingOffset: Double,
    initialCalibratedAt: Long?,
    onHome: () -> Unit,
) {
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
    // 素の val にすると、長生きするコルーチン（追従ループ・ジェスチャー購読）が
    // 起動時の値を握ったままになり、あとから測位できても観測地が更新されない。
    // 委譲プロパティにしておけば、読むたび最新になる
    val site by remember {
        derivedStateOf {
            Site(latText.toDoubleOrNull() ?: DEFAULT_LAT, lonText.toDoubleOrNull() ?: DEFAULT_LON)
        }
    }

    val locator = remember { Locator(context) }
    var locateNow by remember { mutableStateOf(0) }

    // 測位は屋内だと 8 秒待って諦める。待っていることが見えないと固まったように見える
    var locating by remember { mutableStateOf(false) }

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
        locating = true
        val fresh = try {
            withTimeoutOrNull(LOCATE_TIMEOUT_MS) { locator.current() }
        } finally {
            locating = false
        }
        if (!apply(fresh, "測位") && siteSource.startsWith("手入力")) {
            log("測位できない（屋内かも）。手入力のまま", failed = true)
        }
    }

    var headingOffset by remember(initialHeadingOffset) { mutableStateOf(initialHeadingOffset) }
    var calibratedAt by remember(initialCalibratedAt) { mutableStateOf(initialCalibratedAt) }
    var glassYaw by remember { mutableStateOf(0.0) }
    var glassPitch by remember { mutableStateOf(0.0) }

    var fov by remember { mutableStateOf(35f) }
    var limitMag by remember { mutableStateOf(5f) }
    var drawLines by remember { mutableStateOf(true) }
    var imageSize by remember { mutableStateOf(ImageSize.MAX) }
    var showLabels by remember { mutableStateOf(true) }

    // 衛星の輪郭。実物大ではないアイコンなので、邪魔なら切れるようにしておく
    var showFigures by remember { mutableStateOf(true) }

    // 星座モードと人工衛星モードを行き来する。切り替えはグラスのダブルタップ
    var satelliteMode by remember { mutableStateOf(false) }
    var satellites by remember { mutableStateOf<SatelliteScene?>(null) }
    var satellitesAbove by remember { mutableStateOf(0) }
    var starlinkAbove by remember { mutableStateOf(0) }
    var sightings by remember { mutableStateOf<List<SatelliteScene.Sighting>>(emptyList()) }
    var skyDarkness by remember { mutableStateOf(SkyDarkness.NIGHT) }

    // いま出ている画像を「どの視線・どの画角で焼いたか」。印だけ動かすときにこの座標系へ乗せる
    var drawnLook by remember { mutableStateOf<Look?>(null) }
    var drawnFov by remember { mutableStateOf(0.0) }

    // 10,748 機ぶんあるので IO で読む。読み終わるまで衛星モードには入れない
    LaunchedEffect(Unit) {
        val loaded = withContext(Dispatchers.IO) { SatelliteScene.load(context) }
        satellites = loaded
        if (loaded.loaded) {
            log("人工衛星の軌道要素を読んだ")
        } else {
            // assets に TLE が入っていない。data/ を assets に足す設定が外れると起きる
            log("人工衛星の軌道要素が読めない（data/satellites.tle が無い）", failed = true)
        }
    }
    var showDetails by remember { mutableStateOf(false) }

    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var lastMap by remember { mutableStateOf<StarMap?>(null) }
    var renderMs by remember { mutableStateOf(0L) }
    // 画像の分割送信にかかる見積り。追従の間隔をこれに合わせる
    var transferMs by remember { mutableStateOf(1000L) }

    /** 実際に待った時間。見積りとどれだけ違うかを見るために出す */
    var waitMs by remember { mutableStateOf(0L) }

    // 1 パケットあたりの見積り。転送の実時間は測る手段が無いので、目で見て詰められるようにする
    var packetMs by remember { mutableStateOf(PACKET_MS_DEFAULT) }
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

    // ツルをタップすると頭が動く。判定はタップ直前の視線から取りたいので、少し過去を持っておく
    val lookHistory = remember { ArrayDeque<Triple<Long, Double, Double>>() }

    DisposableEffect(commandManager) {
        val job: Job = scope.launch {
            commandManager.imuData.collect { data ->
                glassYaw = data.yawDegrees.toDouble()
                // ピッチは取付補正済みで上向きが負
                glassPitch = -data.pitchDegrees.toDouble()
                lastImuAt = System.currentTimeMillis()
                lookHistory.addLast(Triple(lastImuAt, glassYaw, glassPitch))
                while (lookHistory.isNotEmpty() && lastImuAt - lookHistory.first().first > HISTORY_MS) {
                    lookHistory.removeFirst()
                }
            }
        }
        onDispose {
            job.cancel()
            // 切断しない限りグラス側は送り続ける。画面を出るときに止めないと、
            // 他の画面でも 10Hz のサンプルが BLE を流れ続ける
            commandManager.stopImuData()
            // ホームボタン以外（戻るキー・切断・アプリ終了）で抜けたときに、
            // 古い星図がグラスに出たままになる。閉じる手段はリモコンの戻るだけなので、ここで閉じる
            runCatching { commandManager.closeCanvas() }
        }
    }

    LaunchedEffect(Unit) { commandManager.startImuData() }

    // スマホ側の一覧。衛星モードなら 3 秒、星座モードなら 15 秒ごとに作り直す
    LaunchedEffect(satellites, satelliteMode, latText, lonText) {
        val scene = satellites ?: return@LaunchedEffect
        while (true) {
            val now = System.currentTimeMillis()
            val observer = Observer(site.latDeg, site.lonDeg)
            val computed = withContext(Dispatchers.Default) {
                Triple(
                    scene.aboveHorizon(observer, now),
                    // 星座モードでは出さない値なので数えない。10,748 機の伝播が丸ごと浮く
                    if (satelliteMode) scene.starlinkAboveHorizon(observer, now) else 0,
                    sunAltitudeDeg(site, now),
                )
            }
            sightings = computed.first
            satellitesAbove = computed.first.size
            starlinkAbove = computed.second
            skyDarkness = SkyDarkness.of(computed.third)
            // 10,748 機を数え直すので、毎秒はやりすぎ。衛星の位置は 3 秒で 3° ほどしか動かない
            delay(if (satelliteMode) 3_000 else 15_000)
        }
    }

    // 送信は呼び出しから見ると積むだけで終わる。転送しきる前に次を入れると順番待ちが伸びるので、
    // 見積り時間ぶんは次を入れずに捨てる
    val sendGate = remember { Mutex() }

    fun look(): Look = Look((normalizeDeg(glassYaw + headingOffset) + 360.0) % 360.0, glassPitch)

    /** タップの反動を避けた視線。履歴が無ければ現在値でごまかす（初回タップくらいでしか起きない） */
    fun latchedLook(): Look {
        val target = System.currentTimeMillis() - LATCH_MS
        val entry = lookHistory.lastOrNull { it.first <= target } ?: return look()
        return Look((normalizeDeg(entry.second + headingOffset) + 360.0) % 360.0, entry.third)
    }

    /** 送れたら true。送らずに帰ったときに「描いた視線」を進めると、次の描き直しが止まる */
    suspend fun drawAndSend(): Boolean {
        val r = renderer ?: return false
        if (!sendGate.tryLock()) return false
        sending = true
        try {
            val started = System.currentTimeMillis()
            val now = System.currentTimeMillis()
            val scene = satellites
            // 衛星モードのときだけ軌跡を作る。10,748 機を回しても数十 ms で終わる
            val tracks = if (satelliteMode && scene != null) {
                withContext(Dispatchers.Default) {
                    val observer = Observer(site.latDeg, site.lonDeg)
                    scene.tracksInView(observer, now, look(), fov.toDouble())
                }
            } else {
                emptyList()
            }
            val map = withContext(Dispatchers.Default) {
                r.render(
                    site = site,
                    epochMillis = now,
                    look = look(),
                    fovDeg = fov.toDouble(),
                    limitMagnitude = limitMag.toDouble(),
                    width = imageSize.width,
                    height = imageSize.height,
                    // 衛星モードでは星も星座線も出さない。同じ緑 8 階調なので、
                    // 星を残すと軌跡がその中に紛れて「どれが衛星か」が読めない
                    drawLines = drawLines && !satelliteMode,
                    maxLabels = if (showLabels) CANVAS_TEXT_SLOTS else 0,
                    tracks = tracks,
                    drawStars = !satelliteMode,
                    drawFigures = showFigures,
                )
            }
            renderMs = System.currentTimeMillis() - started
            lastMap = map

            // グラスの画像バッファを超えると SDK が例外を投げる。同じ式で先に見て、
            // 落ちる代わりに「1 段下げてくれ」と出す（星の多い空ほど圧縮後が膨らむ）
            val compressed = map.compressedBytes()
            val used = map.width * map.height * 2 + compressed
            transferMs = ((compressed + 199) / 200) * packetMs.toLong()
            if (used > IMAGE_BUFFER_BYTES) {
                preview = withContext(Dispatchers.Default) { map.toPreviewBitmap() }
                log("バッファ超過 $used > $IMAGE_BUFFER_BYTES バイト。大きさを 1 段下げる", failed = true)
                Log.w(TAG, "バッファ超過 ${map.width}x${map.height} used=$used")
                return false
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
            // 衛星モードで視野に 1 機も無いと真っ黒な絵だけが出る。
            // 切り替わったのか壊れたのか見分けが付かないので、そのことを書いておく
            val shown = if (satelliteMode && tracks.isEmpty()) {
                StarMap(map.width, map.height, map.gray, listOf(Label("衛星なし", map.width / 2, map.height / 2)))
            } else {
                map
            }
            val placed = shown.toCanvasElements()
            for (batch in placed.batched(shownLabels)) {
                commandManager.sendCanvasElements(batch)
            }
            shownLabels = placed.size
            // 印だけ動かすために、この画像を焼いた条件を覚えておく
            drawnLook = look()
            drawnFov = fov.toDouble()

            // プレビューは転送を待つ間に作る。送信の手前で作ると、そのぶんグラスに出るのが遅れる
            preview = withContext(Dispatchers.Default) { map.toPreviewBitmap() }

            val l = look()
            log(
                "送信 方位${l.azDeg.roundToInt()}° 高度${l.altDeg.roundToInt()}° " +
                    (if (satelliteMode) "衛星${tracks.size}機 " else "") +
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
            return true
        } catch (e: CancellationException) {
            // 画面を離れたときの中断。送信の失敗ではないので、そのまま上へ流す
            throw e
        } catch (e: Throwable) {
            log("失敗: ${e.message}", failed = true)
            Log.e(TAG, "drawAndSend で失敗", e)
            return false
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
    LaunchedEffect(
        renderer, imageSize, fov, limitMag, drawLines, showLabels, calibrating, satelliteMode, showFigures,
    ) {
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
                // 送れなかったとき（前の送信が居座っている・バッファ超過）に視線を進めると、
                // 次に 6° 動くまで描き直しが来ない。モードを切り替えた直後に効いてくる
                if (drawAndSend()) drawn = look()
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

    // 読み上げはスマホから鳴らす。SDK に音声出力 API が無いので、そもそもグラスからは鳴らせない
    val speaker = remember { Speaker(context) }
    DisposableEffect(speaker) { onDispose { speaker.shutdown() } }

    val narrator = remember(speaker) {
        Narrator(
            speaker = speaker,
            client = OpenAiClient(BuildConfig.OPENAI_API_KEY, BuildConfig.OPENAI_MODEL),
            log = { text, failed -> log(text, failed) },
        )
    }
    val narration by narrator.state.collectAsState()
    val speaking by speaker.speaking.collectAsState()

    // 読み上げが終わったら待機に戻す。TextToSpeech の完了通知は Speaker が拾っている
    LaunchedEffect(speaking) { if (!speaking) narrator.finishedSpeaking() }

    LaunchedEffect(Unit) {
        if (BuildConfig.OPENAI_API_KEY.isEmpty()) {
            log("OPENAI_API_KEY が設定されていない（.env を作る）", failed = true)
        }
    }

    var narrationJob by remember { mutableStateOf<Job?>(null) }
    val timestamp = remember { SimpleDateFormat("yyyy-MM-dd HH:mm z", Locale.JAPAN) }

    fun startNarration() {
        val r = renderer
        if (r == null) {
            log("星表がまだ読めていない", failed = true)
            return
        }
        val latched = latchedLook()
        narrationJob = scope.launch {
            val names = withContext(Dispatchers.Default) {
                r.constellationsNear(site, System.currentTimeMillis(), latched)
            }
            // 送るのはグラスに出ている絵そのもの。別に描き直すと、聞いている人の視界と食い違う
            val png = lastMap?.let { withContext(Dispatchers.Default) { it.toPngBase64() } }
            narrator.narrate(
                NarrationInput(
                    calibrated = calibratedAt != null,
                    altDeg = latched.altDeg,
                    azDeg = latched.azDeg,
                    constellations = names,
                    latDeg = site.latDeg,
                    lonDeg = site.lonDeg,
                    localTime = timestamp.format(Date()),
                    pngBase64 = png,
                ),
            )
        }
    }

    fun stopNarration() {
        narrationJob?.cancel()
        narrationJob = null
        narrator.stop()
        log("解説を止めた")
    }

    /** SINGLE_TAP はトグル。ツルは触れやすく、かけ直しただけで発火するので、押すたび開始では困る */
    fun toggleNarration() {
        if (narrator.busy || speaking) stopNarration() else startNarration()
    }

    // gestureEvents は SharedFlow。購読前のジェスチャーは受け取れないので、画面に入った時点で購読する
    DisposableEffect(commandManager) {
        val job: Job = scope.launch {
            commandManager.gestureEvents.collect { gesture ->
                when (gesture) {
                    GestureType.SINGLE_TAP -> toggleNarration()

                    // 「もっと詳しく」に当てていた枠。人工衛星モードとの切り替えに使う
                    GestureType.DOUBLE_TAP -> {
                        val scene = satellites
                        if (calibrating) {
                            // 合わせている間に切り替えると、十字を消したあとに
                            // 思っていないモードの星図が出てくる
                            log("方位合わせ中はモードを切り替えない")
                        } else if (scene == null || !scene.loaded) {
                            log("軌道要素が読めていない")
                        } else {
                            satelliteMode = !satelliteMode
                            log(if (satelliteMode) "人工衛星モードへ（ダブルタップ）" else "星座モードへ（ダブルタップ）")
                        }
                    }

                    // 仕様どおり長押しは方位合わせ。いつでも呼べる必要がある
                    GestureType.HOLD -> {
                        calibrating = true
                        log("方位合わせへ（長押し）")
                    }

                    else -> log("ジェスチャー ${gesture.name}（未割り当て）")
                }
            }
        }
        onDispose { job.cancel() }
    }

    /**
     * 衛星の印だけ送り直す。
     *
     * **衛星は 1 秒に 1° 動くのに、画像は 1 枚 0.5 秒かかって、その間グラスは前の絵を消す。**
     * 首が止まっている間じゅう画像を送り直すと点滅するだけなので、
     * 位置が変わるテキスト要素（1 パケット・数十 ms）だけを送る。
     */
    suspend fun moveMarkers() {
        val r = renderer ?: return
        val scene = satellites ?: return
        val baseLook = drawnLook ?: return
        val map = lastMap ?: return
        if (!satelliteMode || calibrating) return
        // 画像を送っている最中なら邪魔しない。次の機会に送ればよい
        if (!sendGate.tryLock()) return
        try {
            val now = System.currentTimeMillis()
            val moved = withContext(Dispatchers.Default) {
                val observer = Observer(site.latDeg, site.lonDeg)
                // 軌跡は焼いた時点のまま。動かすのは「いまどこにいるか」だけ。
                // 印が付くのは名前つきだけなので、スターリンク 10,748 機は回さない
                // 画角も焼いたときの値を使う。つまみを動かした直後に今の画角で投影すると、
                // 絵は前の画角のままなので印だけがずれる
                val fresh = scene.tracksInView(observer, now, baseLook, drawnFov, maxStarlink = 0)
                r.trackLabels(baseLook, drawnFov, map.width, map.height, fresh)
            }
            if (moved.isEmpty()) return
            // 衛星モードは星座名を出さないので、送り直すのは印だけ。
            // 前のフレームより数が減ったぶんは batched が空文字で消す
            val elements = StarMap(map.width, map.height, map.gray, moved).toCanvasElements()
            for (batch in elements.batched(shownLabels)) {
                commandManager.sendCanvasElements(batch)
            }
            shownLabels = elements.size
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            log("印の更新で失敗: ${e.message}", failed = true)
        } finally {
            sendGate.unlock()
        }
    }

    // 衛星モードのあいだ、印だけを動かし続ける
    LaunchedEffect(satelliteMode, calibrating) {
        if (!satelliteMode || calibrating) return@LaunchedEffect
        while (true) {
            delay(MARKER_INTERVAL_MS)
            moveMarkers()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("星図") },
                navigationIcon = {
                    TextButton(
                        onClick = {
                            commandManager.closeCanvas()
                            onHome()
                        },
                    ) {
                        Text("ホーム")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
        ) {
            if (renderer == null) {
                LoadingLine("星表（星と星座線）を読み込み中")
                Spacer(Modifier.height(12.dp))
            }
            if (satellites == null) {
                // 10,748 機ぶんの TLE。読み終わるまで衛星モードに入れないので、待ちを見せる
                LoadingLine("人工衛星の軌道要素を読み込み中")
                Spacer(Modifier.height(12.dp))
            }
            if (locating) {
                LoadingLine("観測地を測位中（屋内なら ${LOCATE_TIMEOUT_MS / 1000} 秒で諦める）")
                Spacer(Modifier.height(12.dp))
            }

            Text("グラスに映っているもの", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Card(colors = CardDefaults.cardColors(containerColor = Color.Black)) {
                val shot = preview
                Box(Modifier.fillMaxWidth().aspectRatio(PANEL_WIDTH / PANEL_HEIGHT.toFloat())) {
                    if (shot != null) {
                        Image(
                            bitmap = shot.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxWidth().background(Color.Black),
                        )
                    } else {
                        // **方位合わせの直後がここ。** 何も出ないと壊れたように見えるので、
                        // 何を待っているのかと、首を止めてほしいことを出す
                        LoadingPanel(
                            text = when {
                                renderer == null -> "星表を読み込み中"
                                calibrating -> "方位合わせ中（グラスには十字が出ている）"
                                sending -> "1 枚目を送信中（約 $transferMs ms）"
                                else -> "星図の送信を待っている"
                            },
                            hint = if (renderer != null && !calibrating) {
                                "首を止めると送ります（0.4 秒じっとする）"
                            } else {
                                null
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    if (sending && shot != null) {
                        // 転送中はグラスから絵が消える。プレビューは前の絵なので、その食い違いを出す
                        SendingChip(
                            "送信中（グラスは一時的に消える）",
                            Modifier.align(Alignment.TopStart).padding(8.dp),
                        )
                    }
                }
            }
            Text(
                if (satelliteMode) {
                    "衛星の軌跡は画像に焼き、いまの位置と名前はグラス側のテキストで重ねる（ここには出ない）"
                } else {
                    "星座名は画像に焼かず、グラス側のテキストとして手前に重なる（ここには出ない）"
                },
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
            StatusRow("モード", if (satelliteMode) "人工衛星" else "星座")
            if (satelliteMode) {
                StatusRow(
                    "空の暗さ",
                    when (skyDarkness) {
                        SkyDarkness.DAY -> "昼（衛星は肉眼では見えない）"
                        SkyDarkness.CIVIL -> "薄明（明るい衛星だけ見える）"
                        SkyDarkness.NIGHT -> "夜（日の当たった衛星が見える）"
                    },
                )
                StatusRow("頭上", "名前つき $satellitesAbove 機 / スターリンク $starlinkAbove 機")
                // 古さは取得日ではなく元期で見る。取得日はキャッシュを使い回すと嘘になるし、
                // 落とした時点で元期は数時間〜数日前なので、位置のずれはこちらで決まる
                val age = satellites?.elementAgeDays(System.currentTimeMillis())
                StatusRow(
                    "軌道要素の元期",
                    when {
                        age == null -> "分からない"
                        age < 2.0 -> "${"%.1f".format(age)} 日前（十分新しい）"
                        age < 7.0 -> "${"%.1f".format(age)} 日前（そろそろずれる。1 日 0.6°）"
                        else -> "${"%.0f".format(age)} 日前。取り直したほうがよい"
                    },
                )
            }

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
                CommandButton(
                    if (satelliteMode) "星座モードに戻す" else "人工衛星モードにする",
                ) {
                    val scene = satellites
                    if (scene == null || !scene.loaded) {
                        log("軌道要素が読めていない", failed = scene != null)
                    } else {
                        satelliteMode = !satelliteMode
                        log(if (satelliteMode) "人工衛星モードへ" else "星座モードへ")
                    }
                }
                Text(
                    "グラスのダブルタップでも切り替わる",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))

                if (satelliteMode && sightings.isNotEmpty()) {
                    Text("いま空に出ている", style = MaterialTheme.typography.titleSmall)
                    for (sighting in sightings) {
                        Text(
                            "${if (sighting.sunlit) "●" else "○"} ${sighting.name}　${sighting.where}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Text(
                        "● は日が当たっていて肉眼でも見える可能性がある。○ は地球の影",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                }

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
                            // 印の送り直しも止める。残しておくと、消した画像の上に
                            // 衛星の名前だけが 1.5 秒後に浮かんでくる
                            drawnLook = null
                            log("表示を消した")
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("表示を消す") }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("AI 解説", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(8.dp)) {
                    StatusRow(
                        "状態",
                        when {
                            narration.phase == NarrationPhase.GENERATING -> "AI に聞いている…"
                            narration.phase == NarrationPhase.SPEAKING || speaking -> "読み上げ中"
                            BuildConfig.OPENAI_API_KEY.isEmpty() -> "キー未設定（.env を作る）"
                            else -> "待機中（グラスのツルを 1 回タップ）"
                        },
                    )
                    if (narration.constellation.isNotEmpty()) {
                        StatusRow("星座", narration.constellation)
                    }
                    if (narration.phase == NarrationPhase.GENERATING) {
                        // 星座名を喋ってから返事が来るまで数秒空く。その間が見えるようにする
                        Spacer(Modifier.height(4.dp))
                        LoadingLine("AI の返事を待っている")
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        narration.text.ifEmpty { "まだ解説していない" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (narration.phase == NarrationPhase.FAILED) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
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
                if (satelliteMode) {
                    Text(
                        "衛星モードは星を描かないので、星座名・星座線・限界等級は効かない",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row {
                        Checkbox(checked = showFigures, onCheckedChange = { showFigures = it })
                        Text("主役 1 機の輪郭を出す", Modifier.padding(top = 14.dp))
                    }
                }
                Text("画角 ${fov.roundToInt()}°")
                Slider(value = fov, onValueChange = { fov = it }, valueRange = 10f..70f)
                Text("限界等級 ${"%.1f".format(limitMag)}")
                Slider(value = limitMag, onValueChange = { limitMag = it }, valueRange = 2f..5f)
                Text("1 パケットの見積り ${packetMs.roundToInt()} ms")
                Slider(value = packetMs, onValueChange = { packetMs = it }, valueRange = 8f..40f)
                Text(
                    "次の 1 枚を送るまでの待ちを決める。下げるほど追従が速くなり、" +
                        "下げすぎると送信が順番待ちで溜まって遅れて出る（絵は壊れない）",
                    style = MaterialTheme.typography.bodySmall,
                )
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

/**
 * 1 パケット（200 バイト）を書き出すのにかかる時間の初期値。
 *
 * もとは 30ms で見積もっていたが、根拠は以前の実測メモだけで、確かめ直せていない。
 * 短くしすぎても 0.6.0 の SDK が送信を直列化するので絵は壊れず、順番待ちが伸びるだけなので、
 * 画面のつまみで下げながら詰められるようにしてある。
 */
private const val PACKET_MS_DEFAULT = 20f

/**
 * 6DoF がこれより新しく届いていれば BLE は空いたとみなす、つもりの値。
 *
 * 実機で見るかぎり打ち切りは毎回「見積りの半分」の下限で起きていて、
 * 転送中も 6DoF は流れ続けている。つまりこれは転送の終わりを検出できていない。
 * 害は無い（待ちの上限は見積りのまま）ので残すが、当てにはしない。
 */
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

/**
 * 衛星の印を動かす間隔。
 *
 * 天頂を通る衛星は 1 秒に 1°（画面では 15 画素）動く。
 * 1.5 秒ごとなら 20 画素ほどの遅れで、テキストは 1 パケットなので転送も邪魔しない。
 */
private const val MARKER_INTERVAL_MS = 1_500L

/**
 * どれだけ過去の視線で星座を決めるか。
 *
 * **ツルをタップすると頭が動く。** タップ時点の視線で判定すると、押した反動で
 * 隣の星座に化けることがある（app-flow.md）。
 */
private const val LATCH_MS = 500L

/** 視線の履歴を持つ長さ。ラッチに使うぶんだけあればよい */
private const val HISTORY_MS = 3_000L

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

/**
 * AI に送る星図。
 *
 * **グラスに出したのと同じ 3bit へ落としてから渡す。** 8bit のまま送ると、
 * 実機では潰れて見えない淡い星まで写ってしまい、「見えていないもの」の解説が返る。
 * 緑には写さない（色味は表示の都合で、絵の中身とは関係がない）。
 */
private fun StarMap.toPngBase64(): String {
    val pixels = IntArray(width * height)
    for (i in pixels.indices) {
        val v = gray[i].toInt() and 0xFF
        val q = Math.round(v / 255.0 * 7.0).toInt() * 255 / 7
        pixels[i] = (0xFF shl 24) or (q shl 16) or (q shl 8) or q
    }
    val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    val out = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
    bitmap.recycle()
    return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
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
