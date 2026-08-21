package jp.jig.glasses.sample.kmp.ui

import android.hardware.SensorManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.jigglass.glass.CommandManager
import app.jigglass.glass.GlassClient
import jp.jig.glasses.sample.kmp.starmap.ACCEPTABLE_RESIDUAL_DEG
import jp.jig.glasses.sample.kmp.starmap.AlignmentCorrespondence
import jp.jig.glasses.sample.kmp.starmap.AlignmentGuidance
import jp.jig.glasses.sample.kmp.starmap.AlignmentHold
import jp.jig.glasses.sample.kmp.starmap.AlignmentHoldState
import jp.jig.glasses.sample.kmp.starmap.AlignmentSolution
import jp.jig.glasses.sample.kmp.starmap.AlignmentTarget
import jp.jig.glasses.sample.kmp.starmap.AlignmentTargets
import jp.jig.glasses.sample.kmp.starmap.CANVAS_LABEL_HEIGHT
import jp.jig.glasses.sample.kmp.starmap.CalibrationEstimate
import jp.jig.glasses.sample.kmp.starmap.CalibrationEstimator
import jp.jig.glasses.sample.kmp.starmap.CalibrationMarker
import jp.jig.glasses.sample.kmp.starmap.CalibrationResult
import jp.jig.glasses.sample.kmp.starmap.CalibrationSource
import jp.jig.glasses.sample.kmp.starmap.CelestialAlignment
import jp.jig.glasses.sample.kmp.starmap.Compass
import jp.jig.glasses.sample.kmp.starmap.Locator
import jp.jig.glasses.sample.kmp.starmap.Look
import jp.jig.glasses.sample.kmp.starmap.MagneticQuality
import jp.jig.glasses.sample.kmp.starmap.ObservationDefaults
import jp.jig.glasses.sample.kmp.starmap.PANEL_HEIGHT
import jp.jig.glasses.sample.kmp.starmap.PANEL_WIDTH
import jp.jig.glasses.sample.kmp.starmap.STAR_MAP_HEIGHT
import jp.jig.glasses.sample.kmp.starmap.STAR_MAP_WIDTH
import jp.jig.glasses.sample.kmp.starmap.StarCatalog
import jp.jig.glasses.sample.kmp.starmap.YawDriftCorrector
import jp.jig.glasses.sample.kmp.starmap.alignmentGrade
import jp.jig.glasses.sample.kmp.starmap.bridgeToRawYaw
import jp.jig.glasses.sample.kmp.starmap.cardinalDirection8
import jp.jig.glasses.sample.kmp.starmap.glassGuidanceText
import jp.jig.glasses.sample.kmp.starmap.normalizeDeg
import jp.jig.glasses.sample.kmp.starmap.phoneGuidanceText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
fun CalibrationScreen(
    client: GlassClient,
    constellation: ConstellationBackground,
    onCalibrated: (CalibrationResult) -> Unit,
    onHome: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val commandManager = remember(client) { client.createCommandManager() }
    val imuStarted by commandManager.imuDataStarted.collectAsState()
    val compass = remember { Compass(context) }
    val locator = remember { Locator(context) }
    val estimator = remember { CalibrationEstimator() }

    // 段階 2（天体アライメント）。段階 1 の結果を土台に、十字を明るい星へ重ねて方位を詰める
    val alignment = remember { CelestialAlignment() }
    val hold = remember { AlignmentHold() }
    val yawCorrector = remember { YawDriftCorrector() }
    var stage by remember { mutableStateOf(CalibrationStage.PHONE_SYNC) }
    var coarse by remember { mutableStateOf<CalibrationResult?>(null) }
    // 段階 1 のオフセットは「生のヨー」基準。段階 2 は首を振って何十秒も使うので
    // ドリフト補正後のヨー基準に置き換えて持つ（最後に生のヨー基準へ戻す）
    var coarseHeadingCorrectedDeg by remember { mutableStateOf(0.0) }
    var fusedYaw by remember { mutableStateOf<Double?>(null) }
    var holdState by remember { mutableStateOf<AlignmentHoldState?>(null) }
    var targets by remember { mutableStateOf<AlignmentTargets?>(null) }
    var candidates by remember { mutableStateOf<List<AlignmentTarget>>(emptyList()) }
    var guidance by remember { mutableStateOf<AlignmentGuidance?>(null) }
    var manualHip by remember { mutableStateOf<Int?>(null) }
    var solution by remember { mutableStateOf<AlignmentSolution?>(null) }
    var magnetic by remember { mutableStateOf<MagneticQuality?>(null) }
    var lastMagneticAt by remember { mutableLongStateOf(0L) }
    val correspondences = remember { mutableStateListOf<AlignmentCorrespondence>() }

    var site by remember { mutableStateOf(ObservationDefaults.site) }
    var siteStatus by remember { mutableStateOf("観測地を確認中") }
    var locateNow by remember { mutableIntStateOf(0) }
    var glassYaw by remember { mutableStateOf<Double?>(null) }
    var glassPitch by remember { mutableStateOf<Double?>(null) }
    var lastImuAt by remember { mutableLongStateOf(0L) }
    var phoneHeading by remember { mutableStateOf<Double?>(null) }
    var phonePitch by remember { mutableStateOf<Double?>(null) }
    var compassAccuracy by remember { mutableIntStateOf(SensorManager.SENSOR_STATUS_UNRELIABLE) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var lastEstimatedImuAt by remember { mutableLongStateOf(0L) }
    var estimate by remember { mutableStateOf<CalibrationEstimate?>(null) }

    val askLocation = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        siteStatus = if (granted.values.any { it }) "観測地を取得中" else "位置情報なし・鯖江を仮使用"
        if (granted.values.any { it }) locateNow++
    }

    DisposableEffect(compass) {
        compass.start()
        onDispose { compass.stop() }
    }

    DisposableEffect(commandManager) {
        val imuJob: Job = scope.launch {
            commandManager.imuData.collect { data ->
                val rawYaw = data.yawDegrees.toDouble()
                val pitch = -data.pitchDegrees.toDouble()
                glassYaw = rawYaw
                glassPitch = pitch
                lastImuAt = System.currentTimeMillis()
                // 段階 2 は数十秒かかる。生のヨーは静止中も 0.74°/秒 流れるので、
                // 観測画面と同じ補正をここでも通す
                val corrected = yawCorrector.update(
                    rawYawDeg = rawYaw,
                    gyroXDps = data.gyroXDps.toDouble(),
                    gyroYDps = data.gyroYDps.toDouble(),
                    gyroZDps = data.gyroZDps.toDouble(),
                    timestampMs = data.timestampMs,
                )
                fusedYaw = corrected.yawDeg
                holdState = hold.add(lastImuAt, corrected.yawDeg, pitch)
            }
        }
        onDispose {
            imuJob.cancel()
            commandManager.stopImuData()
            commandManager.removeCanvasImage(CalibrationMarker.IMAGE_ID)
            // 観測画面は入口でキャンバスを消さないので、案内の枠はここで消しておく
            runCatching { commandManager.sendCanvasElements(listOf(guidanceElement(""))) }
        }
    }

    LaunchedEffect(Unit) {
        commandManager.startImuData()
        commandManager.clearCanvas()
        commandManager.sendCanvasImage(
            id = CalibrationMarker.IMAGE_ID,
            x = (PANEL_WIDTH - CalibrationMarker.SIZE) / 2,
            y = (PANEL_HEIGHT - CalibrationMarker.SIZE) / 2,
            width = CalibrationMarker.SIZE,
            height = CalibrationMarker.SIZE,
            grayscale = CalibrationMarker.grayscale(),
        )
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
        val located = locator.lastKnown() ?: withTimeoutOrNull(ObservationDefaults.LOCATION_TIMEOUT_MS) {
            locator.current()
        }
        if (located == null) {
            siteStatus = "測位できないため鯖江を仮使用"
        } else {
            site = located.site
            siteStatus = "観測地を取得済み"
        }
    }

    LaunchedEffect(site) {
        estimator.reset()
        estimate = null
        lastEstimatedImuAt = 0L
        while (true) {
            now = System.currentTimeMillis()
            phoneHeading = compass.trueHeadingDeg(site, now)
            phonePitch = compass.pitchDeg
            compassAccuracy = compass.accuracy
            if (now - lastMagneticAt > MAGNETIC_POLL_MS) {
                magnetic = compass.quality(site, now)
                lastMagneticAt = now
            }
            val heading = phoneHeading
            val pitch = phonePitch
            val yaw = glassYaw
            val glassPitchNow = glassPitch
            if (
                heading != null && pitch != null && yaw != null && glassPitchNow != null &&
                lastImuAt > lastEstimatedImuAt && now - lastImuAt < IMU_FRESH_MS
            ) {
                estimate = estimator.add(lastImuAt, heading, pitch, yaw, glassPitchNow)
                lastEstimatedImuAt = lastImuAt
            }
            delay(SENSOR_POLL_MS)
        }
    }

    val tiltDifference = if (phonePitch != null && glassPitch != null) {
        abs(phonePitch!! - glassPitch!!)
    } else {
        null
    }
    val imuFresh = imuStarted && now - lastImuAt < IMU_FRESH_MS
    val headingReady = phoneHeading != null
    val compassReady = compassAccuracy >= SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM
    val facingReady = tiltDifference != null && tiltDifference <= MAX_TILT_DIFFERENCE_DEG
    val stabilityReady = estimate?.stable == true
    // OS の「磁気精度は高い」はキャリブレーションが済んだかしか言わない。
    // 土地の期待値と比べて明らかに歪んでいるときは合わせさせない
    val magneticReady = magnetic?.distorted != true
    val ready = imuFresh && headingReady && compassReady && facingReady && stabilityReady && magneticReady

    /** 段階 2 で使う視線。ドリフト補正後のヨー基準で持つ */
    fun celestialLook(): Look? {
        val yaw = fusedYaw ?: return null
        val pitch = glassPitch ?: return null
        val heading = solution?.headingOffsetDeg ?: coarseHeadingCorrectedDeg
        val pitchOffset = solution?.pitchOffsetDeg ?: coarse?.pitchOffsetDeg ?: 0.0
        return Look(
            (normalizeDeg(yaw + heading) + 360.0) % 360.0,
            (pitch + pitchOffset).coerceIn(-90.0, 90.0),
        )
    }

    LaunchedEffect(stage) {
        if (stage != CalibrationStage.CELESTIAL || targets != null) return@LaunchedEffect
        // 星表は 49KB。段階 2 に入るまで読まない
        val catalog = withContext(Dispatchers.IO) { StarCatalog.load(context) }
        targets = AlignmentTargets(catalog)
    }

    LaunchedEffect(stage, targets) {
        val finder = targets
        if (stage != CalibrationStage.CELESTIAL || finder == null) return@LaunchedEffect
        while (true) {
            val look = celestialLook()
            if (look != null) {
                val list = finder.candidates(site, System.currentTimeMillis(), look.azDeg)
                candidates = list
                val used = correspondences.map { it.targetName }.toSet()
                val chosen = manualHip?.let { hip -> list.firstOrNull { it.hip == hip } }
                guidance = when {
                    chosen != null -> finder.guidanceFor(chosen, list, look)
                    else -> finder.guide(
                        candidates = list.filter { it.nameJa !in used },
                        look = look,
                        separateFrom = correspondences.firstOrNull()?.let { first ->
                            list.firstOrNull { it.nameJa == first.targetName }
                        },
                    )
                }
                // 十字に重なったまま静止が続いたら、その静止区間の平均を対応点にする。
                // 押した瞬間の値は使わない（ツルに触ると頭が動く）
                val current = guidance
                val steady = holdState
                if (
                    current != null && steady != null && current.inView &&
                    !current.ambiguous && steady.confirmed &&
                    correspondences.none { it.targetName == current.target.nameJa }
                ) {
                    correspondences += AlignmentCorrespondence(
                        atMs = System.currentTimeMillis(),
                        glassYawDeg = steady.yawDeg,
                        glassPitchDeg = steady.pitchDeg,
                        panelX = STAR_MAP_WIDTH / 2.0,
                        panelY = STAR_MAP_HEIGHT / 2.0,
                        targetName = current.target.nameJa,
                        targetAzDeg = current.target.azDeg,
                        targetAltDeg = current.target.altDeg,
                    )
                    // 残差が悪くなっても差し替える。**取り違えを隠さないため**。
                    // 2 点目の食い違いは「別の星に合わせてしまった」という情報そのもの
                    solution = alignment.solve(correspondences.toList()) ?: solution
                    manualHip = null
                    // 同じ静止でもう一度取らないよう窓を空にする
                    hold.reset()
                }
            }
            delay(GUIDANCE_POLL_MS)
        }
    }

    // グラス側の案内。矢印だけで意味が通るようにする（見上げている人は文字を読み込まない）
    val glassText = if (stage == CalibrationStage.CELESTIAL) glassGuidanceText(guidance, holdState) else ""
    LaunchedEffect(glassText) {
        runCatching { commandManager.sendCanvasElements(listOf(guidanceElement(glassText))) }
    }

    fun finishCelestial() {
        val solved = solution ?: return
        val yaw = fusedYaw ?: return
        onCalibrated(
            CalibrationResult(
                // 観測画面のドリフト補正は入った時点の生のヨーから始まるので、
                // 段階 2 で溜めた補正ぶんを足して生のヨー基準へ戻す
                headingOffsetDeg = bridgeToRawYaw(solved.headingOffsetDeg, yaw, glassYaw ?: yaw),
                pitchOffsetDeg = solved.pitchOffsetDeg,
                calibratedAt = System.currentTimeMillis(),
                // 段階 2 の「ばらつき」は静止のばらつきではなく、対応点どうしの食い違い
                headingStdDeg = solved.rmsResidualDeg,
                pitchStdDeg = solved.rmsResidualDeg,
                sampleCount = solved.count,
                source = CalibrationSource.CELESTIAL,
                residualDeg = solved.maxResidualDeg,
                targetNames = correspondences.joinToString("・") { it.targetName },
            ),
        )
    }

    Box(Modifier.fillMaxSize()) {
        SeasonalConstellationBackground(
            constellation = constellation,
            modifier = Modifier.fillMaxSize(),
        )

        if (stage == CalibrationStage.CELESTIAL) {
            CelestialAlignmentStage(
                guidance = guidance,
                holdState = holdState,
                solution = solution,
                captured = correspondences.map { it.targetName },
                candidateCount = candidates.size,
                loaded = targets != null,
                imuFresh = imuFresh,
                onAnotherStar = {
                    val list = candidates
                    if (list.isNotEmpty()) {
                        val currentHip = guidance?.target?.hip
                        val index = list.indexOfFirst { it.hip == currentHip }
                        manualHip = list[(index + 1).mod(list.size)].hip
                    }
                },
                onFinish = { finishCelestial() },
                onReset = {
                    correspondences.clear()
                    solution = null
                    manualHip = null
                    hold.reset()
                },
                onSkip = { coarse?.let(onCalibrated) },
            )
            return@Box
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 32.dp)
                .padding(horizontal = 20.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("方位合わせ", style = MaterialTheme.typography.headlineMedium, color = Color.White)
            Text(
                "グラスの十字と、スマホの中央を重ねます",
                modifier = Modifier.padding(top = 6.dp),
                color = Color.White.copy(alpha = 0.72f),
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(20.dp))
            Card(
                modifier = Modifier.fillMaxWidth().widthIn(max = 380.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xED0C151D)),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    AlignmentTarget(
                        ready = ready,
                        modifier = Modifier.fillMaxWidth().height(230.dp),
                    )
                    Text(
                        text = if (ready) {
                            "この位置で合わせられます"
                        } else {
                            magnetic?.takeIf { it.distorted }?.reason ?: calibrationInstruction(
                                imuFresh = imuFresh,
                                headingReady = headingReady,
                                compassReady = compassReady,
                                facingReady = facingReady,
                                stabilityReady = stabilityReady,
                            )
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = if (ready) SaberaGreen else Color.White,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Card(
                modifier = Modifier.fillMaxWidth().widthIn(max = 380.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xED152028)),
            ) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            modifier = Modifier.weight(0.38f),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                "スマホの方角",
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White.copy(alpha = 0.68f),
                            )
                            Spacer(Modifier.height(6.dp))
                            CompassDial(
                                headingDegrees = phoneHeading,
                                ready = headingReady && compassReady,
                                modifier = Modifier.size(108.dp),
                            )
                        }
                        VerticalDivider(
                            modifier = Modifier.height(150.dp).padding(horizontal = 10.dp),
                            color = Color.White.copy(alpha = 0.18f),
                        )
                        Column(Modifier.weight(0.62f)) {
                            Text(
                                "精度情報",
                                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White.copy(alpha = 0.68f),
                                textAlign = TextAlign.Start,
                            )
                            PrecisionRow(
                                "仰角差",
                                tiltDifference?.let { "%.1f°".format(it) } ?: "取得中",
                                facingReady,
                            )
                            PrecisionRow("6DoF", if (imuFresh) "受信中" else "待機中", imuFresh)
                            PrecisionRow("磁気精度", compassAccuracyLabel(compassAccuracy), compassReady)
                            PrecisionRow(
                                "磁気の歪み",
                                magnetic?.let { "%.2f倍 / 伏角%.0f°差".format(it.strengthRatio, it.inclinationDiffDeg) }
                                    ?: "計測中",
                                magneticReady && magnetic != null,
                            )
                            PrecisionRow(
                                "静止精度",
                                estimate?.let { "±%.1f° / %d件".format(it.headingStdDeg, it.sampleCount) }
                                    ?: "計測中",
                                stabilityReady,
                            )
                            PrecisionRow(
                                "位置情報",
                                compactSiteStatus(siteStatus),
                                !siteStatus.contains("仮使用"),
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.weight(1f))
            Button(
                onClick = {
                    val stable = estimate?.takeIf { it.stable } ?: return@Button
                    onCalibrated(
                        CalibrationResult(
                            headingOffsetDeg = stable.headingOffsetDeg,
                            pitchOffsetDeg = stable.pitchOffsetDeg,
                            calibratedAt = System.currentTimeMillis(),
                            headingStdDeg = stable.headingStdDeg,
                            pitchStdDeg = stable.pitchStdDeg,
                            sampleCount = stable.sampleCount,
                        ),
                    )
                },
                enabled = ready,
                modifier = Modifier.fillMaxWidth().widthIn(max = 340.dp).height(54.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SaberaGreen,
                    contentColor = SaberaOnAccent,
                    disabledContainerColor = SaberaGreen.copy(alpha = 0.30f),
                    disabledContentColor = Color.White.copy(alpha = 0.55f),
                ),
            ) {
                Text(if (ready) "この向きで合わせる" else "精度条件を確認中")
            }
            // 段階 2 は任意。ここで残るのは地磁気そのものの誤差（±5〜15°）で、
            // 星図を空に重ねる（±2〜3°）には届かない
            TextButton(
                onClick = {
                    val stable = estimate?.takeIf { it.stable } ?: return@TextButton
                    val yaw = fusedYaw
                    val raw = glassYaw
                    coarse = CalibrationResult(
                        headingOffsetDeg = stable.headingOffsetDeg,
                        pitchOffsetDeg = stable.pitchOffsetDeg,
                        calibratedAt = System.currentTimeMillis(),
                        headingStdDeg = stable.headingStdDeg,
                        pitchStdDeg = stable.pitchStdDeg,
                        sampleCount = stable.sampleCount,
                    )
                    // 段階 1 のオフセットは生のヨー基準。段階 2 の間はドリフト補正後のヨーを使う
                    coarseHeadingCorrectedDeg = if (yaw != null && raw != null) {
                        normalizeDeg(stable.headingOffsetDeg - normalizeDeg(yaw - raw))
                    } else {
                        stable.headingOffsetDeg
                    }
                    correspondences.clear()
                    solution = null
                    manualHip = null
                    hold.reset()
                    stage = CalibrationStage.CELESTIAL
                },
                enabled = ready,
                colors = ButtonDefaults.textButtonColors(contentColor = SaberaGreen),
            ) {
                Text("さらに星に合わせる（精度モード）")
            }
            TextButton(
                onClick = onHome,
                colors = ButtonDefaults.textButtonColors(contentColor = SaberaGreen),
            ) {
                Text("ホーム")
            }
        }
    }
}

