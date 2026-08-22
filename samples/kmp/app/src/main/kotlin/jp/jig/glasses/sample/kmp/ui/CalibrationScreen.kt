package jp.jig.glasses.sample.kmp.ui

import android.hardware.SensorManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
import jp.jig.glasses.sample.kmp.starmap.MagneticQuality
import jp.jig.glasses.sample.kmp.starmap.ObservationDefaults
import jp.jig.glasses.sample.kmp.starmap.PANEL_HEIGHT
import jp.jig.glasses.sample.kmp.starmap.PANEL_WIDTH
import jp.jig.glasses.sample.kmp.starmap.cardinalDirection8
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
    // **子の失敗でスコープごと落とさない。** rememberCoroutineScope() は素の Job なので、
    // ここから launch / async したものが 1 つ失敗すると兄弟が全部キャンセルされる。
    // 実機では TTS の先読みが圏外で失敗したとき、6DoF の購読とログまで道連れになった
    val uiScope = rememberCoroutineScope()
    val scope = remember(uiScope) {
        CoroutineScope(uiScope.coroutineContext + SupervisorJob(uiScope.coroutineContext[Job]))
    }
    val commandManager = remember(client) { client.createCommandManager() }
    val imuStarted by commandManager.imuDataStarted.collectAsState()
    val compass = remember { Compass(context) }
    val locator = remember { Locator(context) }
    val estimator = remember { CalibrationEstimator() }

    var magnetic by remember { mutableStateOf<MagneticQuality?>(null) }
    var lastMagneticAt by remember { mutableLongStateOf(0L) }

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
    // 二度渡さないための札。onCalibrated で画面は切り替わるが、
    // そのあとの合成が 1 回走ることがある
    var committed by remember { mutableStateOf(false) }
    // 精度条件が揃ってからの進み具合（0..1）。的の外周のゲージがこれで満ちる
    var holdProgress by remember { mutableFloatStateOf(0f) }

    // **「つながっているのに返事が無い」を出せるようにする。**
    // BLE がつながっていれば connected は true のままなので、切断ダイアログ（GlassesApp）は出ない。
    // 実機では、グラスが再起動したあと 6DoF も画像も一切返さないまま
    // 「グラスの6DoFを待っています」が出続けた（2026-08-22）
    var waitingSince by remember(commandManager) { mutableLongStateOf(System.currentTimeMillis()) }
    var recoveryNote by remember { mutableStateOf<String?>(null) }
    var recovering by remember { mutableStateOf(false) }

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
                // 方位合わせが渡すのは生のヨー基準のオフセット。観測画面のドリフト補正も
                // 入った時点の生のヨーから始まるので、ここで補正を挟むと基準が二重にずれる
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

    suspend fun showMarker() {
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

    LaunchedEffect(Unit) {
        showMarker()
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
    // 土地の期待値と比べて明らかに歪んでいるかは、こちらで見る
    // **歪みでは止めない。** 止めていたときは机の上（ノート PC・ディスプレイ・鉄の脚）で
    // 常時弾かれ、観測画面から先の確認が何もできなかった
    val ready = imuFresh && headingReady && compassReady && facingReady && stabilityReady

    // **グラスが一度も返事をしていない。** つながっているのに黙っているので、
    // 待ち続けても直らない。**このグラスに電源ボタンは無い**ので、
    // 電源を入れ直す手段はここに出すしかない
    val glassSilent = lastImuAt == 0L && now - waitingSince > GLASS_SILENT_MS

    /** 送り直す。**再起動よりこちらが先**（30 秒待たずに済む） */
    fun resend() {
        if (recovering) return
        recovering = true
        recoveryNote = "送り直しています…"
        scope.launch {
            val result = runCatching {
                commandManager.stopImuData()
                delay(RECOVERY_GAP_MS)
                showMarker()
            }
            waitingSince = System.currentTimeMillis()
            recovering = false
            recoveryNote = if (result.isSuccess) {
                "送り直しました。数秒待っても変わらなければ再起動してください"
            } else {
                "送り直せませんでした（${result.exceptionOrNull()?.message}）"
            }
        }
    }

    /**
     * グラスを再起動する。
     *
     * **このグラスには電源ボタンが無い**ので、SDK のデバッグシェル（`dbg reboot`）を叩く以外に
     * 電源を入れ直す方法がない。名前などはリセットされないので、ペアリングは残る。
     */
    fun rebootGlass() {
        if (recovering) return
        recovering = true
        recoveryNote = "再起動を送っています…"
        scope.launch {
            val result = runCatching { client.reboot() }
            waitingSince = System.currentTimeMillis()
            recovering = false
            recoveryNote = if (result.isSuccess) {
                "再起動しました。つながり直すまで 30 秒ほどかかります"
            } else {
                "再起動を送れませんでした（${result.exceptionOrNull()?.message}）"
            }
        }
    }

    /**
     * 観測画面へ渡して確定する。
     *
     * 渡すのは押した瞬間の 1 サンプルではなく、直近の静止区間の平均（[CalibrationEstimator]）。
     */
    fun commit(measured: CalibrationEstimate) {
        if (committed) return
        committed = true
        onCalibrated(
            CalibrationResult(
                headingOffsetDeg = measured.headingOffsetDeg,
                pitchOffsetDeg = measured.pitchOffsetDeg,
                calibratedAt = System.currentTimeMillis(),
                headingStdDeg = measured.headingStdDeg,
                pitchStdDeg = measured.pitchStdDeg,
                sampleCount = measured.sampleCount,
            ),
        )
    }

    /**
     * 揃ったまま数秒止まっていたら、そのまま観測へ進む。**確定の操作は無い。**
     *
     * **押す動作そのものが精度を壊す。** スマホを顔の前にかざして十字と重ねている姿勢では
     * 画面のボタンは見えず、指を伸ばせば頭とスマホの両方が動く。
     *
     * 進み具合は文字ではなく[的の外周][AlignmentTarget]で見せる。**かざしている人が
     * 読めるのは形だけ**で、腕を伸ばした先の文章は読まれない。
     */
    LaunchedEffect(ready) {
        if (!ready) {
            holdProgress = 0f
            return@LaunchedEffect
        }
        val startedAt = System.currentTimeMillis()
        while (true) {
            val held = System.currentTimeMillis() - startedAt
            holdProgress = (held.toFloat() / AUTO_CONFIRM_MS).coerceIn(0f, 1f)
            if (held >= AUTO_CONFIRM_MS) break
            delay(AUTO_CONFIRM_TICK_MS)
        }
        // 待っている数秒で崩れていることがあるので、確定の直前にもう一度見る
        val measured = estimate?.takeIf { it.stable } ?: return@LaunchedEffect
        commit(measured)
    }

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
                        progress = holdProgress,
                        modifier = Modifier.fillMaxWidth().height(230.dp),
                    )
                    // **揃ってからは何も書かない。** 外周のゲージが満ちるのが答えで、
                    // 腕の先のスマホの文章は読まれない。書くのは直すことがあるときだけ
                    if (!ready) {
                        Text(
                            text = if (glassSilent) {
                                "グラスから返事がありません"
                            } else {
                                calibrationInstruction(
                                    imuFresh = imuFresh,
                                    headingReady = headingReady,
                                    compassReady = compassReady,
                                    facingReady = facingReady,
                                    stabilityReady = stabilityReady,
                                )
                            },
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            textAlign = TextAlign.Center,
                        )
                    }
                    // つながっているのに黙っているときだけ出す。
                    // **待っていても直らない**ので、手を出せるものを見せる
                    if (glassSilent) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "つながってはいますが、十字も6DoFも返ってきていません",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.72f),
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.Center) {
                            TextButton(
                                onClick = { resend() },
                                enabled = !recovering,
                                colors = ButtonDefaults.textButtonColors(contentColor = SaberaGreen),
                            ) {
                                Text("送り直す")
                            }
                            TextButton(
                                onClick = { rebootGlass() },
                                enabled = !recovering,
                                colors = ButtonDefaults.textButtonColors(contentColor = SaberaGreen),
                            ) {
                                Text("グラスを再起動")
                            }
                        }
                        recoveryNote?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.72f),
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                    // 歪んでいても押せる。**ただし黙って通さない。**
                    // ここで合わせた方位には歪みぶんの誤差が丸ごと乗る
                    magnetic?.takeIf { it.distorted }?.reason?.let { reason ->
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = reason,
                            style = MaterialTheme.typography.bodyMedium,
                            color = SaberaWarning,
                            textAlign = TextAlign.Center,
                        )
                        Text(
                            text = "このまま合わせると方位がずれます。金属や電子機器から離れてください",
                            style = MaterialTheme.typography.bodySmall,
                            color = SaberaWarning.copy(alpha = 0.8f),
                            textAlign = TextAlign.Center,
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
                                // 赤字は強さだけで出すが、この行は切り分け用なので伏角も見る
                                magnetic?.let { !it.distorted && !it.inclinationOff } == true,
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
            // **確定のボタンは置かない。** 押せるものがあると押しに行き、
            // その動作で頭とスマホが動く。進むのは的の外周が満ちたときだけ
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

/**
 * 合わせる的。**外周が精度のゲージになっている。**
 *
 * かざしている人が読めるのは形だけで、腕を伸ばした先の文章は読まれない。
 * 精度条件が揃っている間だけ [progress] が伸び、満ちたらそのまま観測へ進む。
 * 崩れたら 0 に戻るので、**「あと少し」が手の動きとして分かる**。
 */
@Composable
private fun AlignmentTarget(
    ready: Boolean,
    progress: Float,
    modifier: Modifier = Modifier,
) {
    // 実測値をそのまま描くと 50ms ごとに角度が飛ぶので、なめらかに追わせる
    val swept by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = GAUGE_TWEEN_MS),
        label = "hold",
    )
    // 満ちていく間の脈。**止まっている待ちと、進んでいる待ちを見分けるため**に付ける
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = GAUGE_PULSE_MS),
            repeatMode = RepeatMode.Restart,
        ),
        label = "pulse",
    )

    Canvas(modifier) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val color = if (ready) SaberaGreen else Color.White.copy(alpha = 0.86f)
        val ringRadius = 82.dp.toPx()
        drawCircle(color.copy(alpha = 0.28f), radius = ringRadius, center = center, style = Stroke(2.dp.toPx()))

        if (swept > 0f) {
            // 外へ広がって消える輪。伸びている間だけ出す
            val spread = ringRadius + pulse * GAUGE_PULSE_SPREAD_DP.dp.toPx()
            drawCircle(
                SaberaGreen.copy(alpha = (1f - pulse) * 0.45f * swept.coerceAtMost(1f)),
                radius = spread,
                center = center,
                style = Stroke(6.dp.toPx()),
            )
            // 12 時から時計回りに満ちる
            drawArc(
                color = SaberaGreen,
                startAngle = -90f,
                sweepAngle = 360f * swept,
                useCenter = false,
                topLeft = Offset(center.x - ringRadius, center.y - ringRadius),
                size = Size(ringRadius * 2, ringRadius * 2),
                style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round),
            )
        }

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

