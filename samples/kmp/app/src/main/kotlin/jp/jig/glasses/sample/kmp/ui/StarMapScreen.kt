package jp.jig.glasses.sample.kmp.ui

import android.content.Intent
import android.graphics.Bitmap
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.jigglass.glass.CommandManager
import app.jigglass.glass.GestureType
import app.jigglass.glass.GlassClient
import jp.jig.glasses.sample.kmp.BuildConfig
import jp.jig.glasses.sample.kmp.alignment.CalibrationResult
import jp.jig.glasses.sample.kmp.alignment.HeadMotion
import jp.jig.glasses.sample.kmp.alignment.Located
import jp.jig.glasses.sample.kmp.alignment.Locator
import jp.jig.glasses.sample.kmp.alignment.YawDriftCorrector
import jp.jig.glasses.sample.kmp.catalog.ConstellationLore
import jp.jig.glasses.sample.kmp.glass.CANVAS_IMAGE_BUFFER_BYTES
import jp.jig.glasses.sample.kmp.glass.CANVAS_PACKET_BYTES
import jp.jig.glasses.sample.kmp.glass.CANVAS_TEXT_SLOTS
import jp.jig.glasses.sample.kmp.glass.GlassBrightness
import jp.jig.glasses.sample.kmp.glass.GlassBrightnessPrefs
import jp.jig.glasses.sample.kmp.glass.GlassPage
import jp.jig.glasses.sample.kmp.glass.GlassTextPage
import jp.jig.glasses.sample.kmp.glass.LabelKind
import jp.jig.glasses.sample.kmp.glass.PANEL_HEIGHT
import jp.jig.glasses.sample.kmp.glass.PANEL_WIDTH
import jp.jig.glasses.sample.kmp.glass.STAR_MAP_HEIGHT
import jp.jig.glasses.sample.kmp.glass.STAR_MAP_IMAGE_ID
import jp.jig.glasses.sample.kmp.glass.STAR_MAP_MAX_HEIGHT
import jp.jig.glasses.sample.kmp.glass.STAR_MAP_MAX_WIDTH
import jp.jig.glasses.sample.kmp.glass.STAR_MAP_WIDTH
import jp.jig.glasses.sample.kmp.glass.SkyBodyMark
import jp.jig.glasses.sample.kmp.glass.StarMap
import jp.jig.glasses.sample.kmp.glass.StarMapRenderer
import jp.jig.glasses.sample.kmp.glass.batched
import jp.jig.glasses.sample.kmp.glass.canvasBufferUsageBytes
import jp.jig.glasses.sample.kmp.glass.compressedSizeBytes
import jp.jig.glasses.sample.kmp.glass.constellationNames
import jp.jig.glasses.sample.kmp.glass.toCanvasElements
import jp.jig.glasses.sample.kmp.glass.updatesFrom
import jp.jig.glasses.sample.kmp.narration.AskGuard
import jp.jig.glasses.sample.kmp.narration.NarrationInput
import jp.jig.glasses.sample.kmp.narration.NarrationPhase
import jp.jig.glasses.sample.kmp.narration.Narrator
import jp.jig.glasses.sample.kmp.narration.SkyTips
import jp.jig.glasses.sample.kmp.openai.AskFacts
import jp.jig.glasses.sample.kmp.openai.OpenAiAsk
import jp.jig.glasses.sample.kmp.openai.OpenAiRequestTrace
import jp.jig.glasses.sample.kmp.openai.OpenAiSpeech
import jp.jig.glasses.sample.kmp.satellite.Observer
import jp.jig.glasses.sample.kmp.satellite.SatelliteScene
import jp.jig.glasses.sample.kmp.sky.Look
import jp.jig.glasses.sample.kmp.sky.ObservationDefaults
import jp.jig.glasses.sample.kmp.sky.ObservedStarFact
import jp.jig.glasses.sample.kmp.sky.Site
import jp.jig.glasses.sample.kmp.sky.SkyDarkness
import jp.jig.glasses.sample.kmp.sky.SkyDensity
import jp.jig.glasses.sample.kmp.sky.SolarSystemBody
import jp.jig.glasses.sample.kmp.sky.azimuthFromYaw
import jp.jig.glasses.sample.kmp.sky.bodiesInView
import jp.jig.glasses.sample.kmp.sky.bodiesUp
import jp.jig.glasses.sample.kmp.sky.bodyAltAz
import jp.jig.glasses.sample.kmp.sky.cardinalDirection16
import jp.jig.glasses.sample.kmp.sky.moonPhase
import jp.jig.glasses.sample.kmp.sky.normalizeDeg
import jp.jig.glasses.sample.kmp.sky.rollFromAccel
import jp.jig.glasses.sample.kmp.sky.sunAltitudeDeg
import jp.jig.glasses.sample.kmp.sound.Bgm
import jp.jig.glasses.sample.kmp.sound.SoundPrefs
import jp.jig.glasses.sample.kmp.support.AskHistory
import jp.jig.glasses.sample.kmp.support.BundledData
import jp.jig.glasses.sample.kmp.support.NightRecord
import jp.jig.glasses.sample.kmp.support.SessionLog
import jp.jig.glasses.sample.kmp.ui.component.AskHistoryCard
import jp.jig.glasses.sample.kmp.ui.component.BACKGROUND_LABEL_CLEARANCE
import jp.jig.glasses.sample.kmp.ui.component.BrightnessSettings
import jp.jig.glasses.sample.kmp.ui.component.ConstellationBackground
import jp.jig.glasses.sample.kmp.ui.component.KeepScreenOn
import jp.jig.glasses.sample.kmp.ui.component.LogLine
import jp.jig.glasses.sample.kmp.ui.component.NarrationPanel
import jp.jig.glasses.sample.kmp.ui.component.NightRecordCard
import jp.jig.glasses.sample.kmp.ui.component.ObservationActions
import jp.jig.glasses.sample.kmp.ui.component.ObservationPreview
import jp.jig.glasses.sample.kmp.ui.component.ObservationStatusCard
import jp.jig.glasses.sample.kmp.ui.component.SaberaDarkColorScheme
import jp.jig.glasses.sample.kmp.ui.component.SaberaTypography
import jp.jig.glasses.sample.kmp.ui.component.SeasonalConstellationBackground
import jp.jig.glasses.sample.kmp.ui.component.SessionLogCard
import jp.jig.glasses.sample.kmp.ui.component.SkyViewSettings
import jp.jig.glasses.sample.kmp.ui.component.SoundSettings
import jp.jig.glasses.sample.kmp.ui.component.toPreviewBitmap
import jp.jig.glasses.sample.kmp.voice.CloudVoice
import jp.jig.glasses.sample.kmp.voice.DeviceVoice
import jp.jig.glasses.sample.kmp.voice.GlassMic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
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

    // **アプリの生きている間 1 回だけ読む**（[BundledData]）。方位を合わせ直すたびに
    // 星表を読み直し、星座ごとの最輝星を全星と突き合わせ直していた
    LaunchedEffect(Unit) {
        val loaded = runCatching { BundledData.renderer(context) }
        loaded.onSuccess {
            renderer = it
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

    // 星座絵。**星座線だけでは「なにに見立てたのか」が伝わらない**ので既定で敷く。
    // 星より暗い段で描くので星の位置は読めるが、うるさいと感じたら設定から切れる
    var showArt by remember { mutableStateOf(true) }
    // 地平線・方位の文字・視野中心の印。**星図らしく読ませるための下敷き**
    var showGuides by remember { mutableStateOf(true) }

    /** 設定パネルを開いているか。開いている間は上のバーの見出しも変える */
    var showDetails by remember { mutableStateOf(false) }

    // **衛星は星座のおまけ**（#36）。モードで分けず、同じ星図に重ねる。
    // 衛星だけを見たい人は少数で、狙っているのは天文の初心者なので、
    // **星座＋αで衛星も見える**形にする。切り替えはスマホの設定パネル
    // （ダブルタップは一口メモへ譲った）
    var showSatellites by remember { mutableStateOf(true) }

    /** 次に出す一口メモ（[SkyTips]）。**ダブルタップのたびに 1 つ進める** */
    var tipIndex by remember { mutableStateOf(0) }
    var satellites by remember { mutableStateOf<SatelliteScene?>(null) }
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

    // 10,748 機ぶんあるので IO で読む（[BundledData] が 1 回だけ読む）。
    // 読み終わるまで衛星は重ねられない
    LaunchedEffect(Unit) {
        val loaded = BundledData.satellites(context)
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
    // 解説の根拠（月・惑星）にそのまま渡すぶん。**絵と根拠を別に計算しない**
    var bodiesShown by remember { mutableStateOf<List<SkyBodyMark>>(emptyList()) }
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

    // 空の暗さ。**BGM の曲がこれで決まる**（時計だと同じ 19 時が夏と冬で違う空になる）。
    // 星や衛星の一覧はスマホに出さないので、ここで測るのは太陽高度だけ
    LaunchedEffect(latText, lonText) {
        while (true) {
            val altitude = withContext(Dispatchers.Default) {
                sunAltitudeDeg(site, System.currentTimeMillis())
            }
            skyDarkness = SkyDarkness.of(altitude)
            delay(SKY_DARKNESS_REFRESH_MS)
        }
    }

    // 送信は呼び出しから見ると積むだけで終わる。転送しきる前に次を入れると順番待ちが伸びるので、
    // 見積り時間ぶんは次を入れずに捨てる
    val sendGate = remember { Mutex() }

    /**
     * 入ってきた時点でグラスを白紙に戻す。
     *
     * **記憶と実物が食い違うと、消し残りは以降どのフレームでも取れない。**
     * [shownElements] は「グラスに置いた枠」の記憶で、消す必要のある枠を割り出す唯一の手がかり
     * （[batched]）。Activity が作り直されると記憶だけが空に戻り、実物のラベルは残るので、
     * 前の名前が画面に居座り続ける。縦固定（AndroidManifest）で回転は止めたが、
     * 端末の文字サイズやダークモードの変更でも同じことが起きるので、入口で必ず揃える。
     */
    LaunchedEffect(commandManager) {
        sendGate.withLock { runCatching { commandManager.clearCanvas() } }
        shownElements = emptyList()
    }

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

    /**
     * 1 枚焼いて送る。**送れたら焼いた視線を返す**（送らずに帰ったときに「描いた視線」を
     * 進めると、次の描き直しが止まる）。
     *
     * @param aim 焼く向き。**渡さなければいまの視線。** 首が止まる先へ先出しするときだけ渡す（[HeadMotion]）
     */
    suspend fun drawAndSend(aim: Look? = null): Look? {
        val r = renderer ?: return null
        if (!sendGate.tryLock()) return null
        sending = true
        try {
            val started = System.currentTimeMillis()
            val now = System.currentTimeMillis()
            // **1 枚のあいだ向きを変えない。** ここで look() を呼び直すと、
            // 星・衛星・月惑星・ラベルがそれぞれ違う瞬間の視線で計算される
            val target = aim ?: look()
            val scene = satellites
            // **名前つき、しかも名前で分かるものだけを重ねる。** スターリンクの群れも、
            // 地球観測衛星（だいち・いぶき・しきさい…）も、初心者には名前が手がかりにならない。
            // 測位（GPS・みちびき・ガリレオ）と ISS・ひまわり・ハッブルに絞る
            val tracks = if (showSatellites && scene != null) {
                withContext(Dispatchers.Default) {
                    val observer = Observer(site.latDeg, site.lonDeg)
                    scene.tracksInView(observer, now, target, fov.toDouble(), maxStarlink = 0)
                        .filter { track -> NOTABLE_SATELLITES.any { track.name.startsWith(it) } }
                        .take(MAX_SATELLITES_IN_VIEW)
                }
            } else {
                emptyList()
            }
            val bodies = withContext(Dispatchers.Default) {
                bodiesInView(site, now, target, fov.toDouble()).map {
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
                    look = target,
                    fovDeg = fov.toDouble(),
                    limitMagnitude = density.limitMagnitude,
                    width = w,
                    height = h,
                    // 星座線と星座名は切れるようにしていない。**線が無いと星座に見えず、
                    // 名前が無いと解説の主役も決まらない**（主役はラベルの先頭・#37）
                    drawLines = true,
                    maxLabels = CANVAS_TEXT_SLOTS,
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
            lastMapLook = target

            // グラスの画像バッファを超えると SDK が例外を投げる。同じ式で先に見て、
            // 落ちる代わりに「1 段下げてくれ」と出す（星の多い空ほど圧縮後が膨らむ）
            val compressed = map.compressedSizeBytes()
            val used = map.canvasBufferUsageBytes()
            transferMs = ((compressed + CANVAS_PACKET_BYTES - 1) / CANVAS_PACKET_BYTES) * packetMs.toLong()
            if (used > CANVAS_IMAGE_BUFFER_BYTES) {
                preview = withContext(Dispatchers.Default) { map.toPreviewBitmap() }
                log("バッファ超過 $used > $CANVAS_IMAGE_BUFFER_BYTES バイト", failed = true)
                Log.w(TAG, "バッファ超過 ${map.width}x${map.height} used=$used")
                return null
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
            drawnLook = target
            drawnFov = fov.toDouble()
            drawnRoll = glassRoll

            // プレビューは転送を待つ間に作る。送信の手前で作ると、そのぶんグラスに出るのが遅れる
            preview = withContext(Dispatchers.Default) { map.toPreviewBitmap() }

            val l = target
            log(
                "送信 方位${l.azDeg.roundToInt()}° 高度${l.altDeg.roundToInt()}° " +
                    (if (aim == null) "" else "先出し ") +
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
            return target
        } catch (e: CancellationException) {
            // 画面を離れたときの中断。送信の失敗ではないので、そのまま上へ流す
            throw e
        } catch (e: Throwable) {
            log("失敗: ${e.message}", failed = true)
            Log.e(TAG, "drawAndSend で失敗", e)
            return null
        } finally {
            sending = false
            sendGate.unlock()
        }
    }

    /**
     * 解説画面をグラスへ出す（#40）。
     *
     * 送るのは**変わった行だけ**（[updatesFrom]）なので、1 行流すごとに呼んでよい。
     * 1 枚は 3 行で 190 バイト・1 電文に収まる（星図画像 1 枚の 1/30 で済む）。
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
     * 「首が止まったら描き直す」追従。**止まりきる手前で、止まる先へ送る。**
     *
     * 1 枚の転送中はグラスが前の絵を捨てて何も出さない。動くたびに送ると
     * 表示より転送のほうが長く、点いては消えるだけになる（実機で確認）。
     * かといって完全に止まるまで待つと、**止めてから絵が出るまで 0.5 秒以上**かかり、
     * その間ずっとグラスは真っ暗になる。
     *
     * そこで**減速に入った時点で 1 枚だけ先出しする**（[HeadMotion]）。転送が終わるころの
     * 視線を割り引いて外挿し、そこ向きの絵を焼く。送る枚数もバイト数も増えない。
     * 外挿が足りなければ、止まったあとの描き直しが埋める。
     */
    var settled by remember { mutableStateOf(true) }
    val headMotion = remember { HeadMotion() }
    LaunchedEffect(renderer, showSatellites, showFigures, showArt, showGuides, density) {
        if (renderer == null) return@LaunchedEffect
        var drawn: Look? = null
        var previous = look()
        var movedAt = 0L
        /** 先出しした時刻。**首を振り続けている間に何枚も先出ししない**ための間隔 */
        var predictedAt = 0L
        while (true) {
            val now = look()
            headMotion.add(System.currentTimeMillis(), now)
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
                drawAndSend()?.let {
                    drawn = it
                    predictedAt = 0L
                }
            } else if (
                drift > REDRAW_DEG && headMotion.slowing &&
                System.currentTimeMillis() - predictedAt > PREDICT_COOLDOWN_MS
            ) {
                // **止まる先へ 1 枚。** 行き過ぎるより届かないほうが安全なので割り引く
                val aim = headMotion.predict(now, transferMs + SETTLE_MS, PREDICT_DAMPING)
                predictedAt = System.currentTimeMillis()
                drawAndSend(aim)?.let { drawn = it }
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
    val soundPrefs = remember { SoundPrefs(context) }
    val speaker = remember { DeviceVoice(context) }
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
    // 端末の読み上げを選んだ人に毎回選び直させない。**選ぶ理由は場所で変わらない**
    var aiVoice by remember { mutableStateOf(soundPrefs.aiVoice) }
    LaunchedEffect(aiVoice) { voice.enabled = aiVoice }
    DisposableEffect(voice) { onDispose { voice.shutdown(); speaker.shutdown() } }

    // **解説文は端末が持つ。** 星を見に行く場所は電波が届かないことが多く、
    // その場で AI に作らせていた頃は、圏外だと一言も出せなかった。
    // 読むのは [BundledData] に任せる（画面を作り直すたびに 88 星座ぶん読み直していた）
    val lore = remember { mutableStateOf(ConstellationLore.empty) }
    LaunchedEffect(Unit) {
        lore.value = BundledData.lore(context)
        log("解説文を ${lore.value.size} 星座ぶん読んだ")
    }

    // **解説文が届いても作り直さない。** ここを lore で作り直すと、
    // 読み込みが終わった瞬間に解説の状態（喋っている中身）が消える
    val narrator = remember(voice) {
        Narrator(
            speaker = voice,
            lore = { name -> lore.value.of(name) },
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

    // 今夜の記録は画面より長生きする（[NightRecord]）。方位を合わせ直しても消えない
    val nightSeen by NightRecord.seen.collectAsState()

    // 声で聞いたやり取りも同じ扱い（[AskHistory]）。字幕は流れて消え、声は一度きり
    val askHistory by AskHistory.exchanges.collectAsState()

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
            // **字幕のように流す。** 1 枚に全部置くと最初の行が押し出されて消える
            val pages = GlassTextPage.pages(header, state.text)
            explanationPaging = pages.size > 1
            for ((index, page) in pages.withIndex()) {
                sendExplanationPage(page)
                if (index == pages.lastIndex) break
                // **1 枚目は音が出るまで置いておく。** 流す速さは読み上げに合わせてあるが、
                // 数え始めが「文を積んだ時点」なので、合成の 1〜2 秒ぶん字幕が先へ行く。
                // 声の質問の途中経過は喋らないので待たない（#38）
                if (index == 0 && !asking) {
                    withTimeoutOrNull(EXPLANATION_SOUND_WAIT_MS) { voice.sounding.first { it } }
                }
                // 新しく出た行を読む時間だけ置く。**流すほど少しずつゆっくりにする**
                delay(explanationDwellMs(page.revealed, index))
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
     *
     * **声で聞いている間は数えない**（#38）。聞き取りと回答は喋らないので [speaking] は false のまま、
     * 途中経過はどれも `SPEAKING` なので鍵も変わらない。つまり「聞いています」から一度も
     * 数え直さずに 25 秒が過ぎる。録音 12 秒＋文字起こし＋回答で超えると、
     * **答えが届く前に星図へ戻り、字幕の組版ごと畳まれて答えが声だけになる。**
     */
    LaunchedEffect(glassPage, speaking, narration.phase, explanationPaging, asking) {
        if (glassPage != GlassPage.EXPLANATION) return@LaunchedEffect
        // 読み上げが終わっても、字幕がまだ残っているうちは戻さない
        if (speaking || explanationPaging || asking) return@LaunchedEffect
        delay(if (explanationSpoke) EXPLANATION_LINGER_MS else EXPLANATION_READ_MS)
        leaveGlassExplanation("解説が終わったので星図へ戻る")
    }

    // BGM は解説していない間も鳴らす。**プラネタリウムの雰囲気は無音では出ない**
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
    LaunchedEffect(lastMap, bodiesShown, lore.value) {
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
        val script = Narrator.script(name, lore.value.of(name), warmInput)
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

    /**
     * 解説と質問はここから走らせる。**取りこぼした例外でアプリを終わらせない。**
     *
     * `SupervisorJob` は兄弟を巻き込まないだけで、**未捕捉例外そのものは止めない**
     * （既定のハンドラまで上がってプロセスが落ちる）。ここから呼ぶのは星表の計算と、
     * マイク・通信という**外の事情で落ちる**ものばかりで、`drawAndSend` は同じ理由で
     * `Throwable` を拾っている。
     *
     * **落ちるより、断って喋るほうが上**（app-flow.md「タップして無反応が一番よくない」）。
     */
    fun launchNarration(what: String, subject: String, block: suspend () -> Unit): Job = scope.launch {
        try {
            block()
        } catch (e: CancellationException) {
            // タップで止めた・画面を離れたときの中断。失敗ではないのでそのまま流す
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "$what で失敗", e)
            log("${what}で失敗: ${e.message}", failed = true)
            narrator.cannotAnswer(subject, "うまく動きませんでした。もう一度お願いします。")
        }
    }

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

        narrationJob = launchNarration("解説", shown?.constellationNames()?.firstOrNull().orEmpty()) {
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
            // **今夜の一覧に残す**（#10 の動機）。字幕は数秒でめくれ、声は一度きりなので、
            // 同伴者がスマホを覗いたときには次の星座に変わっている
            val spoken = narrator.state.value
            if (spoken.phase == NarrationPhase.SPEAKING) {
                NightRecord.add(spoken.constellation, System.currentTimeMillis(), spoken.text)
            }
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
        // **質問の間は星座名も方角も出さない。** 見出しに名前が出ていると、聞いたことと
        // 関係のない星座について答えているように見える（実機で「上に星座名が出る」）。
        // AI へ渡す事実にはこれまでどおり星座が入っているので、答えの中身は変わらない
        val subject = ""

        narrationJob?.cancel()
        narrator.stop()
        narrator.reset()
        explanationHeading = ""
        explanationSpoke = false
        explanationDropped = 0
        explanationPaging = false
        glassPage = GlassPage.EXPLANATION

        asking = true
        // **マイクと通信はいちばん落ちやすい経路。** startMicStreaming が投げただけで
        // アプリが終わっては、質問どころではなくなる（[launchNarration] が受け止める）
        narrationJob = launchNarration("質問", subject) {
            try {
                if (!ask.configured) {
                    narrator.cannotAnswer(subject, "AI の設定がないので、質問には答えられません。")
                    return@launchNarration
                }
                narrator.progress(subject, askPrompt(0f))
                // **拾っている音の大きさをその場で返す。** 聞き取った文字が出るのは録り終わって
                // からなので、喋っている最中に「届いているのか」を返せるのはこれだけ。
                // 声が小さくて枠が伸びないなら、そのまま黙って待たれるより言い直せる
                var meterAt = 0L
                val recording = mic.record { level ->
                    micLevel = level
                    val at = System.currentTimeMillis()
                    // マイクは毎秒 32,000 バイトを同じ BLE に流している。**枠の送り直しで
                    // その帯域を食わない**ように、目で追える速さまで落とす
                    if (at - meterAt >= MIC_METER_MS) {
                        meterAt = at
                        narrator.progress(subject, askPrompt(level))
                    }
                }
                micLevel = 0f
                // **しきい値が屋外で合っているかはこの行でしか分からない。**
                // 暗騒音が高い夜は「上限まで録り切ったのに声 0ms」として出る
                log(
                    "録音 %dバイト・声%dms・暗騒音%.0f→しきい値%.0f".format(
                        recording.pcm.size,
                        recording.speechMs,
                        recording.noiseFloorRms,
                        recording.thresholdRms,
                    ),
                )
                if (recording.speechMs == 0L) {
                    narrator.cannotAnswer(subject, "聞き取れませんでした。もう一度お願いします。")
                    return@launchNarration
                }
                narrator.progress(subject, "聞き取っています。")
                val wav = GlassMic.toWav(recording.pcm)
                val heard = runCatching { withContext(Dispatchers.IO) { ask.transcribe(wav) } }
                    .getOrElse { e ->
                        log("文字起こしに失敗: ${e.message}", failed = true)
                        narrator.cannotAnswer(subject, "いまは通信ができないので、質問には答えられません。")
                        return@launchNarration
                    }
                // **聞き取った文は指示ではなくデータ。** 画面へ出す前にここで整える
                val question = AskGuard.sanitizeQuestion(heard)
                if (question.isBlank()) {
                    narrator.cannotAnswer(subject, "聞き取れませんでした。もう一度お願いします。")
                    return@launchNarration
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
                        val refusal = "うまく答えられませんでした。"
                        // **答えられなかったやり取りも残す**（#38）。履歴に無いと、
                        // 質問が届かなかったのか答えが返らなかったのかが分からない
                        AskHistory.add(observedAt, question, refusal, answered = false)
                        narrator.cannotAnswer(subject, refusal)
                        return@launchNarration
                    }
                // 断り（[AskGuard.OFF_TOPIC]）は鳴らし直しても何も進まないので、答えとしては数えない
                AskHistory.add(observedAt, question, reply, answered = reply != AskGuard.OFF_TOPIC)
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
     * 今夜の記録から読み直す（[NightRecord]）。
     *
     * **文は作り直さない。** 1 回目と同じ文字列なので AI 音声のキャッシュが当たり、
     * 圏外でも 1 回目に鳴ったものはそのまま鳴る。グラスにも字幕で出す（#40 と同じ道）。
     */
    fun replayRecord(entry: NightRecord.Seen) {
        narrationJob?.cancel()
        narrationJob = null
        narrator.reset()
        // 見出しの方角は出さない。**いま向いている方向とは関係ない**（記録は過去の視線）
        explanationHeading = ""
        explanationSpoke = false
        explanationDropped = 0
        explanationPaging = false
        glassPage = GlassPage.EXPLANATION
        narrator.again(entry.nameJa, entry.text)
    }

    /**
     * ダブルタップの**一口メモ**。いまの時刻とこの場所から言えることを 1 つ出す。
     *
     * **通信も生成も要らない**（[SkyTips]）。解説文を同梱してあるのと同じ理由で、
     * 星を見に行く場所ほど電波が届かない。
     *
     * **押すたびに次のメモへ進む。** 1 つしか言わないと、2 回目のダブルタップが
     * 無反応と区別できない（**タップして無反応が一番よくない**・app-flow.md）。
     */
    fun showTip() {
        narrationJob?.cancel()
        narrator.stop()
        narrator.reset()
        // 見出しにはメモの題を出す（[Narrator.tip] の subject）。方角はメモと関係ない
        explanationHeading = ""
        explanationSpoke = false
        explanationDropped = 0
        explanationPaging = false
        glassPage = GlassPage.EXPLANATION

        narrationJob = launchNarration("一口メモ", "") {
            val observedAt = System.currentTimeMillis()
            val tip = withContext(Dispatchers.Default) {
                val clock = Calendar.getInstance()
                SkyTips.of(
                    SkyTips.Sky(
                        site = site,
                        hourOfDay = clock.get(Calendar.HOUR_OF_DAY),
                        month = clock.get(Calendar.MONTH) + 1,
                        sunAltDeg = sunAltitudeDeg(site, observedAt),
                        moon = moonPhase(observedAt),
                        moonAltDeg = bodyAltAz(SolarSystemBody.MOON, site, observedAt)[1],
                        bodiesUp = bodiesUp(site, observedAt),
                    ),
                    tipIndex,
                )
            }
            tipIndex++
            narrator.retell(tip.header, tip.text, what = "一口メモ")
        }
    }

    /**
     * 声のやり取りを読み直す（設定パネルの履歴から・[AskHistory]）。**文は作り直さない。**
     *
     * 見出しに出すのは**質問文**。何に対する答えかが分からないと、聞き直しても意味が取れない。
     */
    fun replayAsk(exchange: AskHistory.Exchange) {
        narrationJob?.cancel()
        narrationJob = null
        narrator.reset()
        explanationHeading = ""
        explanationSpoke = false
        explanationDropped = 0
        explanationPaging = false
        glassPage = GlassPage.EXPLANATION
        narrator.retell(exchange.question, exchange.answer, what = "やり取りを聞き直し")
    }

    /**
     * 衛星を重ねるかどうか。**切り替えはスマホの設定パネルだけ**。
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

                    // **ダブルタップは一口メモ**。衛星の重ねはスマホの設定パネルへ戻した
                    GestureType.DOUBLE_TAP -> showTip()

                    // **長押しは声で聞く**（#38）。方位合わせはスマホのボタンに残してある
                    GestureType.HOLD -> askByVoice()
                }
            }
        }
        onDispose { job.cancel() }
    }

    // 観測中は消灯させない。音の出し先はスマホで、同伴者はここで空の様子を見る
    KeepScreenOn()

    // **戻るキーで設定パネルを閉じる。** 上の「戻る」を押すには顔の前のスマホを見る必要があり、
    // 空を見ている人には見えない。ここを取っておかないと、観測画面ごと畳まれる
    BackHandler(enabled = showDetails) { showDetails = false }

    MaterialTheme(
        colorScheme = SaberaDarkColorScheme,
        typography = SaberaTypography,
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // **横縦の判定はここで 1 回だけ。** 上部バーを出すかどうかも横縦で変わるので、
            // Scaffold の中身側で測っていては間に合わない
            val landscape = maxWidth > maxHeight
            SeasonalConstellationBackground(
                constellation = constellation,
                modifier = Modifier.fillMaxSize(),
            )
            Scaffold(
                containerColor = Color.Transparent,
                topBar = {
                    // **横画面ではバーを出さない。** 全幅 64dp のうち右半分は空なのに、
                    // その下の解説と設定はそのぶん低くなる（横の縦幅は 350dp ほどしかない）。
                    // 中身は左ペインの見出しへ畳む。**高さ 0 の器も置かないこと** —
                    // Scaffold は「バーはある・高さ 0」と数えて中身をステータスバーの裏へ潜らせる
                    if (!landscape) {
                        TopAppBar(
                            title = { Text(if (showDetails) "星図の設定" else "現在の星空") },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = Color(0xA608111B),
                                titleContentColor = Color.White,
                            ),
                            navigationIcon = {
                                Box(Modifier.width(88.dp), contentAlignment = Alignment.CenterStart) {
                                    if (showDetails) {
                                        TextButton(
                                            onClick = { showDetails = false },
                                            modifier = Modifier.padding(start = 8.dp),
                                        ) {
                                            Text("戻る", color = Color.White)
                                        }
                                    }
                                }
                            },
                            actions = {
                                // **止める手段はどの画面でも消さない。** 解説と設定は同じ画面の
                                // 表と裏なので、設定を開いている間だけ「解説を止める」が消えていた
                                // （戻るキーを知らないと止められない）
                                if (narrator.busy || speaking || asking) {
                                    TextButton(onClick = { toggleNarration() }) {
                                        Text("解説を止める", color = Color.White)
                                    }
                                }
                                // **衛星の切り替えは設定パネルに置いた。** 空を見ている人は
                                // スマホを見ないので、上のバーに常設する意味が無い
                                if (!showDetails) {
                                    TextButton(onClick = { showDetails = true }) {
                                        Text("設定", color = Color.White)
                                    }
                                }
                            },
                        )
                    }
                },
            ) { padding ->
                Box(
                    Modifier.fillMaxSize().padding(padding)
                        .padding(horizontal = 16.dp),
                ) {
                    val narrationStatus = when {
                        asking -> "グラスのマイクで質問を聞いています ${micMeter(micLevel)}"
                        speaking || narration.phase == NarrationPhase.SPEAKING -> "解説を読み上げています"
                        BuildConfig.OPENAI_API_KEY.isEmpty() ->
                            "グラスのツルを1回タップすると解説します。読み上げは端末の音声です"
                        else -> "グラスのツルを1回タップすると解説します"
                    }
                    Row(
                        Modifier.fillMaxSize().padding(
                            vertical = if (landscape) 0.dp else 16.dp,
                        ),
                    ) {
                        if (landscape) {
                            Column(Modifier.weight(1f).fillMaxHeight().padding(end = 8.dp)) {
                                // バーの代わり。題と「戻る」は左、操作は右ペインの頭（画面の右上）
                                LandscapeHeader(
                                    title = if (showDetails) "星図の設定" else "現在の星空",
                                    onBack = if (showDetails) ({ showDetails = false }) else null,
                                )
                                Column(
                                    Modifier.fillMaxWidth().weight(1f),
                                    verticalArrangement = Arrangement.Center,
                                ) {
                                    if (renderer == null) {
                                        Text("星表を読み込み中…")
                                        Spacer(Modifier.height(12.dp))
                                    }
                                    Text("グラスに表示している星空", style = MaterialTheme.typography.titleLarge)
                                    Spacer(Modifier.height(4.dp))
                                    ObservationPreview(preview, sending, transferMs, Modifier.fillMaxWidth())
                                    Text(
                                        "グラスの向きを止めると、その方角の星図に更新します",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    Spacer(Modifier.height(10.dp))
                                    Text(
                                        narrationStatus,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                        Box(
                            modifier = if (landscape) {
                                // **下端は背景の星座名に譲る。** 設定側は 85% の板を敷くので、
                                // 下まで伸ばすと右下の「◯の星座・◯◯座」が板の裏に隠れる。
                                // padding を background より先に置き、板ごと縮める
                                Modifier.weight(1f).fillMaxHeight()
                                    .padding(start = 8.dp, bottom = BACKGROUND_LABEL_CLEARANCE)
                                    .then(
                                        if (showDetails) Modifier.background(Color(0xD908111B))
                                        else Modifier,
                                    )
                            } else {
                                Modifier.fillMaxWidth().fillMaxHeight()
                            },
                            contentAlignment = Alignment.TopCenter,
                        ) {
                            Column(Modifier.widthIn(max = 400.dp).fillMaxWidth().fillMaxHeight()) {
                                // **操作は画面の右上へ。** 左ペインの題と同じ行に並ぶので、
                                // 見た目は 1 本のバーのまま高さは 1 行ぶんで済む
                                if (landscape) {
                                    LandscapeActions(
                                        onStop = if (narrator.busy || speaking || asking) {
                                            ({ toggleNarration() })
                                        } else {
                                            null
                                        },
                                        onSettings = if (!showDetails) ({ showDetails = true }) else null,
                                    )
                                }
                                // 流れるのは中身だけ。上の操作はスクロールの外に残す
                                Column(
                                    Modifier.fillMaxWidth().weight(1f)
                                        .then(
                                            if (showDetails) Modifier.verticalScroll(rememberScrollState())
                                            else Modifier,
                                        ),
                                ) {
                                    // **畳んだ側が本体。** 見出し → プレビュー → 解説 → ボタン 2 つだけを出す。
                                    // 数字と設定は「設定」を開いた側へ全部やる（空を見ている人はスマホを見ない）
                                    if (!showDetails) {
                                        if (!landscape) {
                                            if (renderer == null) {
                                                Text("星表を読み込み中…")
                                                Spacer(Modifier.height(12.dp))
                                            }

                                            Text("グラスに表示している星空", style = MaterialTheme.typography.titleLarge)
                                            Spacer(Modifier.height(4.dp))
                                            ObservationPreview(preview, sending, transferMs, Modifier.fillMaxWidth())
                                            Text(
                                                "グラスの向きを止めると、その方角の星図に更新します",
                                                style = MaterialTheme.typography.bodySmall,
                                            )
                                            Spacer(Modifier.height(16.dp))
                                        }
                                        Text("星座解説", style = MaterialTheme.typography.titleMedium)
                                        Spacer(Modifier.height(4.dp))
                                        NarrationPanel(
                                            status = if (landscape) "" else narrationStatus,
                                            subject = narration.constellation,
                                            text = narration.text,
                                            failed = narration.phase == NarrationPhase.FAILED,
                                            // **横は残りの高さを本文に吸わせる。** そうしないと
                                            // 解説の下が空いたままボタン 2 つが宙に浮く
                                            modifier = if (landscape) Modifier.weight(1f) else Modifier,
                                            maxTextHeight = if (landscape) null else 160.dp,
                                        )
                                        ObservationActions(
                                            primaryLabel = if (narrator.busy || speaking) "解説を止める" else "この星空を解説する",
                                            onPrimary = { toggleNarration() },
                                            onRecalibrate = onRecalibrate,
                                        )
                                    } else {
                                        // **設定を触っている間もグラスの中身を見せる。** 濃さや星座絵を変える
                                        // 判断材料はこの絵で、切り替えるたびに閉じて確かめるのは往復になる
                                        if (!landscape) {
                                            ObservationPreview(
                                                preview,
                                                sending,
                                                transferMs,
                                                modifier = Modifier.fillMaxWidth(),
                                            )
                                        }

                                        // 今夜どの星座を解説したか。**読み終わった解説文はここにしか残らない**。
                                        // 1 つも無いうちは出さない（使う機能だけを置く）
                                        if (nightSeen.isNotEmpty()) {
                                            NightRecordCard(
                                                entries = nightSeen,
                                                onAgain = { replayRecord(it) },
                                                onClear = {
                                                    NightRecord.clear()
                                                    log("今夜の記録を消した")
                                                },
                                            )
                                        }

                                        // 声で聞いたやり取り。**答えはここにしか残らない**（#38）
                                        if (askHistory.isNotEmpty()) {
                                            AskHistoryCard(
                                                exchanges = askHistory,
                                                onAgain = { replayAsk(it) },
                                                onClear = {
                                                    AskHistory.clear()
                                                    log("声のやり取りを消した")
                                                },
                                            )
                                        }

                                        SkyViewSettings(
                                            density = density,
                                            onDensityChange = { step ->
                                                density = step
                                                // 次の 1 枚で入れ替わるように、描いた視線を捨てる
                                                drawnLook = null
                                                log("空の濃さ: ${step.label}（${step.limitMagnitude} 等まで）")
                                            },
                                            showSatellites = showSatellites,
                                            onSatellitesChange = { toggleSatellites() },
                                            showArt = showArt,
                                            onArtChange = {
                                                showArt = it
                                                drawnLook = null
                                                log(if (it) "星座絵を出す" else "星座絵を消す")
                                            },
                                            showGuides = showGuides,
                                            onGuidesChange = {
                                                showGuides = it
                                                drawnLook = null
                                                log(if (it) "目印を出す" else "目印を消す")
                                            },
                                        )

                                        BrightnessSettings(
                                            level = brightnessLevel,
                                            configured = brightnessConfigured,
                                            onLevelChange = { applyBrightness(it) },
                                        )

                                        SoundSettings(
                                            aiVoice = aiVoice,
                                            onAiVoiceChange = {
                                                aiVoice = it
                                                soundPrefs.aiVoice = it
                                                voice.stop()
                                                log(if (it) "声: AI 音声にした" else "声: 端末の読み上げに戻した")
                                            },
                                            voiceVolume = voiceVolume,
                                            onVoiceVolumeChange = { voiceVolume = it },
                                            onVoiceVolumeCommit = { soundPrefs.voiceVolume = voiceVolume },
                                            bgmOn = bgmOn,
                                            onBgmChange = {
                                                bgmOn = it
                                                soundPrefs.bgmEnabled = it
                                                log(if (it) "BGM を入れた" else "BGM を止めた")
                                            },
                                            bgmVolume = bgmVolume,
                                            onBgmVolumeChange = { bgmVolume = it },
                                            onBgmVolumeCommit = { soundPrefs.bgmVolume = bgmVolume },
                                            bgmTrackLabel = bgmTrack?.label,
                                        )

                                        ObservationStatusCard(
                                            imuStarted = imuStarted,
                                            calibration = initialCalibration,
                                            siteSource = siteSource,
                                            latText = latText,
                                            onLatChange = { latText = it },
                                            lonText = lonText,
                                            onLonChange = { lonText = it },
                                            onLocate = { locateNow++ },
                                            onRecalibrate = onRecalibrate,
                                        )

                                        SessionLogCard(
                                            logBytes = logBytes,
                                            visibleLines = LOG_LINES,
                                            lines = logs,
                                            onExport = {
                                                val intent = sessionLog.shareIntent()
                                                if (intent == null) {
                                                    log("記録がまだ空", failed = true)
                                                } else {
                                                    context.startActivity(Intent.createChooser(intent, "記録を書き出す"))
                                                }
                                            },
                                            onClear = {
                                                sessionLog.clear()
                                                logs.clear()
                                                log("記録を消した。ここから計測しなおす")
                                            },
                                        )
                                    }
                                    if (showDetails) Spacer(Modifier.height(24.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 横画面の見出し。**上部バーの代わり**にタイトルを左ペインの頭へ置く。
 *
 * 横では全幅のバーが右半分を空けたまま 64dp を取り、そのぶん解説と設定が低くなる。
 * 操作は [LandscapeActions] が右ペインの頭に置き、**この行と同じ高さで並ぶ**ので、
 * 見た目は 1 本のバーのまま高さは 1 行ぶんで済む。
 */
@Composable
private fun LandscapeHeader(title: String, onBack: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = LANDSCAPE_HEADER_HEIGHT),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            TextButton(onClick = onBack) { Text("戻る", color = Color.White) }
        }
        Text(
            title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 横画面の操作。**画面の右上**（右ペインの頭）に右寄せで置く。
 *
 * **止める手段はどの画面でも消さない**（縦のバーと同じ扱い）ので、設定を開いている間も出す。
 * スクロールの外に置くこと — 中に入れると設定を送ったときに流れて消える。
 */
@Composable
private fun LandscapeActions(onStop: (() -> Unit)?, onSettings: (() -> Unit)?) {
    // **出すものが無い行に高さを取らせない。** 空でも min を効かせると、
    // 設定を開いている間じゅう右の頭に 48dp の空き帯が残る
    if (onStop == null && onSettings == null) return
    Row(
        Modifier.fillMaxWidth().heightIn(min = LANDSCAPE_HEADER_HEIGHT),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onStop != null) {
            TextButton(onClick = onStop) { Text("解説を止める", color = Color.White) }
        }
        if (onSettings != null) {
            TextButton(onClick = onSettings) { Text("設定", color = Color.White) }
        }
    }
}

/** 横画面の見出しと操作の行の高さ。**左右で揃えないと題と設定の高さがずれる** */
private val LANDSCAPE_HEADER_HEIGHT = 48.dp

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
 * 質問を待っている間の画面（#38）。**1 行目は言葉、2 行目は拾っている音。**
 *
 * 「聞いています」だけだと、**声が届いているのか黙って待たれているのか分からない**。
 * 文字起こしは録り終わってから 1 回なので、喋っている最中に返せるのは音の大きさだけ。
 */
private fun askPrompt(level: Float): String = "質問をどうぞ。\n" + micMeter(level)

/**
 * 拾っている音の大きさを枠で描く。
 *
 * **グラスの字形は分からない**ので、JIS の記号（■□）だけで作る。
 * しきい値（[jp.jig.glasses.sample.kmp.voice.GlassMic] が暗騒音から決める）で 1 に届くので、
 * **枠が伸びていれば「声として拾えている」**ことになる。
 */
private fun micMeter(level: Float): String {
    val filled = (level.coerceIn(0f, 1f) * MIC_METER_CELLS).roundToInt()
    return "■".repeat(filled) + "□".repeat(MIC_METER_CELLS - filled)
}

/** 枠の数。1 行（17 文字）に収まる範囲で、伸び縮みが目で分かる長さ */
private const val MIC_METER_CELLS = 8

/**
 * 音の大きさを送り直す間隔。
 *
 * マイクは毎秒 32,000 バイトを同じ BLE に流しているので、**枠の更新でその帯域を食わない**。
 * 0.4 秒あれば声の強弱は目で追える。
 */
private const val MIC_METER_MS = 400L

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

/** 空の暗さを測り直す間隔。薄暮から夜へ移るのは数十分かかる */
private const val SKY_DARKNESS_REFRESH_MS = 15_000L

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
 * 先出しの外挿をどれだけ割り引くか（0..1）。
 *
 * **行き過ぎるより届かないほうが安全。** 足りない分は止まったあとの描き直しが埋めるが、
 * 行き過ぎた絵は「合っていない星図」として出たままになる。
 */
private const val PREDICT_DAMPING = 0.6

/**
 * 先出しを繰り返さない間隔。
 *
 * 首を振り続けている間に何枚も先出しすると、**転送のたびに画面が消えて点滅になる**
 * （動きに追従させない理由そのもの）。転送 1 枚が 0.4 秒なので、その 3 倍を空ける。
 */
private const val PREDICT_COOLDOWN_MS = 1_200L

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

/**
 * 1 枚目を出したあと、音が出るのを待つ上限。
 *
 * AI 音声の合成は 1〜2 秒。**待ちすぎるより先へ進むほうが害が小さい**（字幕だけで読む人が
 * 主役の場面もある）ので、鳴らなければ黙読の速さでめくる。
 */
private const val EXPLANATION_SOUND_WAIT_MS = 3_000L

/**
 * 字幕を 1 枚出しておく最短の時間。
 *
 * 1 行ずつ流すようになったので、**下限も 1 行ぶん**。最後のほうに短い行が来たときに、
 * 目に入る前に流れていくのを止めるためだけの値で、埋まった行（17 文字）では効かない。
 */
private const val EXPLANATION_PAGE_MIN_MS = 1_600L

/** 1 文字あたりの送り時間。読み上げはおよそ 7 文字／秒 */
private const val EXPLANATION_PAGE_PER_CHAR_MS = 150L

/**
 * 1 行流すごとに、次まで置く時間を何割ずつ延ばすか。
 *
 * **読み上げは文の切れ目で息が入る**が、字幕は 1 文字あたり一定で数えているので、
 * **流すほど字幕が声より先へ出ていく**。1 行ごとに少しずつ長く置けば、そのぶんを取り返せる。
 * 読む側から見ても、後ろの行ほど前の行を思い出しながら読むので、同じ速さでは追いつかない。
 * **実機未確認**（読む速さは人と明るさで変わるので、合わなければここだけ直す）。
 */
private const val EXPLANATION_SCROLL_SLOWDOWN = 0.06

/**
 * 遅くする頭打ち。
 *
 * 際限なく遅くすると、**声が終わったあと字幕だけが延々と残る**。
 * いちばん長い解説（16 行）でも、最後の行は 1.4 倍で頭打ちになる。
 */
private const val EXPLANATION_SCROLL_SLOWDOWN_MAX = 1.4

/**
 * 字幕を次の 1 行へ送るまでの時間。
 *
 * [revealed] はその 1 枚で新しく出た文字数（1 枚目だけ 2 行ぶん）、[step] は何枚目か。
 * **Android に触らないので JVM テストで固定できる。**
 */
internal fun explanationDwellMs(revealed: Int, step: Int): Long {
    val read = max(EXPLANATION_PAGE_MIN_MS, revealed * EXPLANATION_PAGE_PER_CHAR_MS)
    val slowdown = min(1.0 + step * EXPLANATION_SCROLL_SLOWDOWN, EXPLANATION_SCROLL_SLOWDOWN_MAX)
    return (read * slowdown).toLong()
}