/**
 * 段階 2 の画面。**初心者に星を探させない**ので、名前も方位も言わせない。
 * アプリが「右へ」と言い、見えている明るい点に十字を重ねてもらうだけにする。
 * 何に合わせたかは、合ってから教える。
 */
@Composable
private fun CelestialAlignmentStage(
    guidance: AlignmentGuidance?,
    holdState: AlignmentHoldState?,
    solution: AlignmentSolution?,
    captured: List<String>,
    candidateCount: Int,
    loaded: Boolean,
    imuFresh: Boolean,
    onAnotherStar: () -> Unit,
    onFinish: () -> Unit,
    onReset: () -> Unit,
    onSkip: () -> Unit,
) {
    val holdProgress = holdState?.let {
        (it.heldMs.toFloat() / AlignmentHold.REQUIRED_HOLD_MS).coerceIn(0f, 1f)
    } ?: 0f
    val inView = guidance?.inView == true

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 32.dp)
            .padding(horizontal = 20.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("星に合わせる", style = MaterialTheme.typography.headlineMedium, color = Color.White)
        Text(
            "名前は知らなくて大丈夫です。案内する方へ首を向けてください",
            modifier = Modifier.padding(top = 6.dp),
            color = Color.White.copy(alpha = 0.72f),
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(20.dp))
        Card(
            modifier = Modifier.fillMaxWidth().widthIn(max = 380.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xED0C151D)),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                AlignmentTarget(
                    ready = inView,
                    modifier = Modifier.fillMaxWidth().height(190.dp),
                )
                Text(
                    text = when {
                        !imuFresh -> "グラスの6DoFを待っています"
                        !loaded -> "星表を読み込んでいます"
                        // 候補を使い切ったときに「星がありません」と言うと嘘になる
                        guidance == null && solution != null ->
                            "ほかに離れた明るい星がありません。この精度で進められます"
                        else -> phoneGuidanceText(guidance, holdState)
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = if (inView) SaberaGreen else Color.White,
                    textAlign = TextAlign.Center,
                )
                if (inView && holdState?.steady == true) {
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(
                        progress = { holdProgress },
                        modifier = Modifier.fillMaxWidth(),
                        color = SaberaGreen,
                        trackColor = Color.White.copy(alpha = 0.18f),
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Card(
            modifier = Modifier.fillMaxWidth().widthIn(max = 380.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xED152028)),
        ) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                PrecisionRow(
                    "合わせ先",
                    guidance?.let { "%s（%.1f等）".format(it.target.nameJa, it.target.magnitude) } ?: "選定中",
                    guidance != null && !guidance.ambiguous,
                )
                PrecisionRow(
                    "向き",
                    guidance?.let {
                        if (it.inView) "視野の中" else "%s へ %.0f°".format(
                            if (it.turnDeg >= 0) "右" else "左",
                            abs(it.turnDeg),
                        )
                    } ?: "—",
                    inView,
                )
                PrecisionRow(
                    "取れた点",
                    if (captured.isEmpty()) "まだ 0 点" else "${captured.size} 点・" + captured.joinToString("・"),
                    captured.isNotEmpty(),
                )
                PrecisionRow(
                    "ずれ",
                    solution?.let { "%.1f°".format(it.maxResidualDeg) } ?: "未計測",
                    solution != null && solution.maxResidualDeg <= ACCEPTABLE_RESIDUAL_DEG,
                )
                PrecisionRow("候補", if (loaded) "$candidateCount 個" else "読み込み中", candidateCount > 0)
                if (solution != null) {
                    Text(
                        alignmentGrade(solution.maxResidualDeg) +
                            if (solution.count < 2) "（もう 1 点合わせると確かめられます）" else "",
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        color = Color.White.copy(alpha = 0.78f),
                        fontSize = 13.sp,
                    )
                }
            }
        }

        Spacer(Modifier.weight(1f))
        Button(
            onClick = onFinish,
            enabled = solution != null,
            modifier = Modifier.fillMaxWidth().widthIn(max = 340.dp).height(54.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = SaberaGreen,
                contentColor = SaberaOnAccent,
                disabledContainerColor = SaberaGreen.copy(alpha = 0.30f),
                disabledContentColor = Color.White.copy(alpha = 0.55f),
            ),
        ) {
            Text(if (solution == null) "十字を重ねてください" else "この精度で観測へ")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(
                onClick = onAnotherStar,
                enabled = candidateCount > 1,
                colors = ButtonDefaults.textButtonColors(contentColor = SaberaGreen),
            ) {
                Text("別の星にする")
            }
            TextButton(
                onClick = onReset,
                enabled = captured.isNotEmpty(),
                colors = ButtonDefaults.textButtonColors(contentColor = SaberaWarning),
            ) {
                Text("取り直す")
            }
        }
        TextButton(
            onClick = onSkip,
            colors = ButtonDefaults.textButtonColors(contentColor = SaberaGreen),
        ) {
            Text("やめてスマホ合わせのまま進む")
        }
    }
}