/**
 * これだけ待って 6DoF が 1 件も来なければ「返事が無い」とみなす。
 *
 * つながった直後の 1 件目は数秒かかることがあるので、短くしすぎると普通の起動で出てしまう。
 */
private const val GLASS_SILENT_MS = 10_000L

/** 止めてから送り直すまでの間。続けて送ると止まる前の状態に上書きされる */
private const val RECOVERY_GAP_MS = 300L

/** 磁気の期待値（WMM）の評価は 1 秒ごとで足りる */
private const val MAGNETIC_POLL_MS = 1_000L

/** ゲージを描き直す間隔。60fps まで刻む必要はない */
private const val AUTO_CONFIRM_TICK_MS = 50L

/** 実測の進み具合を追いかける時間。飛びを均すだけなので短く */
private const val GAUGE_TWEEN_MS = 120

/** 外へ広がる輪の周期と広がり */
private const val GAUGE_PULSE_MS = 900
private const val GAUGE_PULSE_SPREAD_DP = 14

/**
 * 精度条件が揃ったまま、これだけ続いたら自動で確定する。
 *
 * [CalibrationEstimator] の窓が 1.2 秒で、そのうち 0.8 秒ぶんが揃わないと `stable` にならない。
 * ここで 2 秒足すので、実際には**3 秒ほど止めた区間**を渡すことになる。
 */
private const val AUTO_CONFIRM_MS = 2_000L
