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
import jp.jig.glasses.sample.kmp.ai.AskFacts
import jp.jig.glasses.sample.kmp.ai.AskGuard
import jp.jig.glasses.sample.kmp.ai.GlassMic
import jp.jig.glasses.sample.kmp.ai.NarrationInput
import jp.jig.glasses.sample.kmp.ai.OpenAiAsk
import jp.jig.glasses.sample.kmp.ai.NarrationPhase
import jp.jig.glasses.sample.kmp.ai.CloudVoice
import jp.jig.glasses.sample.kmp.ai.Narrator
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
import jp.jig.glasses.sample.kmp.starmap.ConstellationLore
import jp.jig.glasses.sample.kmp.starmap.GlassPage
import jp.jig.glasses.sample.kmp.starmap.GlassTextPage
import jp.jig.glasses.sample.kmp.starmap.cardinalDirection16
import jp.jig.glasses.sample.kmp.starmap.azimuthFromYaw
import jp.jig.glasses.sample.kmp.starmap.Label
import jp.jig.glasses.sample.kmp.starmap.LabelKind
import jp.jig.glasses.sample.kmp.starmap.Located
import jp.jig.glasses.sample.kmp.starmap.Locator
import jp.jig.glasses.sample.kmp.starmap.Look
import jp.jig.glasses.sample.kmp.starmap.SkyBodyMark
import jp.jig.glasses.sample.kmp.starmap.SolarSystemBody
import jp.jig.glasses.sample.kmp.starmap.bodiesInView
import jp.jig.glasses.sample.kmp.starmap.ObservationDefaults
import jp.jig.glasses.sample.kmp.starmap.ObservedStarFact
import jp.jig.glasses.sample.kmp.starmap.PANEL_HEIGHT
import jp.jig.glasses.sample.kmp.starmap.PANEL_WIDTH
import jp.jig.glasses.sample.kmp.starmap.SessionLog
import jp.jig.glasses.sample.kmp.starmap.Site
import jp.jig.glasses.sample.kmp.starmap.StarCatalog
import jp.jig.glasses.sample.kmp.starmap.StarMap
import jp.jig.glasses.sample.kmp.starmap.STAR_MAP_HEIGHT
import jp.jig.glasses.sample.kmp.starmap.STAR_MAP_MAX_HEIGHT
import jp.jig.glasses.sample.kmp.starmap.STAR_MAP_MAX_WIDTH
import jp.jig.glasses.sample.kmp.starmap.STAR_MAP_IMAGE_ID
import jp.jig.glasses.sample.kmp.starmap.STAR_MAP_WIDTH
import jp.jig.glasses.sample.kmp.starmap.YawDriftCorrector
import jp.jig.glasses.sample.kmp.starmap.batched
import jp.jig.glasses.sample.kmp.starmap.canvasBufferUsageBytes
import jp.jig.glasses.sample.kmp.starmap.compressedSizeBytes
import jp.jig.glasses.sample.kmp.starmap.constellationNames
import jp.jig.glasses.sample.kmp.sound.Bgm
import jp.jig.glasses.sample.kmp.sound.SoundPrefs
import jp.jig.glasses.sample.kmp.starmap.MoonPhase
import jp.jig.glasses.sample.kmp.starmap.SkyDarkness
import jp.jig.glasses.sample.kmp.starmap.SkyDensity
import jp.jig.glasses.sample.kmp.starmap.moonPhase
import jp.jig.glasses.sample.kmp.starmap.StarMapRenderer
import jp.jig.glasses.sample.kmp.starmap.normalizeDeg
import jp.jig.glasses.sample.kmp.starmap.rollFromAccel
import jp.jig.glasses.sample.kmp.starmap.sunAltitudeDeg
import jp.jig.glasses.sample.kmp.starmap.toCanvasElements
import jp.jig.glasses.sample.kmp.starmap.updatesFrom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
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
    // **子の失敗でスコープごと落とさない。** rememberCoroutineScope() は素の Job なので、
    // ここから launch / async したものが 1 つ失敗すると兄弟が全部キャンセルされる。
    // 実機では TTS の先読みが圏外で失敗したとき、6DoF の購読とログまで道連れになった
    val uiScope = rememberCoroutineScope()
    val scope = remember(uiScope) {
        CoroutineScope(uiScope.coroutineContext + SupervisorJob(uiScope.coroutineContext[Job]))
    }
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
    /**
     * 首の傾き[度]。**パネルは頭に固定されている**ので、傾けたぶん星図ごと回さないと
     * 地平線だけが水平のまま残る（実機で確認・2026-08-22）。
     */
    var glassRoll by remember { mutableStateOf(0.0) }

    /** 絵を焼いたときの傾き。印を動かすときはこちらを使う（いまの傾きで置くと印だけ回る） */
    var drawnRoll by remember { mutableStateOf(0.0) }

    // 画角はパネルと光学系の定数。**まだ実測していないので仮の値**
    val fovDeg = ObservationDefaults.STAR_MAP_FOV_DEG
    val fov = fovDeg.toFloat()

    // **空の濃さ。既定は「肉眼でよく見えるものだけ」。**
    // 5 等まで全部描くと、街の明かりでは見えていない星まで写って対応が取れない。
    // かといって絞りすぎるとスカスカで星座の形が読めないので、段階で選べるようにする
    var density by remember { mutableStateOf(SkyDensity.default) }

    val drawLines = true
    // 星座絵。**星座線だけでは「なにに見立てたのか」が伝わらない**ので既定で敷く。
    // 星より暗い段で描くので星の位置は読めるが、うるさいと感じたら設定から切れる
    var showArt by remember { mutableStateOf(true) }
    // 地平線・方位の文字・視野中心の印。**星図らしく読ませるための下敷き**
    var showGuides by remember { mutableStateOf(true) }
    val showLabels = true
    var showDetails by remember { mutableStateOf(false) }

    // **衛星は星座のおまけ**（#36）。モードで分けず、同じ星図に重ねる。
    // 衛星だけを見たい人は少数で、狙っているのは天文の初心者なので、
    // **星座＋αで衛星も見える**形にする。切り替えは上のボタンとダブルタップ
    var showSatellites by remember { mutableStateOf(true) }
    var satellites by remember { mutableStateOf<SatelliteScene?>(null) }
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

    // 衛星の輪郭（引き出し線＋枠）。**星座に重ねると邪魔なので既定はオフ**（#36）。
    // 衛星を主役にして見たいときだけ設定から出す
    var showFigures by remember { mutableStateOf(false) }

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
    // 解説の根拠は**その絵を焼いた視線**から作る。いまの視線で作り直すと、
    // グラスに出ている絵と食い違う（[drawnLook] は「送れた」絵の視線なので別に持つ）
    var lastMapLook by remember { mutableStateOf<Look?>(null) }
    // スマホ側の「いまの空」に出す。**他の星見アプリが必ず出している情報**
    var bodiesShown by remember { mutableStateOf<List<SkyBodyMark>>(emptyList()) }
    var moon by remember { mutableStateOf<MoonPhase?>(null) }
    var renderMs by remember { mutableStateOf(0L) }
    // 画像の分割送信にかかる見積り。追従の間隔をこれに合わせる
    var transferMs by remember { mutableStateOf(1000L) }

    /** 実際に待った時間。見積りとどれだけ違うかを見るために出す */
    var waitMs by remember { mutableStateOf(0L) }

    // 1 パケットあたりの見積り。転送の実時間は測る手段が無いので、目で見て詰められるようにする
    val packetMs = PACKET_MS_DEFAULT
    var sending by remember { mutableStateOf(false) }
    // **前のフレームの要素そのもの**を持つ。数だけだと、短い名前に変わったときに
    // 前の名前の末尾が消え残る（実機で「る」が右上に残った）
    var shownElements by remember { mutableStateOf(emptyList<CommandManager.CanvasElement>()) }

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
                // **首の傾きは重力から起こす**（6DoF はピッチとヨーしか返さない）。
                // 1 サンプルは揺れるので、ゆっくり寄せてから使う
                val roll = rollFromAccel(data.accelXMilliG, data.accelYMilliG, data.accelZMilliG)
                glassRoll += (normalizeDeg(roll - glassRoll)) * ROLL_SMOOTHING
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

    // スマホ側の一覧。衛星を重ねている間は 3 秒、隠している間は 15 秒ごとに作り直す。
    // **スターリンクは数えない**（群れで星座を埋めるので重ねない。伝播も丸ごと浮く）
    LaunchedEffect(satellites, showSatellites, latText, lonText) {
        val scene = satellites ?: return@LaunchedEffect
        while (true) {
            val now = System.currentTimeMillis()
            val observer = Observer(site.latDeg, site.lonDeg)
            val computed = withContext(Dispatchers.Default) {
                scene.aboveHorizon(observer, now) to sunAltitudeDeg(site, now)
            }
            sightings = computed.first
            skyDarkness = SkyDarkness.of(computed.second)
            moon = moonPhase(now)
            delay(if (showSatellites) SATELLITE_REFRESH_MS else CONSTELLATION_REFRESH_MS)
        }
    }

    // 送信は呼び出しから見ると積むだけで終わる。転送しきる前に次を入れると順番待ちが伸びるので、
    // 見積り時間ぶんは次を入れずに捨てる
    val sendGate = remember { Mutex() }

    // BRIGHTNESS_LEVEL は設定を送っただけでは見た目が変わらず、次の再描画で反映される。
    // その場で送り直せるよう、実際にグラスへ送った最後の画像だけを持つ
    var lastSentMap by remember { mutableStateOf<StarMap?>(null) }

    /**
     * 画像を上限いっぱい（544×340）で作るか。
     *
     * 入るかどうかは**空の濃さと向き**で変わる。1 度でも溢れたらその設定では諦めて標準へ落とし、
     * 設定が変わったらまた上限から試す（毎フレーム 2 回描くのは無駄なので覚えておく）。
     */
    var useMaxSize by remember(density, showArt, showGuides, showSatellites) { mutableStateOf(true) }


    /**
     * グラスに出しているページ。
     *
     * **解説は星図と同居させない**（#40）。190 バイトは 1 電文あたりの上限で画面の合計ではないので、
     * 星図の画像を消して枠 8 つを全部文字に使えば、いま喋っている量がそのまま入る。
     */
    var glassPage by remember { mutableStateOf(GlassPage.STAR_MAP) }

    /** 見出しに出す方角。解説の途中で首を動かしても書き換えない（根拠は入った時点の絵） */
    var explanationHeading by remember { mutableStateOf("") }

    /** この解説で一度でも音が鳴ったか。鳴っていないなら読む時間をたっぷり残す */
    var explanationSpoke by remember { mutableStateOf(false) }

    /** めくり切れず捨てた文字数。**実機で切れているかはログでしか分からない** */
    var explanationDropped by remember { mutableStateOf(0) }

    /** 字幕をめくっている最中か。**めくり終わる前に星図へ戻さない** */
    var explanationPaging by remember { mutableStateOf(false) }

    /** 声で聞いている最中か。**重ねて始めない**（マイクは 1 本しかない） */
    var asking by remember { mutableStateOf(false) }

    /** マイクの音の大きさ（0..1）。スマホ側に出して「聞こえている」ことを見せる */
    var micLevel by remember { mutableStateOf(0f) }

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

                        // 解説画面を出している間は星図を戻さない。文字の上に画像が重なる
                        val map = lastSentMap?.takeIf { glassPage == GlassPage.STAR_MAP }
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
        azimuthFromYaw(yawNow(), headingOffset),
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
            // **名前つき、しかも名前で分かるものだけを重ねる。** スターリンクの群れも、
            // 地球観測衛星（だいち・いぶき・しきさい…）も、初心者には名前が手がかりにならない。
            // 測位（GPS・みちびき・ガリレオ）と ISS・ひまわり・ハッブルに絞る
            val tracks = if (showSatellites && scene != null) {
                withContext(Dispatchers.Default) {
                    val observer = Observer(site.latDeg, site.lonDeg)
                    scene.tracksInView(observer, now, look(), fov.toDouble(), maxStarlink = 0)
                        .filter { track -> NOTABLE_SATELLITES.any { track.name.startsWith(it) } }
                        .take(MAX_SATELLITES_IN_VIEW)
                }
            } else {
                emptyList()
            }
            val bodies = withContext(Dispatchers.Default) {
                bodiesInView(site, now, look(), fov.toDouble()).map {
                    SkyBodyMark(
                        nameJa = it.nameJa,
                        azDeg = it.azDeg,
                        altDeg = it.altDeg,
                        magnitude = it.magnitude,
                        moon = it.nameJa == SolarSystemBody.MOON.nameJa,
                    )
                }
            }
            suspend fun renderAt(w: Int, h: Int) = withContext(Dispatchers.Default) {
                r.render(
                    site = site,
                    epochMillis = now,
                    look = look(),
                    fovDeg = fov.toDouble(),
                    limitMagnitude = density.limitMagnitude,
                    width = w,
                    height = h,
                    drawLines = drawLines,
                    maxLabels = if (showLabels) CANVAS_TEXT_SLOTS else 0,
                    tracks = tracks,
                    drawStars = true,
                    drawFigures = showFigures,
                    drawFigureArt = showArt,
                    constellationMagnitude = density.constellationMagnitude,
                    drawGuides = showGuides,
                    bodies = bodies,
                    rollDeg = glassRoll,
                )
            }
            // **上限いっぱいで描く。** 入るかどうかは空の濃さと向きで変わるので、
            // 送る前に同じ式で数えて、溢れたときだけ標準サイズへ落とす
            var map = renderAt(
                if (useMaxSize) STAR_MAP_MAX_WIDTH else STAR_MAP_WIDTH,
                if (useMaxSize) STAR_MAP_MAX_HEIGHT else STAR_MAP_HEIGHT,
            )
            if (useMaxSize && map.canvasBufferUsageBytes() > CANVAS_IMAGE_BUFFER_BYTES) {
                useMaxSize = false
                log("${STAR_MAP_MAX_WIDTH}×${STAR_MAP_MAX_HEIGHT} では入らないので落とす")
                map = renderAt(STAR_MAP_WIDTH, STAR_MAP_HEIGHT)
            }
            renderMs = System.currentTimeMillis() - started
            bodiesShown = bodies
            lastMap = map
            lastMapLook = look()

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
            // 星座が主役なので、衛星が 0 機でも画面は空にならない（「衛星なし」の札は要らない）
            val shown = map
            val placed = shown.toCanvasElements()
            for (batch in placed.batched(shownElements)) {
                commandManager.sendCanvasElements(batch)
            }
            shownElements = placed
            // 印だけ動かすために、この画像を焼いた条件を覚えておく
            drawnLook = look()
            drawnFov = fov.toDouble()
            drawnRoll = glassRoll

            // プレビューは転送を待つ間に作る。送信の手前で作ると、そのぶんグラスに出るのが遅れる
            preview = withContext(Dispatchers.Default) { map.toPreviewBitmap() }

            val l = look()
            log(
                "送信 方位${l.azDeg.roundToInt()}° 高度${l.altDeg.roundToInt()}° " +
                    (if (tracks.isEmpty()) "" else "衛星${tracks.size}機 ") +
                    (if (bodies.isEmpty()) "" else bodies.joinToString("・") { it.nameJa } + " ") +
                    "名前${placed.size}個 描画${renderMs}ms 転送約${transferMs}ms" +
                    // **解説の主役はこの並びの先頭。** ログだけで
                    // 「グラスに出た星座」と「喋った星座」を突き合わせられるようにする
                    shown.constellationNames().take(LOGGED_LABELS)
                        .takeIf { it.isNotEmpty() }
                        ?.let { " ラベル=" + it.joinToString("→") }
                        .orEmpty(),
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
     * 解説画面をグラスへ出す（#40）。
     *
     * 送るのは**変わった行だけ**（[updatesFrom]）なので、1 文ごとに呼んでよい。
     * 8 行を全部送っても 600 バイト弱・4 電文で、星図画像 1 枚の 1/10 で済む。
     */
    suspend fun sendExplanationPage(page: GlassTextPage.Page) {
        sendGate.withLock {
            // 途中で畳まれると行が半分だけ書かれた画面が残る。1 枚ぶんは最後まで送る
            withContext(NonCancellable) {
                for (batch in page.elements.updatesFrom(shownElements)) {
                    commandManager.sendCanvasElements(batch)
                }
                shownElements = page.elements
                if (page.dropped > 0) explanationDropped = page.dropped
            }
        }
    }

    /**
     * 解説画面をやめて星図へ戻す。
     *
     * **読み上げは止めない。** 首を振って戻したときは「空を見たいが話は聞いている」なので、
     * ここで黙らせると意図と食い違う。止めたいときはタップする（[toggleNarration]）。
     */
    fun leaveGlassExplanation(reason: String) {
        if (glassPage != GlassPage.EXPLANATION) return
        glassPage = GlassPage.STAR_MAP
        if (explanationDropped > 0) {
            log("解説の末尾${explanationDropped}文字はグラスに入らなかった", failed = true)
            explanationDropped = 0
        }
        log(reason)
    }

    /**
     * 「首が止まったら描き直す」追従。
     *
     * 1 枚の転送中はグラスが前の絵を捨てて何も出さない。動くたびに送ると
     * 表示より転送のほうが長く、点いては消えるだけになる（実機で確認）。
     * 動いている間は前の絵を出したままにして、止まってから 1 枚だけ送る。
     */
    var settled by remember { mutableStateOf(true) }
    LaunchedEffect(renderer, showSatellites, showFigures, showArt, showGuides, density) {
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

            // 解説画面の間は星図を送らない。文字の上に画像が重なるうえ、
            // 転送のあいだ（実測 332〜390ms）は文字ごと消える
            if (glassPage != GlassPage.STAR_MAP) {
                // 戻ったら 1 枚目をすぐ送る。ここを進めておくと 6° 動くまで星図が出てこない
                drawn = null
                // **首の向きでは戻さない。** 読んでいる途中で空を見上げただけで消えると、
                // 読み終わらないまま星図に戻ってしまう。戻すのはタップと読み上げ終了だけ
                delay(POLL_MS)
                continue
            }
            val drift = drawn?.let {
                max(abs(normalizeDeg(now.azDeg - it.azDeg)), abs(now.altDeg - it.altDeg))
            } ?: Double.MAX_VALUE
            // **首を傾けただけでも描き直す。** 方位も高度も動かないので、
            // ここを見ないと地平線が傾いたまま残る
            val rolled = abs(normalizeDeg(glassRoll - drawnRoll))
            if (settled && (drift > REDRAW_DEG || rolled > REDRAW_ROLL_DEG)) {
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
        // どの精度で始めた観測なのかを最初に残す。あとでログを読むとき、
        // これが分からないと以降の数字の意味が決まらない
        log("方位合わせ=スマホ同期 ばらつき=±%.1f°".format(initialCalibration.headingStdDeg))
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
        if (!showSatellites) return
        // 解説画面の枠を衛星の印で上書きしない
        if (glassPage != GlassPage.STAR_MAP) return
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
                r.trackLabels(
                    baseLook, drawnFov, map.width, map.height, fresh, showFigures, drawnRoll,
                )
            }
            // **星座名は動かさない。** 絵が同じなので位置も変わらない。
            // ここで星座名を落とすと、衛星の印を動かすたびに星座名が消える。
            // 0 機になっても早期 return しない（視野から出た衛星名の末尾が残る）
            val kept = map.labels.filterNot { it.kind == LabelKind.SATELLITE }
            val elements = StarMap(map.width, map.height, map.gray, kept + moved).toCanvasElements()
            for (batch in elements.batched(shownElements)) {
                commandManager.sendCanvasElements(batch)
            }
            shownElements = elements
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            log("印の更新で失敗: ${e.message}", failed = true)
        } finally {
            sendGate.unlock()
        }
    }

    // 衛星を重ねているあいだ、印だけを動かし続ける
    LaunchedEffect(showSatellites) {
        if (!showSatellites) return@LaunchedEffect
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
            caller = scope,
            log = { text, failed -> log(text, failed) },
        )
    }
    var aiVoice by remember { mutableStateOf(true) }
    DisposableEffect(voice) { onDispose { voice.shutdown(); speaker.shutdown() } }

    // **解説文は端末が持つ。** 星を見に行く場所は電波が届かないことが多く、
    // その場で AI に作らせていた頃は、圏外だと一言も出せなかった
    val lore = remember { ConstellationLore.load(context) }
    LaunchedEffect(lore) { log("解説文を ${lore.size} 星座ぶん読んだ") }

    val narrator = remember(voice, lore) {
        Narrator(
            speaker = voice,
            lore = { name -> lore.of(name) },
            log = { text, failed -> log(text, failed) },
        )
    }
    // 声で聞く経路（#38）。**マイクはグラス側**なので端末の録音権限は要らない
    val mic = remember(commandManager) { GlassMic(commandManager) }
    val ask = remember {
        OpenAiAsk(
            apiKey = BuildConfig.OPENAI_API_KEY,
            transcribeModel = BuildConfig.OPENAI_TRANSCRIBE_MODEL,
            answerModel = BuildConfig.OPENAI_ANSWER_MODEL,
            onTrace = { trace -> scope.launch { log(trace.logLine()) } },
        )
    }

    val narration by narrator.state.collectAsState()
    val speaking by voice.speaking.collectAsState()
    val ttsAvailable by voice.available.collectAsState()

    // 読み上げが終わったら待機に戻す。AI 音声も端末の読み上げも、終わりは voice が拾っている
    LaunchedEffect(speaking) { if (!speaking) narrator.finishedSpeaking() }

    /**
     * 解説をグラスの専用ページへ出す（#40）。
     *
     * **星座名は端末が既に知っている**ので、AI を待つ 1〜3 秒のあいだも見出しは出ている。
     * 本文は SSE で届くたびに描き足す。行の位置は動かさず文字が伸びるだけなので、
     * 読んでいる最中に行が組み変わらない（[GlassTextPage]）。
     */
    LaunchedEffect(glassPage) {
        if (glassPage != GlassPage.EXPLANATION) return@LaunchedEffect
        // 文字を置く前に星図を消す。逆にすると、消えるまでのあいだ文字が星図に重なる
        sendGate.withLock {
            withContext(NonCancellable) {
                runCatching { commandManager.removeCanvasImage(STAR_MAP_IMAGE_ID) }
            }
        }
        narrator.state.collectLatest { state ->
            // 話が切り替わってすぐ送らない。畳まれた古い本文を 1 枚出してしまう
            delay(EXPLANATION_SEND_DEBOUNCE_MS)
            val header = listOf(state.constellation, explanationHeading)
                .filter { it.isNotBlank() }
                .joinToString("　")
            // **字幕のようにめくる。** 1 枚に全部置くと最初の行が押し出されて消える
            val pages = GlassTextPage.pages(header, state.text)
            explanationPaging = pages.size > 1
            for ((index, page) in pages.withIndex()) {
                sendExplanationPage(page)
                if (index == pages.lastIndex) break
                // 読み上げの速さに合わせる。喋らないときも読める速さで送る
                val shown = page.elements.drop(1).sumOf { it.text.length }
                delay(max(EXPLANATION_PAGE_MIN_MS, shown * EXPLANATION_PAGE_PER_CHAR_MS))
            }
            explanationPaging = false
        }
    }

    // 星図へ戻るときは文字を先に消す。画像が届くまでの 0.4 秒、解説が星図に重なって見える
    LaunchedEffect(glassPage) {
        if (glassPage != GlassPage.STAR_MAP || shownElements.isEmpty()) return@LaunchedEffect
        sendGate.withLock {
            withContext(NonCancellable) {
                for (batch in emptyList<CommandManager.CanvasElement>().batched(shownElements)) {
                    commandManager.sendCanvasElements(batch)
                }
                shownElements = emptyList()
            }
        }
    }

    LaunchedEffect(speaking) { if (speaking) explanationSpoke = true }

    /**
     * 何もしなくても星図へ戻す（#40）。
     *
     * **起点はタップではなく読み上げの終わり。** タップから数えると、合成が遅れたときに
     * 読み始める前に消える。音が鳴らなかったときは、読む時間として長めに取る
     * （騒がしい場所やイヤホンが無いときは**文字が主役**）。
     *
     * `narration.phase` は本文では見ないが、**話が切り替わったら数え直す**ために鍵に入れている。
     */
    LaunchedEffect(glassPage, speaking, narration.phase, explanationPaging) {
        if (glassPage != GlassPage.EXPLANATION) return@LaunchedEffect
        // 読み上げが終わっても、字幕がまだ残っているうちは戻さない
        if (speaking || explanationPaging) return@LaunchedEffect
        delay(if (explanationSpoke) EXPLANATION_LINGER_MS else EXPLANATION_READ_MS)
        leaveGlassExplanation("解説が終わったので星図へ戻る")
    }

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
    // **解説の主役と同じ選び方にする**（種別で絞らないと、月が視野にあるだけで
    // 「月ですね」を作って、タップしたときのキャッシュが当たらない）
    LaunchedEffect(lastMap, bodiesShown) {
        val name = lastMap?.constellationNames()?.firstOrNull() ?: return@LaunchedEffect
        // **名乗りだけでなく解説の全文を作っておく。** ここを名乗りだけにしていたとき、
        // 圏外では「いて座ですね」が AI の声、続く解説が端末の読み上げになり、
        // **1 回の解説で声が入れ替わって聞こえた**（2026-08-22 実機）
        // タップしたときと同じ月・惑星から作る。1 文字でも違うとキャッシュが当たらない
        val bodies = bodiesShown.map {
            ObservedStarFact(it.nameJa, it.magnitude, it.azDeg, it.altDeg, 0.0)
        }
        val warmInput = NarrationInput(
            calibrated = true,
            altDeg = 0.0,
            azDeg = 0.0,
            constellations = listOf(name),
            latDeg = site.latDeg,
            lonDeg = site.lonDeg,
            localTime = "",
            visibleBodies = bodies,
        )
        val script = Narrator.script(name, lore.of(name), warmInput)
        for (part in Narrator.speechParts(name, script)) voice.warm(part)
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

        // **根拠はグラスに出している絵から作る。**
        // 出しているのは「首が止まってから焼いた 1 枚」で、その名前のラベルも同じ絵の上にある。
        // ここでいまの視線から星座を引き直すと、**グラスには〇〇座と出ているのに
        // 別の星座を喋る**ことが起きる（絵と根拠が別計算だった）。
        // 絵がまだ 1 枚も無いときだけ、タップ直前の視線で代用する。
        // **絵と視線は必ず組で使う**（片方だけ残っていると、また別計算に戻ってしまう）
        val shown = lastMap?.takeIf { lastMapLook != null }
        val basis = lastMapLook ?: latched

        // **解説はグラスの専用ページに出す**（#40）。星図を消して枠 8 つを全部文字に使う。
        // 見出しの方角は「絵を焼いた視線」から取る。解説の途中で首を動かしても書き換えない
        narrator.reset()
        explanationHeading = "%s %d°".format(cardinalDirection16(basis.azDeg), basis.altDeg.roundToInt())
        explanationSpoke = false
        explanationDropped = 0
        explanationPaging = false
        glassPage = GlassPage.EXPLANATION

        narrationJob = scope.launch {
            val observedAt = System.currentTimeMillis()
            // ラベルは視野中心に近い順に並んでいる。**先頭が主役。**
            // 主役以外は 2 つまで（並べるほどモデルが主役を選び直す余地が増える）
            val shownNames = shown?.constellationNames()?.take(NARRATION_CONSTELLATIONS).orEmpty()
            val (names, visibleStars) = withContext(Dispatchers.Default) {
                // ラベルが 1 つも出ていない方向（限界等級以内に星が無い）だけ境界表に頼る
                shownNames.ifEmpty { r.constellationsNear(site, observedAt, basis) } to
                    r.visibleNamedStars(site, observedAt, basis, fov.toDouble())
            }
            // グラスに描いたのと同じ判定で月・惑星を渡す。**絵と根拠を別に作ると食い違う**
            val visibleBodies = withContext(Dispatchers.Default) {
                bodiesInView(site, observedAt, basis, fov.toDouble())
            }
            log(
                "解説の根拠 主役=%s 方位%d° 高度%d° 名前%d個 絵=%s".format(
                    names.firstOrNull() ?: "なし",
                    basis.azDeg.roundToInt(),
                    basis.altDeg.roundToInt(),
                    names.size,
                    if (shown == null) "なし" else "あり",
                ),
            )
            narrator.narrate(
                NarrationInput(
                    calibrated = calibratedAt != null,
                    altDeg = basis.altDeg,
                    azDeg = basis.azDeg,
                    constellations = names,
                    latDeg = site.latDeg,
                    lonDeg = site.lonDeg,
                    localTime = timestamp.format(Date()),
                    visibleStars = visibleStars,
                    visibleBodies = visibleBodies,
                ),
            )
        }
    }

    /**
     * 視野の衛星を読み上げる。**スマホのボタンからだけ呼ぶ。**
     *
     * グラスのタップは星座の解説に使う（#36 で衛星は星座のおまけになった）。
     */
    fun narrateSatellitesNow() {
        val scene = satellites
        if (scene == null || !scene.loaded) {
            log("軌道要素が読めていない", failed = scene != null)
            return
        }
        val latched = latchedLook()
        // 衛星の案内も文字で読めるようにする。中身が違うだけで、出す場所は星座と同じ
        narrator.reset()
        explanationHeading = "%s %d°".format(cardinalDirection16(latched.azDeg), latched.altDeg.roundToInt())
        explanationSpoke = false
        explanationDropped = 0
        explanationPaging = false
        glassPage = GlassPage.EXPLANATION

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
    }

    /**
     * 声で聞く（#38）。**ホールドで始めて、黙ったら終わり。**
     *
     * ジェスチャーは 1 回のイベントなので「離したら終わり」にはできない。
     * 聞き取りと回答は数秒かかるため、**その間じゅう解説画面に途中経過を出す**。
     * ここだけは通信が要る（同梱の解説と違い、自由な質問はその場で作るしかない）。
     */
    fun askByVoice() {
        val r = renderer ?: return
        if (asking) return
        val shown = lastMap?.takeIf { lastMapLook != null }
        val basis = lastMapLook ?: latchedLook()
        val subject = shown?.constellationNames()?.firstOrNull().orEmpty()

        narrationJob?.cancel()
        narrator.stop()
        narrator.reset()
        explanationHeading = "%s %d°".format(cardinalDirection16(basis.azDeg), basis.altDeg.roundToInt())
        explanationSpoke = false
        explanationDropped = 0
        explanationPaging = false
        glassPage = GlassPage.EXPLANATION

        asking = true
        narrationJob = scope.launch {
            try {
                if (!ask.configured) {
                    narrator.cannotAnswer(subject, "AI の設定がないので、質問には答えられません。")
                    return@launch
                }
                narrator.progress(subject, "聞いています。質問をどうぞ。")
                val recording = mic.record { level -> micLevel = level }
                micLevel = 0f
                log("録音 ${recording.pcm.size}バイト・声${recording.speechMs}ms")
                if (recording.speechMs == 0L) {
                    narrator.cannotAnswer(subject, "聞き取れませんでした。もう一度お願いします。")
                    return@launch
                }
                narrator.progress(subject, "聞き取っています。")
                val wav = GlassMic.toWav(recording.pcm)
                val heard = runCatching { withContext(Dispatchers.IO) { ask.transcribe(wav) } }
                    .getOrElse { e ->
                        log("文字起こしに失敗: ${e.message}", failed = true)
                        narrator.cannotAnswer(subject, "いまは通信ができないので、質問には答えられません。")
                        return@launch
                    }
                // **聞き取った文は指示ではなくデータ。** 画面へ出す前にここで整える
                val question = AskGuard.sanitizeQuestion(heard)
                if (question.isBlank()) {
                    narrator.cannotAnswer(subject, "聞き取れませんでした。もう一度お願いします。")
                    return@launch
                }
                log("質問: $question")
                narrator.progress(subject, "「$question」")
                val observedAt = System.currentTimeMillis()
                val names = shown?.constellationNames()?.take(NARRATION_CONSTELLATIONS).orEmpty()
                    .ifEmpty { withContext(Dispatchers.Default) { r.constellationsNear(site, observedAt, basis) } }
                val facts = AskFacts(
                    constellations = names,
                    azDeg = basis.azDeg,
                    altDeg = basis.altDeg,
                    localTime = timestamp.format(Date()),
                    visibleStars = withContext(Dispatchers.Default) {
                        r.visibleNamedStars(site, observedAt, basis, fov.toDouble())
                    },
                    visibleBodies = withContext(Dispatchers.Default) {
                        bodiesInView(site, observedAt, basis, fov.toDouble())
                    },
                )
                val reply = runCatching { withContext(Dispatchers.IO) { ask.answer(question, facts) } }
                    .getOrElse { e ->
                        log("回答の生成に失敗: ${e.message}", failed = true)
                        narrator.cannotAnswer(subject, "うまく答えられませんでした。")
                        return@launch
                    }
                narrator.answer(subject, reply)
            } finally {
                asking = false
                micLevel = 0f
            }
        }
    }

    fun stopNarration() {
        narrationJob?.cancel()
        narrationJob = null
        narrator.stop()
        log("解説を止めた")
    }

    /**
     * 衛星を重ねるかどうか。上のボタンとグラスのダブルタップで同じことをする。
     *
     * **モードの切り替えではない**（#36）。星座はどちらでも出たままで、
     * 衛星の点と名前が増えるか減るかだけが変わる。
     */
    fun toggleSatellites() {
        val scene = satellites
        if (scene == null || !scene.loaded) {
            log("軌道要素が読めていない", failed = scene != null)
            return
        }
        showSatellites = !showSatellites
        // 星座はそのままなので画面は消さない。**次の 1 枚で衛星の点が増減する**ように、
        // 「描いた視線」だけ捨てて追従に描き直させる（消えた印の名前は batched が消す）
        drawnLook = null
        log(if (showSatellites) "人工衛星を重ねる" else "人工衛星を隠す")
    }

    /** SINGLE_TAP はトグル。ツルは触れやすく、かけ直しただけで発火するので、押すたび開始では困る */
    fun toggleNarration() {
        // **解説画面を出している間のタップは「もう終わり」**（#40）。止めて星図へ戻す。
        // ここで新しい解説を始めると、根拠にするのは前に焼いた古い絵になってしまう
        if (glassPage == GlassPage.EXPLANATION) {
            stopNarration()
            leaveGlassExplanation("タップで星図へ戻る")
            return
        }
        if (narrator.busy || speaking) stopNarration() else startNarration()
    }

    // gestureEvents は SharedFlow。購読前のジェスチャーは受け取れないので、画面に入った時点で購読する
    DisposableEffect(commandManager) {
        val job: Job = scope.launch {
            commandManager.gestureEvents.collect { gesture ->
                when (gesture) {
                    GestureType.SINGLE_TAP -> toggleNarration()

                    // 「もっと詳しく」に当てていた枠。衛星を重ねるかの切り替えに使う
                    GestureType.DOUBLE_TAP -> toggleSatellites()

                    // **長押しは声で聞く**（#38）。方位合わせはスマホのボタンに残してある
                    GestureType.HOLD -> askByVoice()
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
                    // **衛星の切り替えはグラスのダブルタップと設定に置いた。**
                    // 空を見ている人はスマホを見ないので、上のバーに常設する意味が無い
                    if (!showDetails) {
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
            Text("星座解説", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            NarrationPanel(
                status = when {
                    // 声で聞いている間は、同伴者にも「いま録っている」ことが見えるようにする
                    asking -> "グラスのマイクで質問を聞いています %d%%".format((micLevel * 100).roundToInt())
                    speaking || narration.phase == NarrationPhase.SPEAKING -> "解説を読み上げています"
                    // 解説文は端末が持っているのでキーが無くても喋る。変わるのは声だけ
                    BuildConfig.OPENAI_API_KEY.isEmpty() ->
                        "グラスのツルを1回タップすると解説します。読み上げは端末の音声です"
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

            if (showDetails) {
                // **メインの並びは星座のまま**（見出し → プレビュー → 解説 → ボタン 2 つ）。
                // 情報カードと衛星の一覧はここへ畳む
                Spacer(Modifier.height(16.dp))
            // **他の星見アプリが必ず出している情報**を 1 枚に畳む。
            // 同伴者がスマホで見るぶんなので、グラスには出さない
            SkyNowCard(
                darkness = skyDarkness,
                moon = moon,
                bodies = bodiesShown,
                density = density,
                satellites = if (showSatellites) sightings.size else null,
            )

            // **衛星は星座のおまけ**（#36）。一覧は同伴者がスマホで見るためのもので、
                // 出ていないときは畳んでおく（星座の邪魔をしない）
                if (showSatellites && satellites != null) {
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
                // **グラスのタップは星座の解説に使う**（#36）。衛星の案内はここから
                OutlinedButton(
                    onClick = { narrateSatellitesNow() },
                    enabled = sightings.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("この空の衛星を案内する") }
                }

                Spacer(Modifier.height(16.dp))
                BrightnessSettingsCard(
                    level = brightnessLevel,
                    configured = brightnessConfigured,
                    onLevelChange = { applyBrightness(it) },
                )
                Spacer(Modifier.height(16.dp))
                Text("空の濃さ", style = MaterialTheme.typography.titleMedium)
                Text(
                    // **見えない星を描かないのがいちばん効く**（見えている星と対応が取れなくなる）
                    "${density.label}：${density.hint}（${"%.1f".format(density.limitMagnitude)} 等まで）",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    for (step in SkyDensity.entries) {
                        val selected = step == density
                        TextButton(
                            onClick = {
                                density = step
                                drawnLook = null
                                log("空の濃さ: ${step.label}（${step.limitMagnitude} 等まで）")
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.textButtonColors(
                                containerColor = if (selected) Color(0xFF2D6A4F) else Color.Transparent,
                            ),
                        ) {
                            Text(
                                step.label,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (selected) Color.White else MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (showSatellites) "人工衛星: 星図に重ねる" else "人工衛星: 出さない",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(checked = showSatellites, onCheckedChange = { toggleSatellites() })
                }

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
                                shownElements = emptyList()
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (showArt) "星座絵: 出す（星より暗く敷く）" else "星座絵: 出さない",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(
                            checked = showArt,
                            onCheckedChange = {
                                showArt = it
                                // 次の 1 枚で入れ替わるように、描いた視線を捨てる
                                drawnLook = null
                                log(if (it) "星座絵を出す" else "星座絵を消す")
                            },
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (showGuides) "目印: 地平線と方位（北東南西）を出す" else "目印: 出さない",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(
                            checked = showGuides,
                            onCheckedChange = {
                                showGuides = it
                                drawnLook = null
                                log(if (it) "目印を出す" else "目印を消す")
                            },
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("方位: ドリフト補正あり", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(4.dp))
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
/**
 * 200 バイト 1 パケットの見積り時間。
 *
 * **実測 8〜9ms**（Pixel 9a・2026-08-20 に 12 回）。10ms にしていたぶんは毎フレーム
 * 数十 ms の待ちすぎになっていた。**多く見積もるほど次の絵が遅れる**ので実測の上端に合わせる。
 */
private const val PACKET_MS_DEFAULT = 9f

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

/**
 * 星図に重ねる衛星。**名前で「なにか」が分かるものだけ**に絞る。
 *
 * 名前つき 24 機のうち、だいち・いぶき・しきさい・しずく・テラ・アクア・ランドサット・NOAA は
 * **初心者には名前が手がかりにならない**（見えているのは同じ点なので、名前が読めないと情報が無い）。
 * 測位衛星（GPS・みちびき・ガリレオ）と、名前を知っている ISS・天宮・ひまわり・ハッブルを残す。
 */
private val NOTABLE_SATELLITES = listOf("ISS", "天宮", "みちびき", "GPS", "ガリレオ", "ひまわり", "ハッブル")

/** 一度に重ねる機体の数。**星座の邪魔をしない**ための上限（#36） */
private const val MAX_SATELLITES_IN_VIEW = 3

/** 送信ログに出す星座ラベルの数。主役（先頭）と、その次までが分かれば突き合わせられる */
private const val LOGGED_LABELS = 3

/**
 * AI へ渡す星座の数。
 *
 * **先頭が主役で、残りは「同じ視野にも入っている」だけ。** 並べるほどモデルが主役を
 * 選び直す余地が増えるので、4 つから減らした（AGENTS.md の「箇条書きを増やすほど薄まる」）。
 */
private const val NARRATION_CONSTELLATIONS = 3

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

/**
 * 動きが止まってからこれだけ待って送る。
 *
 * **短いほど早く出る。** 400ms から詰めた。長くすると「止めたのに出てこない」時間がそのまま伸び、
 * 短くしすぎると首を動かしている途中で送り始めて、転送のあいだ真っ暗になる回数が増える。
 */
private const val STILL_MS = 180L

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
 * **待っている間は次の絵を作らない**ので、ここはそのまま体感の遅さになる。200ms から詰めた。
 */
private const val SETTLE_MS = 80L

/**
 * 首の傾きがこれだけ変わったら描き直す。
 *
 * 傾きは方位も高度も変えないので、[REDRAW_DEG] では引っかからない。
 * 画角 35° の端で 6° 回すと 1.8° ずれるので、**方位のしきい値より小さくする**。
 */
private const val REDRAW_ROLL_DEG = 5.0

/**
 * 首の傾きを寄せる速さ（0..1）。
 *
 * 加速度は 1 サンプルごとに揺れるので、そのまま使うと絵がぱたぱた回る。
 * 10Hz で 0.2 なら、傾けてから 1 秒ほどで追いつく。
 */
private const val ROLL_SMOOTHING = 0.2

/** 読み上げが終わってから星図へ戻すまでの余韻 */
private const val EXPLANATION_LINGER_MS = 5_000L

/**
 * 音が鳴らなかったときに解説を出したままにしておく時間。
 *
 * **騒がしい場所やイヤホンが無いときは文字が主役**（#40 の動機そのもの）なので、
 * 読み終わる前に消えるのがいちばん悪い。140 文字の黙読に 16〜23 秒かかる。
 */
private const val EXPLANATION_READ_MS = 25_000L

/** 話が切り替わってから最初の 1 枚を送るまで。畳まれた古い本文を出さないための間 */
private const val EXPLANATION_SEND_DEBOUNCE_MS = 150L

/** 字幕を 1 枚出しておく最短の時間 */
private const val EXPLANATION_PAGE_MIN_MS = 2_600L

/** 1 文字あたりのめくり時間。読み上げはおよそ 7 文字／秒 */
private const val EXPLANATION_PAGE_PER_CHAR_MS = 150L