@Composable
private fun CompassDial(
    headingDegrees: Double?,
    ready: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = size.minDimension / 2f - 3.dp.toPx()
            val ringColor = Color.White.copy(alpha = 0.42f)
            drawCircle(ringColor, radius, center, style = Stroke(1.5.dp.toPx()))

            repeat(12) { index ->
                val angle = index * 30.0 * PI / 180.0
                val outer = Offset(
                    center.x + sin(angle).toFloat() * radius,
                    center.y - cos(angle).toFloat() * radius,
                )
                val tickLength = if (index % 3 == 0) 10.dp.toPx() else 6.dp.toPx()
                val inner = Offset(
                    center.x + sin(angle).toFloat() * (radius - tickLength),
                    center.y - cos(angle).toFloat() * (radius - tickLength),
                )
                drawLine(
                    color = ringColor,
                    start = inner,
                    end = outer,
                    strokeWidth = if (index % 3 == 0) 2.dp.toPx() else 1.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }

            if (headingDegrees != null) {
                // スマホ上端が headingDegrees を向くので、北はその逆方向へ回して示す
                val northAngle = -headingDegrees * PI / 180.0
                val northTip = Offset(
                    center.x + sin(northAngle).toFloat() * (radius - 13.dp.toPx()),
                    center.y - cos(northAngle).toFloat() * (radius - 13.dp.toPx()),
                )
                val southTip = Offset(
                    center.x - sin(northAngle).toFloat() * (radius - 18.dp.toPx()),
                    center.y + cos(northAngle).toFloat() * (radius - 18.dp.toPx()),
                )
                drawLine(
                    color = Color.White.copy(alpha = 0.38f),
                    start = center,
                    end = southTip,
                    strokeWidth = 3.dp.toPx(),
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = if (ready) SaberaGreen else SaberaWarning,
                    start = center,
                    end = northTip,
                    strokeWidth = 4.dp.toPx(),
                    cap = StrokeCap.Round,
                )

                val directionX = sin(northAngle).toFloat()
                val directionY = -cos(northAngle).toFloat()
                val perpendicularX = -directionY
                val perpendicularY = directionX
                val arrowBaseX = northTip.x - directionX * 11.dp.toPx()
                val arrowBaseY = northTip.y - directionY * 11.dp.toPx()
                val arrow = Path().apply {
                    moveTo(northTip.x, northTip.y)
                    lineTo(
                        arrowBaseX + perpendicularX * 6.dp.toPx(),
                        arrowBaseY + perpendicularY * 6.dp.toPx(),
                    )
                    lineTo(
                        arrowBaseX - perpendicularX * 6.dp.toPx(),
                        arrowBaseY - perpendicularY * 6.dp.toPx(),
                    )
                    close()
                }
                drawPath(arrow, if (ready) SaberaGreen else SaberaWarning)
                drawCircle(Color.White, radius = 3.dp.toPx(), center = center)
            }
        }

        if (headingDegrees == null) {
            Text("—", color = Color.White.copy(alpha = 0.55f))
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    cardinalDirection8(headingDegrees),
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                )
                Text(
                    "${headingDegrees.roundToInt()}°",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.68f),
                )
            }
        }
    }
}

