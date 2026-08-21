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
import app.jigglass.glass.GlassClient
import jp.jig.glasses.sample.kmp.starmap.CalibrationEstimate
import jp.jig.glasses.sample.kmp.starmap.CalibrationEstimator
import jp.jig.glasses.sample.kmp.starmap.CalibrationMarker
import jp.jig.glasses.sample.kmp.starmap.CalibrationResult
import jp.jig.glasses.sample.kmp.starmap.Compass
import jp.jig.glasses.sample.kmp.starmap.Locator
import jp.jig.glasses.sample.kmp.starmap.ObservationDefaults
import jp.jig.glasses.sample.kmp.starmap.PANEL_HEIGHT
import jp.jig.glasses.sample.kmp.starmap.PANEL_WIDTH
import jp.jig.glasses.sample.kmp.starmap.cardinalDirection8
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
                glassYaw = data.yawDegrees.toDouble()
                glassPitch = -data.pitchDegrees.toDouble()
                lastImuAt = System.currentTimeMillis()
            }
        }
        onDispose {
            imuJob.cancel()
            commandManager.stopImuData()
            commandManager.removeCanvasImage(CalibrationMarker.IMAGE_ID)
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
    val ready = imuFresh && headingReady && compassReady && facingReady && stabilityReady

    Box(Modifier.fillMaxSize()) {
        SeasonalConstellationBackground(
            constellation = constellation,
            modifier = Modifier.fillMaxSize(),
        )

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
                        text = if (ready) "この位置で合わせられます" else calibrationInstruction(
                            imuFresh = imuFresh,
                            headingReady = headingReady,
                            compassReady = compassReady,
                            facingReady = facingReady,
                            stabilityReady = stabilityReady,
                        ),
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
            TextButton(
                onClick = onHome,
                colors = ButtonDefaults.textButtonColors(contentColor = SaberaGreen),
            ) {
                Text("ホーム")
            }
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

private const val MAX_TILT_DIFFERENCE_DEG = 3.0
private const val SENSOR_POLL_MS = 100L
private const val IMU_FRESH_MS = 1_000L
