package jp.jig.glasses.sample.kmp.ui

import android.content.Intent
import android.graphics.Bitmap
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.jigglass.glass.CommandManager
import app.jigglass.glass.GestureType
import app.jigglass.glass.GlassClient
import jp.jig.glasses.sample.kmp.BuildConfig
import jp.jig.glasses.sample.kmp.ai.NarrationInput
import jp.jig.glasses.sample.kmp.ai.NarrationPhase
import jp.jig.glasses.sample.kmp.ai.CloudVoice
import jp.jig.glasses.sample.kmp.ai.Narrator
import jp.jig.glasses.sample.kmp.ai.OpenAiClient
import jp.jig.glasses.sample.kmp.ai.OpenAiRequestTrace
import jp.jig.glasses.sample.kmp.ai.OpenAiSpeech
import jp.jig.glasses.sample.kmp.ai.SatellitePass
import jp.jig.glasses.sample.kmp.ai.Speaker
import jp.jig.glasses.sample.kmp.glass.GlassBrightness
import jp.jig.glasses.sample.kmp.glass.GlassBrightnessPrefs
import jp.jig.glasses.sample.kmp.satellite.Observer
import jp.jig.glasses.sample.kmp.satellite.SatelliteScene
import jp.jig.glasses.sample.kmp.starmap.CANVAS_IMAGE_BUFFER_BYTES
import jp.jig.glasses.sample.kmp.starmap.CANVAS_PACKET_BYTES
import jp.jig.glasses.sample.kmp.starmap.CANVAS_TEXT_SLOTS
import jp.jig.glasses.sample.kmp.starmap.CalibrationResult
import jp.jig.glasses.sample.kmp.starmap.Label
import jp.jig.glasses.sample.kmp.starmap.Located
import jp.jig.glasses.sample.kmp.starmap.Locator
import jp.jig.glasses.sample.kmp.starmap.Look
import jp.jig.glasses.sample.kmp.starmap.ObservationDefaults
import jp.jig.glasses.sample.kmp.starmap.PANEL_HEIGHT
import jp.jig.glasses.sample.kmp.starmap.PANEL_WIDTH
import jp.jig.glasses.sample.kmp.starmap.SessionLog
import jp.jig.glasses.sample.kmp.starmap.Site
import jp.jig.glasses.sample.kmp.starmap.StarCatalog
import jp.jig.glasses.sample.kmp.starmap.StarMap
import jp.jig.glasses.sample.kmp.starmap.STAR_MAP_HEIGHT
import jp.jig.glasses.sample.kmp.starmap.STAR_MAP_IMAGE_ID
import jp.jig.glasses.sample.kmp.starmap.STAR_MAP_WIDTH
import jp.jig.glasses.sample.kmp.starmap.YawDriftCorrector
import jp.jig.glasses.sample.kmp.starmap.batched
import jp.jig.glasses.sample.kmp.starmap.canvasBufferUsageBytes
import jp.jig.glasses.sample.kmp.starmap.compressedSizeBytes
import jp.jig.glasses.sample.kmp.sound.Bgm
import jp.jig.glasses.sample.kmp.sound.SoundPrefs
import jp.jig.glasses.sample.kmp.starmap.SkyDarkness
import jp.jig.glasses.sample.kmp.starmap.StarMapRenderer
import jp.jig.glasses.sample.kmp.starmap.normalizeDeg
import jp.jig.glasses.sample.kmp.starmap.sunAltitudeDeg
import jp.jig.glasses.sample.kmp.starmap.toCanvasElements
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 星図をグラスに出す観測画面。役割は 3 つ。
 *
 * - いまグラスに映っているものを見る
 * - 星座と人工衛星を切り替え、案内を始める
 * - 送信のログを見る
 *
 * 方位合わせは [CalibrationScreen] だけが担当する。星図の向きはグラスの 6DoF に追従する。
 * 仕様は docs/team-e/coordinate-system.md。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StarMapScreen(
    client: GlassClient,
    initialCalibration: CalibrationResult?,
    constellation: ConstellationBackground,
    onRecalibrate: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val commandManager = remember(client) { client.createCommandManager() }
    val imuStarted by commandManager.imuDataStarted.collectAsState()

    var renderer by remember { mutableStateOf<StarMapRenderer?>(null) }
    val logs = remember { mutableStateListOf<LogLine>() }
    val clock = remember { SimpleDateFormat("HH:mm:ss", Locale.JAPAN) }

    // 画面のログは 40 行で、画面を出ると消える。ドリフト率のような長い計測が取れないので
    // 同じ行をファイルにも残す（docs/team-e/coordinate-system.md の「実測しないと決められないこと」）
    val sessionLog = remember { SessionLog(context, scope) }

    // ファイルの大きさは Compose から見えないので、パネルを開いている間だけ拾う
    var logBytes by remember { mutableStateOf(0L) }

    fun log(text: String, failed: Boolean = false) {
        logs.add(0, LogLine(clock.format(Date()), text, failed))
        while (logs.size > LOG_LINES) logs.removeAt(logs.lastIndex)
        sessionLog.append(if (failed) "失敗  " + text else text)
        // 有線で繋がっているなら `adb logcat -s StarMap` で生で流れる。
        // 書き出しは屋外用で、机の上では logcat のほうが早い
        if (failed) Log.w(TAG, text) else Log.d(TAG, text)
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

    // 観測地はスマホの測位から取る。取れないときだけ手入力（屋内で試すときの逃げ道）
    var latText by remember { mutableStateOf("%.4f".format(ObservationDefaults.site.latDeg)) }
    var lonText by remember { mutableStateOf("%.4f".format(ObservationDefaults.site.lonDeg)) }
    var siteSource by remember { mutableStateOf("手入力（鯖江）") }
    // 素の val にすると、長生きするコルーチン（追従ループ・ジェスチャー購読）が
    // 起動時の値を握ったままになり、あとから測位できても観測地が更新されない。
    // 委譲プロパティにしておけば、読むたび最新になる
    val site by remember {
        derivedStateOf {
            Site(
                latText.toDoubleOrNull() ?: ObservationDefaults.site.latDeg,
                lonText.toDoubleOrNull() ?: ObservationDefaults.site.lonDeg,
            )
        }
    }

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
        val fresh = withTimeoutOrNull(ObservationDefaults.LOCATION_TIMEOUT_MS) { locator.current() }
        if (!apply(fresh, "測位") && siteSource.startsWith("手入力")) {
            log("測位できない（屋内かも）。手入力のまま", failed = true)
        }
    }

    val headingOffset = initialCalibration?.headingOffsetDeg ?: 0.0
    val pitchOffset = initialCalibration?.pitchOffsetDeg ?: 0.0
    val calibratedAt = initialCalibration?.calibratedAt
    var glassYaw by remember { mutableStateOf(0.0) }
    var glassPitch by remember { mutableStateOf(0.0) }

    val fov = ObservationDefaults.STAR_MAP_FOV_DEG.toFloat()
    val limitMag = ObservationDefaults.LIMIT_MAGNITUDE.toFloat()
    val drawLines = true
    val showLabels = true
    var showDetails by remember { mutableStateOf(false) }

    // 星座モードと人工衛星モードを行き来する。切り替えは上のボタンとグラスのダブルタップ
    var satelliteMode by remember { mutableStateOf(false) }
    var satellites by remember { mutableStateOf<SatelliteScene?>(null) }
    var satellitesAbove by remember { mutableStateOf(0) }
    var starlinkAbove by remember { mutableStateOf(0) }
    var sightings by remember { mutableStateOf<List<SatelliteScene.Sighting>>(emptyList()) }
    var skyDarkness by remember { mutableStateOf(SkyDarkness.NIGHT) }

    // SDK 0.6.0 は設定値の同期結果を公開していないため、
    // 最後にこのアプリから送った値だけをグラスごとに覚える
    val brightnessPrefs = remember(client.deviceIdentifier) {
        GlassBrightnessPrefs(context, client.deviceIdentifier)
    }
    val initialBrightness = remember(brightnessPrefs) { brightnessPrefs.load() }
    var brightnessLevel by remember { mutableStateOf(initialBrightness.level) }
    var brightnessConfigured by remember { mutableStateOf(initialBrightness.configured) }
    var brightnessSendJob by remember { mutableStateOf<Job?>(null) }

    // 衛星の輪郭。実物大ではないアイコンなので、邪魔なら切れるようにしておく
    var showFigures by remember { mutableStateOf(true) }

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

    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var lastMap by remember { mutableStateOf<StarMap?>(null) }
    var renderMs by remember { mutableStateOf(0L) }
    // 画像の分割送信にかかる見積り。追従の間隔をこれに合わせる
    var transferMs by remember { mutableStateOf(1000L) }

    /** 実際に待った時間。見積りとどれだけ違うかを見るために出す */
    var waitMs by remember { mutableStateOf(0L) }

    // 1 パケットあたりの見積り。転送の実時間は測る手段が無いので、目で見て詰められるようにする
    val packetMs = PACKET_MS_DEFAULT
    var sending by remember { mutableStateOf(false) }
    var shownLabels by remember { mutableStateOf(0) }

    // 6DoF のサンプルが着いた時刻。初回受信待ちとログに使う
    var lastImuAt by remember { mutableStateOf(0L) }
    /**
     * 方位の本命。**動いている間だけ `yawDegrees` の変化を足し、静止中は何も足さない。**
     *
     * 実測でヨーは静止中も 44°/分（0.74°/秒）流れる。一方ジャイロを自前で積分すると
     * 静止中はほぼ止まるが、**10Hz しか届かないので回転を 9% 取りこぼす**
     * （90° 回して 84〜99%。一度も 100% を超えないので取りこぼしと確定）。
     *
     * 穴が逆なので組み合わせる。**回転のスケールはファームの融合値が正しく、
     * ドリフトが効くのは静止中**なので、静止中を捨てれば両方の弱点が消える。
     * 漏れるのは「動いている時間 × 0.74°/秒」だけで、2 秒の首振りなら 1.5°。
     *
     * 「動いているか」は**ジャイロの大きさ**で決める。ヨーとは独立なのでドリフトに騙されない。
     */
    val yawCorrector = remember { YawDriftCorrector() }
    var fusedYaw by remember { mutableStateOf<Double?>(null) }
    var driftHeldDeg by remember { mutableStateOf(0.0) }
    var driftRateDps by remember { mutableStateOf(0.0) }

    // 方位は fusedYaw から取る。まだ 1 サンプルも来ていない間だけ生のヨーで代用する
    fun yawNow(): Double = fusedYaw ?: glassYaw

    // ツルをタップすると頭が動く。判定はタップ直前の視線から取りたいので、少し過去を持っておく
    val lookHistory = remember { ArrayDeque<Triple<Long, Double, Double>>() }

    DisposableEffect(commandManager) {
        val job: Job = scope.launch {
            commandManager.imuData.collect { data ->
                glassYaw = data.yawDegrees.toDouble()
                // ピッチは取付補正済みで上向きが負
                glassPitch = -data.pitchDegrees.toDouble()
                lastImuAt = System.currentTimeMillis()
                val corrected = yawCorrector.update(
                    rawYawDeg = glassYaw,
                    gyroXDps = data.gyroXDps.toDouble(),
                    gyroYDps = data.gyroYDps.toDouble(),
                    gyroZDps = data.gyroZDps.toDouble(),
                    timestampMs = data.timestampMs,
                )
                fusedYaw = corrected.yawDeg
                driftHeldDeg = corrected.heldDriftDeg
                driftRateDps = corrected.driftRateDps
                // 履歴も look() と同じ基準で積む。生のヨーを混ぜると解説の星座がずれる
                lookHistory.addLast(Triple(lastImuAt, yawNow(), glassPitch))
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
            // 戻るキー・切断・アプリ終了で抜けたときに古い星図が出たままになる。
            // 閉じる手段はリモコンの戻るだけなので、ここで閉じる
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
            delay(if (satelliteMode) SATELLITE_REFRESH_MS else CONSTELLATION_REFRESH_MS)
        }
    }

    // 送信は呼び出しから見ると積むだけで終わる。転送しきる前に次を入れると順番待ちが伸びるので、
    // 見積り時間ぶんは次を入れずに捨てる
    val sendGate = remember { Mutex() }

    // BRIGHTNESS_LEVEL は設定を送っただけでは見た目が変わらず、次の再描画で反映される。
    // その場で送り直せるよう、実際にグラスへ送った最後の画像だけを持つ
    var lastSentMap by remember { mutableStateOf<StarMap?>(null) }

    fun applyBrightness(level: Int) {
        val normalized = GlassBrightness.normalize(level)
        if (normalized == brightnessLevel && brightnessConfigured) return
        brightnessLevel = normalized
        brightnessSendJob?.cancel()
        brightnessSendJob = scope.launch {
            // スライダーを横切った途中の段階を全部BLEへ積まない。指が止まった最新値だけ送る
            delay(BRIGHTNESS_CHANGE_DEBOUNCE_MS)
            sendGate.withLock {
                // sendCanvasImage() は呼び出し直後に返る。ここを中断すると次の値の再描画が重なるため、
                // 見積り転送時間までは排他を保つ
                withContext(NonCancellable) {
                    sending = true
                    try {
                        // 自動調整が有効な間は手動値が表示へ反映されない。
                        // OFF を先に処理させ、最新のスライダー値だけを続けて送る
                        commandManager.sendSetting(CommandManager.SettingKey.BRIGHTNESS_AUTO, false)
                        delay(BRIGHTNESS_SETTING_GAP_MS)
                        commandManager.sendSetting(CommandManager.SettingKey.BRIGHTNESS_LEVEL, normalized)
                        brightnessPrefs.save(normalized)
                        brightnessConfigured = true

                        val map = lastSentMap
                        if (map == null) {
                            log(
                                "グラスの明るさ: 手動 ${normalized + 1}/${GlassBrightness.levelRange.count()}" +
                                    "（${GlassBrightness.label(normalized)}）を保存。次の描画で反映",
                            )
                        } else {
                            val startedAt = System.currentTimeMillis()
                            commandManager.sendCanvasImage(
                                id = STAR_MAP_IMAGE_ID,
                                x = (PANEL_WIDTH - map.width) / 2,
                                y = (PANEL_HEIGHT - map.height) / 2,
                                width = map.width,
                                height = map.height,
                                grayscale = map.gray,
                            )
                            val redrawMs = (
                                (map.compressedSizeBytes() + CANVAS_PACKET_BYTES - 1) / CANVAS_PACKET_BYTES
                                ) * packetMs.toLong()
                            delay(redrawMs + SETTLE_MS)
                            waitMs = System.currentTimeMillis() - startedAt
                            log(
                                "グラスの明るさ: 手動 ${normalized + 1}/${GlassBrightness.levelRange.count()}" +
                                    "（${GlassBrightness.label(normalized)}）を送信し、星図を再描画",
                            )
                        }
                    } finally {
                        sending = false
                    }
                }
            }
        }
    }

    fun look(): Look = Look(
        (normalizeDeg(yawNow() + headingOffset) + 360.0) % 360.0,
        (glassPitch + pitchOffset).coerceIn(-90.0, 90.0),
    )

    /** タップの反動を避けた視線。履歴が無ければ現在値でごまかす（初回タップくらいでしか起きない） */
    fun latchedLook(): Look {
        val target = System.currentTimeMillis() - LATCH_MS
        val entry = lookHistory.lastOrNull { it.first <= target } ?: return look()
        return Look(
            (normalizeDeg(entry.second + headingOffset) + 360.0) % 360.0,
            (entry.third + pitchOffset).coerceIn(-90.0, 90.0),
        )
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
            // 衛星モードのときだけ位置を出す。10,748 機を回しても数十 ms で終わる
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
                    width = STAR_MAP_WIDTH,
                    height = STAR_MAP_HEIGHT,
                    // 衛星モードでは星も星座線も出さない。同じ緑 8 階調なので、
                    // 星を残すと衛星の点がその中に紛れて「どれが衛星か」が読めない
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
            val compressed = map.compressedSizeBytes()
            val used = map.canvasBufferUsageBytes()
            transferMs = ((compressed + CANVAS_PACKET_BYTES - 1) / CANVAS_PACKET_BYTES) * packetMs.toLong()
            if (used > CANVAS_IMAGE_BUFFER_BYTES) {
                preview = withContext(Dispatchers.Default) { map.toPreviewBitmap() }
                log("バッファ超過 $used > $CANVAS_IMAGE_BUFFER_BYTES バイト", failed = true)
                Log.w(TAG, "バッファ超過 ${map.width}x${map.height} used=$used")
                return false
            }

            val sendStarted = System.currentTimeMillis()
            // 画像を先に積み、星座名はその後ろに続ける。0.6.0 の直列化でチャンクは混ざらないので、
            // 名前は画像が届き切った直後に載る。順番を逆にすると、転送中に名前だけが浮いて見える
            commandManager.sendCanvasImage(
                id = STAR_MAP_IMAGE_ID,
                x = (PANEL_WIDTH - map.width) / 2,
                y = (PANEL_HEIGHT - map.height) / 2,
                width = map.width,
                height = map.height,
                grayscale = map.gray,
            )
            lastSentMap = map
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
            // 6DoF は画像転送中も流れるため、転送完了の目印にはならない。実測したパケット時間で待つ。
            delay(transferMs + SETTLE_MS)
            waitMs = System.currentTimeMillis() - sendStarted
            Log.d(TAG, "待ち ${waitMs}ms（見積り ${transferMs + SETTLE_MS}ms）")
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
     * 1 枚の転送中はグラスが前の絵を捨てて何も出さない。動くたびに送ると
     * 表示より転送のほうが長く、点いては消えるだけになる（実機で確認）。
     * 動いている間は前の絵を出したままにして、止まってから 1 枚だけ送る。
     */
    var settled by remember { mutableStateOf(true) }
    LaunchedEffect(renderer, satelliteMode, showFigures) {
        if (renderer == null) return@LaunchedEffect
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

    /** 生のヨーと補正後の方位を定期記録し、補正が実機で効き続けているか確認できるようにする。 */
    LaunchedEffect(calibratedAt) {
        if (calibratedAt == null) return@LaunchedEffect
        while (lastImuAt == 0L) delay(POLL_MS)
        val baseAt = System.currentTimeMillis()
        log("ドリフト監視 開始 基準yaw=%.1f°".format(glassYaw))
        var previousYaw = glassYaw
        var previousFused = yawNow()
        var previousAt = baseAt
        while (true) {
            delay(DRIFT_LOG_MS)
            val now = System.currentTimeMillis()
            val minutes = (now - previousAt) / 60_000.0
            val rate = normalizeDeg(glassYaw - previousYaw) / minutes
            val fusedRate = normalizeDeg(yawNow() - previousFused) / minutes
            log(
                ("ドリフト監視 経過=%.1f分 生yaw=%.1f°(%+.1f°/分) 方位=%.1f°(%+.1f°/分) " +
                    "止めた量=%.0f° 推定=%+.3f°/秒 静止=%s")
                    .format(
                        (now - baseAt) / 60_000.0,
                        glassYaw,
                        rate,
                        yawNow(),
                        fusedRate,
                        driftHeldDeg,
                        driftRateDps,
                        if (settled) "はい" else "いいえ",
                    ),
            )
            previousYaw = glassYaw
            previousFused = yawNow()
            previousAt = now
        }
    }

    LaunchedEffect(showDetails) {
        while (showDetails) {
            logBytes = sessionLog.bytes
            delay(LOG_SIZE_POLL_MS)
        }
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
        if (!satelliteMode) return
        // 画像を送っている最中なら邪魔しない。次の機会に送ればよい
        if (!sendGate.tryLock()) return
        try {
            val now = System.currentTimeMillis()
            val moved = withContext(Dispatchers.Default) {
                val observer = Observer(site.latDeg, site.lonDeg)
                // 輪郭は焼いた時点のまま。動かすのは「いまどこにいるか」だけ。
                // 印が付くのは名前つきだけなので、スターリンク 10,748 機は回さない。
                // 画角も焼いたときの値を使う（いまの画角で投影すると印だけずれる）
                val fresh = scene.tracksInView(observer, now, baseLook, drawnFov, maxStarlink = 0)
                r.trackLabels(baseLook, drawnFov, map.width, map.height, fresh, showFigures)
            }
            // 衛星モードは星座名を出さないので、送り直すのは印だけ。
            // 前のフレームより数が減ったぶんは batched が空文字で消す。
            // 0 機になったときも早期 return しない。ここで全スロットを消さないと、
            // 視野から出た衛星名の末尾だけが右上などに残り続ける
            val elements = StarMap(map.width, map.height, map.gray, moved).toCanvasElements()
            val previousCount = if (elements.isEmpty()) CANVAS_TEXT_SLOTS else shownLabels
            for (batch in elements.batched(previousCount)) {
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
    LaunchedEffect(satelliteMode) {
        if (!satelliteMode) return@LaunchedEffect
        while (true) {
            delay(MARKER_INTERVAL_MS)
            moveMarkers()
        }
    }

    // 読み上げはスマホから鳴らす。SDK に音声出力 API が無いので、そもそもグラスからは鳴らせない。
    // 端末の TextToSpeech は棒読みで**プラネタリウムの雰囲気を壊す**ので、
    // 普段は AI 音声で喋り、作れないときだけ端末の読み上げに落ちる（CloudVoice）
    val speaker = remember { Speaker(context) }
    val voice = remember(speaker) {
        CloudVoice(
            context = context,
            speech = OpenAiSpeech(
                apiKey = BuildConfig.OPENAI_API_KEY,
                voice = BuildConfig.OPENAI_TTS_VOICE,
                model = BuildConfig.OPENAI_TTS_MODEL,
                onTrace = { trace -> scope.launch { log(trace.logLine()) } },
            ),
            fallback = speaker,
            scope = scope,
            log = { text, failed -> log(text, failed) },
        )
    }
    var aiVoice by remember { mutableStateOf(true) }
    DisposableEffect(voice) { onDispose { voice.shutdown(); speaker.shutdown() } }

    val narrator = remember(voice) {
        Narrator(
            speaker = voice,
            client = OpenAiClient(
                apiKey = BuildConfig.OPENAI_API_KEY,
                model = BuildConfig.OPENAI_MODEL,
                reasoningEffort = BuildConfig.OPENAI_REASONING_EFFORT,
                onTrace = { trace -> scope.launch { log(trace.logLine()) } },
            ),
            log = { text, failed -> log(text, failed) },
        )
    }
    val narration by narrator.state.collectAsState()
    val speaking by voice.speaking.collectAsState()
    val ttsAvailable by voice.available.collectAsState()

    // 読み上げが終わったら待機に戻す。AI 音声も端末の読み上げも、終わりは voice が拾っている
    LaunchedEffect(speaking) { if (!speaking) narrator.finishedSpeaking() }

    // BGM は解説していない間も鳴らす。**プラネタリウムの雰囲気は無音では出ない**
    val soundPrefs = remember { SoundPrefs(context) }
    var voiceVolume by remember { mutableStateOf(soundPrefs.voiceVolume) }
    var bgmVolume by remember { mutableStateOf(soundPrefs.bgmVolume) }
    var bgmOn by remember { mutableStateOf(soundPrefs.bgmEnabled) }
    val bgm = remember { Bgm(context, scope) { text, failed -> log(text, failed) } }
    val bgmTrack by bgm.track.collectAsState()
    DisposableEffect(bgm) { onDispose { bgm.release() } }

    // 曲は太陽高度で決める。時計だと同じ 19 時が夏と冬で違う空になる
    LaunchedEffect(skyDarkness, bgmOn) {
        bgm.enabled = bgmOn
        bgm.follow(skyDarkness)
    }
    // 解説が始まったら絞る。同じ音量のままだと言葉が埋もれる
    LaunchedEffect(speaking) { bgm.duck(speaking) }
    LaunchedEffect(voiceVolume) { voice.volume = voiceVolume }
    LaunchedEffect(bgmVolume) { bgm.volume = bgmVolume }

    // タップした瞬間に最初の一言を返すため、いま視野にある星座の分だけ先に作っておく。
    // 短い定型文はキャッシュに残るので、2 回目からは通信すら要らない。
    // 星図の 1 番目のラベルと解説の主役がずれることはあるが、外れてもキャッシュが当たらないだけ
    LaunchedEffect(lastMap, satelliteMode) {
        if (satelliteMode) return@LaunchedEffect
        val name = lastMap?.labels?.firstOrNull()?.text ?: return@LaunchedEffect
        voice.warm(Narrator.opening(name))
    }

    LaunchedEffect(Unit) {
        if (BuildConfig.OPENAI_API_KEY.isEmpty()) {
            log("OPENAI_API_KEY が設定されていない（.env を作る）", failed = true)
        }
    }

    LaunchedEffect(ttsAvailable) {
        // 黙っている理由が分からないのがいちばん困る。使えないなら言う
        if (ttsAvailable == false) log("読み上げが使えない（日本語の音声データが無い）", failed = true)
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

        // **衛星モードでは星座を喋らない。** グラスに出ているのは衛星の点と輪郭で、
        // 星は 1 つも描いていない。星座の解説を返すと、見えているものと食い違う
        if (satelliteMode) {
            val scene = satellites
            if (scene == null || !scene.loaded) {
                log("軌道要素が読めていない", failed = scene != null)
                return
            }
            narrationJob = scope.launch {
                val inView = withContext(Dispatchers.Default) {
                    val observer = Observer(site.latDeg, site.lonDeg)
                    // 名前つきだけ。スターリンクは名前を読み上げても意味がない
                    scene.tracksInView(
                        observer, System.currentTimeMillis(), latched, fov.toDouble(), maxStarlink = 0,
                    ).map {
                        SatellitePass(
                            name = it.name,
                            azDeg = it.nowAzDeg,
                            altDeg = it.nowAltDeg,
                            sunlit = it.sunlit,
                            closestInMinutes = it.motion?.closestInMinutes,
                            stationary = it.motion?.stationary ?: false,
                        )
                    }
                }
                narrator.narrateSatellites(inView)
            }
            return
        }

        narrationJob = scope.launch {
            val observedAt = System.currentTimeMillis()
            val (names, visibleStars) = withContext(Dispatchers.Default) {
                r.constellationsNear(site, observedAt, latched) to
                    r.visibleNamedStars(site, observedAt, latched, fov.toDouble())
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
                    visibleStars = visibleStars,
                    headingUncertaintyDeg = initialCalibration?.headingStdDeg,
                    pitchUncertaintyDeg = initialCalibration?.pitchStdDeg,
                    knownBrightStarNames = r.knownBrightStarNames(),
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

    /** モードの切り替え。上のボタンとグラスのダブルタップで同じことをする */
    fun toggleSatelliteMode() {
        val scene = satellites
        if (scene == null || !scene.loaded) {
            log("軌道要素が読めていない", failed = scene != null)
            return
        }
        satelliteMode = !satelliteMode
        // 切り替えの準備中に前のモードの絵を残すと、切り替わったように見えない
        drawnLook = null
        lastSentMap = null
        // 画像転送中に clearCanvas を積むと、その後ろから旧フレームの文字が届いて再表示される。
        // 転送が終わってから消し、次の追従描画に渡す
        scope.launch {
            sendGate.withLock {
                commandManager.clearCanvas()
                shownLabels = 0
            }
        }
        log(if (satelliteMode) "人工衛星モードへ" else "星座モードへ")
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
                    GestureType.DOUBLE_TAP -> toggleSatelliteMode()

                    // 仕様どおり長押しは方位合わせ。いつでも呼べる必要がある
                    GestureType.HOLD -> {
                        log("方位合わせへ（長押し）")
                        onRecalibrate()
                    }
                }
            }
        }
        onDispose { job.cancel() }
    }

    MaterialTheme(colorScheme = SaberaDarkColorScheme) {
        Box(Modifier.fillMaxSize()) {
            SeasonalConstellationBackground(
                constellation = constellation,
                modifier = Modifier.fillMaxSize(),
            )
            Scaffold(
                containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(if (showDetails) "星図の設定" else "現在の星空") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xA608111B),
                    titleContentColor = Color.White,
                ),
                navigationIcon = {
                    if (showDetails) {
                        TextButton(onClick = { showDetails = false }) {
                            Text("戻る", color = Color.White)
                        }
                    }
                },
                actions = {
                    if (!showDetails) {
                        TextButton(
                            onClick = { toggleSatelliteMode() },
                            colors = ButtonDefaults.textButtonColors(
                                containerColor = if (satelliteMode) Color(0xFF2D6A4F) else Color.Transparent,
                            ),
                        ) {
                            Text("衛星モード", color = Color.White)
                        }
                        TextButton(onClick = { showDetails = true }) {
                            Text("設定", color = Color.White)
                        }
                    }
                },
            )
        },
            ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
        ) {
            if (!showDetails) {
            if (satelliteMode) {
            // **星座モードと同じ並びにする**（見出し → プレビュー → 一言 → カード → ボタン 2 つ）。
            // 中身だけ衛星に差し替える
            if (satellites == null) {
                LoadingLine("人工衛星の軌道要素を読み込み中", color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(12.dp))
            }

            Text("グラスに表示している人工衛星", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            ObservationPreview(preview, sending, transferMs)
            Text(
                // 星座モードと同じ 1 行に畳む。**見えない理由（昼・影）はここで先に伝える**
                "点が位置、枠のアイコンが機体。向きを止めると更新します" +
                    "（頭上：名前つき $satellitesAbove 機 / スターリンク $starlinkAbove 機・" +
                    when (skyDarkness) {
                        SkyDarkness.DAY -> "昼なので肉眼では見えない）"
                        SkyDarkness.CIVIL -> "薄明。明るいものだけ見える）"
                        SkyDarkness.NIGHT -> "夜）"
                    },
                style = MaterialTheme.typography.bodySmall,
            )

            Spacer(Modifier.height(16.dp))
            Text("いま空に出ている", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = SaberaSurface),
            ) {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 200.dp)
                        .verticalScroll(rememberScrollState()).padding(12.dp),
                ) {
                    if (sightings.isEmpty()) {
                        Text("名前つきの衛星が空に出ていない", style = MaterialTheme.typography.bodyMedium)
                    }
                    for (sighting in sightings) {
                        Text(
                            "${if (sighting.sunlit) "●" else "○"} ${sighting.name}　${sighting.where}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        // 「上昇中・最接近まで 3 分」。点の位置だけでは待つ価値が分からない
                        if (sighting.timing.isNotEmpty()) {
                            Text(
                                "　　${sighting.timing}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "● は日が当たっていて肉眼でも見える可能性がある。○ は地球の影",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("衛星の案内", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            NarrationPanel(
                status = when {
                    narration.phase == NarrationPhase.SPEAKING || speaking -> "読み上げています"
                    ttsAvailable == false -> "読み上げが使えません（日本語の音声データが無い）"
                    else -> "グラスのツルを1回タップすると、視野の衛星を案内します"
                },
                subject = narration.constellation,
                text = narration.text,
                failed = narration.phase == NarrationPhase.FAILED,
            )
            ObservationActions(
                primaryLabel = if (narrator.busy || speaking) "案内を止める" else "この空の衛星を案内する",
                onPrimary = { toggleNarration() },
                onRecalibrate = onRecalibrate,
            )
            } else {
            if (renderer == null) {
                Text("星表を読み込み中…")
                Spacer(Modifier.height(12.dp))
            }

            Text("グラスに表示している星空", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            ObservationPreview(preview, sending, transferMs)
            Text(
                "グラスの向きを止めると、その方角の星図に更新します",
                style = MaterialTheme.typography.bodySmall,
            )

            Spacer(Modifier.height(16.dp))
            Text("AI 星座解説", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            NarrationPanel(
                status = when {
                    speaking && narration.phase == NarrationPhase.GENERATING -> "解説を受信しながら読み上げています"
                    speaking || narration.phase == NarrationPhase.SPEAKING -> "解説を読み上げています"
                    narration.phase == NarrationPhase.GENERATING -> "星座を調べています…"
                    BuildConfig.OPENAI_API_KEY.isEmpty() -> "AI解説を使うにはAPIキーの設定が必要です"
                    else -> "グラスのツルを1回タップすると解説します"
                },
                subject = narration.constellation,
                text = narration.text,
                failed = narration.phase == NarrationPhase.FAILED,
            )
            ObservationActions(
                primaryLabel = if (narrator.busy || speaking) "解説を止める" else "この星空を解説する",
                onPrimary = { toggleNarration() },
                onRecalibrate = onRecalibrate,
            )
            }
            }

            if (showDetails) {
                BrightnessSettingsCard(
                    level = brightnessLevel,
                    configured = brightnessConfigured,
                    onLevelChange = { applyBrightness(it) },
                )
                Spacer(Modifier.height(16.dp))
                StatusRow("6DoF", if (imuStarted) "受信中" else "停止中（グラスが 2.0.0 未満かも）")
                StatusRow(
                    "方位合わせ",
                    initialCalibration?.let {
                        "${(System.currentTimeMillis() - it.calibratedAt) / 1000} 秒前・" +
                            "方位±%.1f° / 仰角±%.1f°（%d件）".format(
                                it.headingStdDeg,
                                it.pitchStdDeg,
                                it.sampleCount,
                            )
                    } ?: "まだ",
                )
            Spacer(Modifier.height(16.dp))
            CommandButton("方位を合わせる", onClick = onRecalibrate)
            Row {
                OutlinedButton(
                    onClick = { scope.launch { drawAndSend() } },
                    modifier = Modifier.weight(1f),
                ) { Text("いま送る") }
                Spacer(Modifier.padding(4.dp))
                OutlinedButton(
                    onClick = {
                        lastSentMap = null
                        scope.launch {
                            sendGate.withLock {
                                commandManager.clearCanvas()
                                shownLabels = 0
                                log("表示を消した")
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("表示を消す") }
            }

            Spacer(Modifier.height(16.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(8.dp)) {
                    Text("ログ", style = MaterialTheme.typography.titleMedium)
                    // 画面に出るのは直近 40 行だけ。長い計測はファイルに残っているので、
                    // 何バイト溜まっているかを出して書き出しと消去へ導く
                    Text(
                        "記録 %.1f KB（画面は直近 %d 行）".format(logBytes / 1024.0, LOG_LINES),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text("方位: ドリフト補正あり", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(4.dp))
                    // 端末の読み上げは棒読みで雰囲気を壊す。既定は AI 音声で、
                    // 圏外や API キー無しのときは自動で端末の読み上げに落ちる
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (aiVoice) "声: AI 音声（落ち着いた解説員）" else "声: 端末の読み上げ",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(
                            checked = aiVoice,
                            onCheckedChange = {
                                aiVoice = it
                                voice.enabled = it
                                voice.stop()
                                log(if (it) "声: AI 音声にした" else "声: 端末の読み上げに戻した")
                            },
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    // 適正な音量は場所（屋外の暗騒音）と機種で変わる。ここで合わせて覚えさせる
                    Text(
                        "読み上げの音量 %d%%".format((voiceVolume * 100).roundToInt()),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Slider(
                        value = voiceVolume,
                        onValueChange = { voiceVolume = it },
                        onValueChangeFinished = { soundPrefs.voiceVolume = voiceVolume },
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (bgmOn) "BGM（いま ${bgmTrack?.label ?: "止まっている"}）" else "BGM なし",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(
                            checked = bgmOn,
                            onCheckedChange = {
                                bgmOn = it
                                soundPrefs.bgmEnabled = it
                                log(if (it) "BGM を入れた" else "BGM を止めた")
                            },
                        )
                    }
                    Text(
                        "BGM の音量 %d%%（解説中は自動で下がる）".format((bgmVolume * 100).roundToInt()),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Slider(
                        value = bgmVolume,
                        onValueChange = { bgmVolume = it },
                        onValueChangeFinished = { soundPrefs.bgmVolume = bgmVolume },
                        enabled = bgmOn,
                    )
                    // CC BY 4.0 は帰属の表示が条件。NOTICE はアプリの利用者には見えないので、
                    // ここにも出しておく
                    Text(
                        "BGM: Silver Blue Light / Fluidscape by Kevin MacLeod " +
                            "(incompetech.com) CC BY 4.0",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF8A9BA8),
                    )
                    Spacer(Modifier.height(4.dp))
                    Row {
                        OutlinedButton(
                            onClick = {
                                val intent = sessionLog.shareIntent()
                                if (intent == null) {
                                    log("記録がまだ空", failed = true)
                                } else {
                                    context.startActivity(Intent.createChooser(intent, "記録を書き出す"))
                                }
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text("記録を書き出す") }
                        Spacer(Modifier.padding(4.dp))
                        OutlinedButton(
                            onClick = {
                                sessionLog.clear()
                                logs.clear()
                                log("記録を消した。ここから計測しなおす")
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text("記録を消す") }
                    }
                    Spacer(Modifier.height(4.dp))
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 180.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
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
            }

            Spacer(Modifier.height(16.dp))
                StatusRow("画面サイズ", "${STAR_MAP_WIDTH}×${STAR_MAP_HEIGHT}")
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

/**
 * 1 パケット（200 バイト）を書き出すのにかかる時間。
 *
 * **実測は 8〜9ms**（528×330・圧縮後 6.1〜6.7KB を 12 回、実測 332〜390ms）。
 * 20ms だと見積りが倍以上になり、そのぶん次の絵を無駄に待っていた。
 *
 * 実測より少し多めの 10ms にしてある。短くしすぎても 0.6.0 の SDK が送信を直列化するので
 * 絵は壊れず順番待ちが伸びるだけなので、実測より少し多めの 10ms にしてある。
 */
private const val PACKET_MS_DEFAULT = 10f

/** ログはこの行数だけ持つ */
private const val LOG_LINES = 40

/**
 * ドリフトの材料を残す間隔。
 *
 * 1 分おきなら 30 分の観測で 30 行。画面のログ（40 行）には入らないが、
 * ファイルには残る（`SessionLog`）。
 */
private const val DRIFT_LOG_MS = 60_000L

/** 記録の大きさを拾い直す間隔。設定パネルを開いている間だけ動く */
private const val LOG_SIZE_POLL_MS = 2_000L

/** 自動調整 OFF をファームが処理してから手動値を送るまでの待ち時間 */
private const val BRIGHTNESS_SETTING_GAP_MS = 100L

/** スライダーを横切った途中の段階をBLEへ積まず、最後の段階だけ送るための待ち時間 */
private const val BRIGHTNESS_CHANGE_DEBOUNCE_MS = 200L

private fun OpenAiRequestTrace.logLine(): String = buildString {
    append(if (operation == "text") "AI文章" else "AI音声")
    append(" 試行")
    append(attempt)
    append(" 初回")
    append(firstByteMs?.let { "${it}ms" } ?: "なし")
    append(" 全体")
    append(totalMs)
    append("ms ")
    append(bytes)
    append("B ")
    append(if (completed) "完了" else "中断")
    requestId?.let { append(" requestId=").append(it) }
}

/** 追従の見張り間隔 */
private const val POLL_MS = 100L

/**
 * どれだけ過去の視線で星座を決めるか。
 *
 * **ツルをタップすると頭が動く。** タップ時点の視線で判定すると、押した反動で
 * 隣の星座に化けることがある（app-flow.md）。
 */
private const val LATCH_MS = 500L

/** 視線の履歴を持つ長さ。ラッチに使うぶんだけあればよい */
private const val HISTORY_MS = 3_000L

/**
 * 衛星の印を動かす間隔。
 *
 * 天頂を通る衛星は 1 秒に 1°（画面では 15 画素）動く。
 * 1.5 秒ごとなら 20 画素ほどの遅れで、テキストは 1 パケットなので転送も邪魔しない。
 */
private const val MARKER_INTERVAL_MS = 1_500L

private const val SATELLITE_REFRESH_MS = 3_000L
private const val CONSTELLATION_REFRESH_MS = 15_000L

/** この幅を超えて動いたら「動いている」とみなす。6DoF のふらつきは 1 度に届かない */
private const val STILL_DEG = 1.0

/** 動きが止まってからこれだけ待って送る */
private const val STILL_MS = 400L

private const val TAG = "StarMap"

/**
 * 前に送った絵からこれだけ視線がずれたら描き直す。
 *
 * 送り直すたびグラスは転送中の約 0.4 秒黙るので、少し動いたくらいでは送らない。
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