@Composable
private fun AlignmentTarget(
    ready: Boolean,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val color = if (ready) SaberaGreen else Color.White.copy(alpha = 0.86f)
        drawCircle(color.copy(alpha = 0.28f), radius = 82.dp.toPx(), center = center, style = Stroke(2.dp.toPx()))
        drawCircle(color, radius = 28.dp.toPx(), center = center, style = Stroke(3.dp.toPx()))
        drawLine(color, Offset(center.x - 55.dp.toPx(), center.y), Offset(center.x - 34.dp.toPx(), center.y), 3.dp.toPx())
        drawLine(color, Offset(center.x + 34.dp.toPx(), center.y), Offset(center.x + 55.dp.toPx(), center.y), 3.dp.toPx())
        drawLine(color, Offset(center.x, center.y - 55.dp.toPx()), Offset(center.x, center.y - 34.dp.toPx()), 3.dp.toPx())
        drawLine(color, Offset(center.x, center.y + 34.dp.toPx()), Offset(center.x, center.y + 55.dp.toPx()), 3.dp.toPx())
        drawCircle(color, radius = 3.dp.toPx(), center = center)
    }
}

@Composable
private fun PrecisionRow(
    label: String,
    value: String,
    ready: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            modifier = Modifier.weight(0.38f),
            color = Color.White.copy(alpha = 0.68f),
            fontSize = 13.sp,
            maxLines = 1,
            softWrap = false,
        )
        Text(
            value,
            modifier = Modifier.weight(0.52f),
            color = Color.White,
            fontSize = 13.sp,
            maxLines = 1,
            softWrap = false,
            textAlign = TextAlign.End,
        )
        Text(
            if (ready) "✓" else "—",
            modifier = Modifier.weight(0.10f),
            color = if (ready) SaberaGreen else Color.White.copy(alpha = 0.45f),
            fontSize = 13.sp,
            maxLines = 1,
            textAlign = TextAlign.End,
        )
    }
}

private fun calibrationInstruction(
    imuFresh: Boolean,
    headingReady: Boolean,
    compassReady: Boolean,
    facingReady: Boolean,
    stabilityReady: Boolean,
): String = when {
    !imuFresh -> "グラスの6DoFを待っています"
    !headingReady -> "スマホを立ててください"
    !compassReady -> "スマホを8の字に動かしてください"
    !facingReady -> "スマホを視線に正対させてください"
    !stabilityReady -> "そのまま1秒ほど止めてください"
    else -> "センサーを確認しています"
}

private fun compassAccuracyLabel(accuracy: Int): String = when (accuracy) {
    SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> "高い"
    SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> "ふつう"
    SensorManager.SENSOR_STATUS_ACCURACY_LOW -> "低い"
    else -> "信用できない"
}

private fun compactSiteStatus(status: String): String = when {
    "取得済み" in status -> "取得済み"
    "確認中" in status || "取得中" in status -> "取得中"
    else -> "仮設定"
}

/** 案内の 1 行。空文字なら枠ごと消える（[batched] の消し方と同じ） */
private fun guidanceElement(text: String) = if (text.isEmpty()) {
    CommandManager.CanvasElement(id = GUIDANCE_ELEMENT_ID, x = 0, y = 0, width = 0, height = 0, text = "")
} else {
    CommandManager.CanvasElement(
        id = GUIDANCE_ELEMENT_ID,
        x = (PANEL_WIDTH - GUIDANCE_WIDTH) / 2,
        y = PANEL_HEIGHT - CANVAS_LABEL_HEIGHT - 12,
        width = GUIDANCE_WIDTH,
        height = CANVAS_LABEL_HEIGHT,
        text = text,
    )
}

/** 方位合わせの段。段階 2 は任意で、段階 1 の結果を土台にする */
private enum class CalibrationStage {
    PHONE_SYNC,
    CELESTIAL,
}

private const val MAX_TILT_DIFFERENCE_DEG = 3.0
private const val SENSOR_POLL_MS = 100L
private const val IMU_FRESH_MS = 1_000L

/** 磁気の期待値（WMM）の評価は 1 秒ごとで足りる */
private const val MAGNETIC_POLL_MS = 1_000L

/** 候補の選び直しは 0.5 秒ごと。星の位置は 1 秒で 0.004° しか動かない */
private const val GUIDANCE_POLL_MS = 500L

/** 案内の文字。星図と同時には出さないので、テキストの枠は 1 つでよい */
private const val GUIDANCE_ELEMENT_ID = 0
private const val GUIDANCE_WIDTH = 480
