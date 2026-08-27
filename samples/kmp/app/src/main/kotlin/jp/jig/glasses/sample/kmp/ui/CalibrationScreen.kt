package jp.jig.glasses.sample.kmp.ui

import android.app.Activity
import android.content.pm.ActivityInfo
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.jigglass.glass.GlassClient
import jp.jig.glasses.sample.kmp.alignment.CalibrationEstimate
import jp.jig.glasses.sample.kmp.alignment.CalibrationEstimator
import jp.jig.glasses.sample.kmp.alignment.CalibrationMarker
import jp.jig.glasses.sample.kmp.alignment.CalibrationResult
import jp.jig.glasses.sample.kmp.alignment.HoldFeedback
import jp.jig.glasses.sample.kmp.alignment.Compass
import jp.jig.glasses.sample.kmp.alignment.CompassGate
import jp.jig.glasses.sample.kmp.alignment.Locator
import jp.jig.glasses.sample.kmp.alignment.MAX_TILT_DIFFERENCE_DEG
import jp.jig.glasses.sample.kmp.alignment.MagneticQuality
import jp.jig.glasses.sample.kmp.alignment.TILT_GAUGE_RANGE_DEG
import jp.jig.glasses.sample.kmp.alignment.tiltGaugeGeometry
import jp.jig.glasses.sample.kmp.glass.PANEL_HEIGHT
import jp.jig.glasses.sample.kmp.glass.PANEL_WIDTH
import jp.jig.glasses.sample.kmp.sky.ObservationDefaults
import jp.jig.glasses.sample.kmp.sky.cardinalDirection8
import jp.jig.glasses.sample.kmp.ui.component.ConstellationBackground
import jp.jig.glasses.sample.kmp.ui.component.KeepScreenOn
import jp.jig.glasses.sample.kmp.ui.component.SaberaGreen
import jp.jig.glasses.sample.kmp.ui.component.SaberaWarning
import jp.jig.glasses.sample.kmp.ui.component.SeasonalConstellationBackground
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
    val activity = context as Activity
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

    DisposableEffect(activity) {
        val previousOrientation = activity.requestedOrientation
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        onDispose { activity.requestedOrientation = previousOrientation }
    }

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
    // 8 の字を出したまま待たせすぎていないか。**止めきると較正が上がらない端末で詰む**
    var compassGate by remember { mutableStateOf(CompassGate()) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var lastEstimatedImuAt by remember { mutableLongStateOf(0L) }
    var estimate by remember { mutableStateOf<CalibrationEstimate?>(null) }
    // **センサーの生値以外**（進み具合・確定・立ち直り）は ViewModel が持つ
    val vm = viewModel<CalibrationViewModel>()

    // 顔の前にかざしている人は画面を読めないので、進み具合は手へも返す
    val feedback = remember(context) { HoldFeedback(context) }
    DisposableEffect(Unit) { onDispose { vm.leave() } }


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

    /**
     * 十字を送り、**消されていたら送り直す**。
     *
     * 観測画面から「方位を合わせ直す」で来ると、**あちらの後片付けがこちらの描画より後に走る**。
     * `onDispose` の `stopImuData()` と `closeCanvas()` が、この画面が送った直後に届くので、
     * **十字が消え、6DoF まで止まる**（実機で「十字が出ない」「6DoFを待っています」の両方が起きた）。
     * 画像は 128×128 で 6 パケットほどなので、送り直しの代償は小さい。
     */
    LaunchedEffect(Unit) {
        showMarker()
        var confirmed = false
        repeat(MARKER_REASSERT_TIMES) {
            delay(MARKER_REASSERT_MS)
            if (confirmed) return@LaunchedEffect
            showMarker()
            // 6DoF が来ていれば生きている。**そのあと 1 回だけ送り直して**止める
            confirmed = lastImuAt != 0L
        }
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
            // すでに 100ms で回っているので、逃がすためのタイマーを別に立てない
            compassGate = compassGate.advance(
                nowMillis = now,
                accurate = compassAccuracy >= SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM,
                // **正対は条件に入れない。** 一瞬崩れるたびに待ち時間が 0 に戻り、上限に届かない
                prompting = imuStarted && now - lastImuAt < IMU_FRESH_MS && phoneHeading != null,
            )
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

    // **符号を捨てない。** 姿勢計が「上げるのか下げるのか」を出すのに向きが要る
    val tiltDifference = if (phonePitch != null && glassPitch != null) {
        phonePitch!! - glassPitch!!
    } else {
        null
    }
    val imuFresh = imuStarted && now - lastImuAt < IMU_FRESH_MS
    val headingReady = phoneHeading != null
    // **生の信頼度と、進んでよいかを分ける。** 逃がしたときに「磁気精度は足りている」と
    // 見せてしまうと、ずれた方位で合わせたことが誰にも分からなくなる
    val compassAccurate = compassAccuracy >= SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM
    val compassReady = compassGate.ready(compassAccurate)
    val facingReady = tiltDifference != null && abs(tiltDifference) <= MAX_TILT_DIFFERENCE_DEG
    val stabilityReady = estimate?.stable == true
    // OS の「磁気精度は高い」はキャリブレーションが済んだかしか言わない。
    // 土地の期待値と比べて明らかに歪んでいるかは、こちらで見る
    // **歪みでは止めない。** 止めていたときは机の上（ノート PC・ディスプレイ・鉄の脚）で
    // 常時弾かれ、観測画面から先の確認が何もできなかった
    val ready = calibrationReady(imuFresh, headingReady, compassReady, facingReady, stabilityReady)

    // **グラスが一度も返事をしていない。** つながっているのに黙っているので、
    // 待ち続けても直らない。**このグラスに電源ボタンは無い**ので、
    // 電源を入れ直す手段はここに出すしかない
    val glassSilent = lastImuAt == 0L && now - vm.waitingSince > GLASS_SILENT_MS

    /** 送り直す。**再起動よりこちらが先**（30 秒待たずに済む） */
    fun resend() = vm.recover(
        running = "送り直しています…",
        done = "送り直しました。数秒待っても変わらなければ再起動してください",
        failed = { "送り直せませんでした（$it）" },
    ) {
        commandManager.stopImuData()
        delay(RECOVERY_GAP_MS)
        showMarker()
    }

    /**
     * グラスを再起動する。
     *
     * **このグラスには電源ボタンが無い**ので、SDK のデバッグシェル（`dbg reboot`）を叩く以外に
     * 電源を入れ直す方法がない。名前などはリセットされないので、ペアリングは残る。
     */
    fun rebootGlass() = vm.recover(
        running = "再起動を送っています…",
        done = "再起動しました。つながり直すまで $GLASS_REBOOT_SECONDS 秒ほどかかります",
        failed = { "再起動を送れませんでした（$it）" },
    ) {
        client.reboot()
    }

    /**
     * 観測画面へ渡して確定する。
     *
     * 渡すのは押した瞬間の 1 サンプルではなく、直近の静止区間の平均（[CalibrationEstimator]）。
     */
    fun commit(measured: CalibrationEstimate) =
        vm.commit(measured, System.currentTimeMillis(), onCalibrated)

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
            // **崩れたことも手に返す。** 顔の前のスマホは見えないので、
            // 進み具合が 0 に戻ったことを画面で知らせても届かない
            if (vm.holdProgress > 0f) feedback.lost()
            vm.resetHold()
            return@LaunchedEffect
        }
        // **揃った瞬間に 1 回。** ここから数えはじめる合図
        feedback.start()
        val startedAt = System.currentTimeMillis()
        var notches = 0
        while (true) {
            val held = System.currentTimeMillis() - startedAt
            vm.advanceHold(held)
            // **満ちていく途中も刻む。** あと少しなのか、始まったばかりなのかが手で分かる
            val notch = (vm.holdProgress * HOLD_NOTCHES).toInt()
            if (notch > notches) {
                notches = notch
                feedback.tick()
            }
            if (held >= AUTO_CONFIRM_MS) break
            delay(AUTO_CONFIRM_TICK_MS)
        }
        // 待っている数秒で崩れていることがあるので、確定の直前にもう一度見る
        val measured = estimate?.takeIf { it.stable } ?: return@LaunchedEffect
        // **決まったときだけ強く。** 押していないのに終わるので、終わった合図が要る
        feedback.done()
        commit(measured)
    }

    // 十字を丸に重ねている最中に消えると、やり直しになる
    KeepScreenOn()

    Box(Modifier.fillMaxSize()) {
        SeasonalConstellationBackground(
            constellation = constellation,
            modifier = Modifier.fillMaxSize(),
        )
        Column(
            modifier = Modifier.fillMaxSize().padding(top = 32.dp)
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
                modifier = Modifier.widthIn(max = 380.dp).fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xED0C151D)),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    AlignmentTarget(
                        ready = ready,
                        progress = vm.holdProgress,
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
                        // 上の行が「グラスから返事がありません」なので、ここは手当てだけ書く
                        Text(
                            text = "送り直すか、グラスを再起動してください",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.72f),
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.Center) {
                            TextButton(
                                onClick = { resend() },
                                enabled = !vm.recovering,
                                colors = ButtonDefaults.textButtonColors(contentColor = SaberaGreen),
                            ) {
                                Text("送り直す")
                            }
                            TextButton(
                                onClick = { rebootGlass() },
                                enabled = !vm.recovering,
                                colors = ButtonDefaults.textButtonColors(contentColor = SaberaGreen),
                            ) {
                                Text("グラスを再起動")
                            }
                        }
                        vm.recoveryNote?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.72f),
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                    // 較正が上がらない端末で止めきると、方位合わせから先へ進めなくなる。
                    // **通すが、黙っては通さない**（歪みの警告と同じ扱い）
                    if (compassGate.bypassed && !compassAccurate) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "磁気の精度が上がりません",
                            style = MaterialTheme.typography.bodyMedium,
                            color = SaberaWarning,
                            textAlign = TextAlign.Center,
                        )
                        Text(
                            text = "このまま進めますが、方位はずれます",
                            style = MaterialTheme.typography.bodySmall,
                            color = SaberaWarning.copy(alpha = 0.8f),
                            textAlign = TextAlign.Center,
                        )
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
                            text = "金属や電子機器から離れてください",
                            style = MaterialTheme.typography.bodySmall,
                            color = SaberaWarning.copy(alpha = 0.8f),
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Card(
                modifier = Modifier.widthIn(max = 380.dp).fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xED152028)),
            ) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // **方角と仰角を縦に積む。** この画面は縦固定なので横並びは作らない
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
                                modifier = Modifier.size(DIAL_SIZE_DP.dp),
                            )
                            Spacer(Modifier.height(12.dp))
                            Text(
                                "仰角差",
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White.copy(alpha = 0.68f),
                            )
                            Spacer(Modifier.height(6.dp))
                            TiltGauge(
                                differenceDegrees = tiltDifference,
                                modifier = Modifier.size(DIAL_SIZE_DP.dp),
                            )
                        }
                        VerticalDivider(
                            modifier = Modifier.height(DIAL_COLUMN_HEIGHT_DP.dp).padding(horizontal = 10.dp),
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
                            // **符号を出す。** 隣の姿勢計とずれの向きが食い違うと、どちらを
                            // 信じるか分からなくなる（実機での切り分けに数値そのものは残す）
                            PrecisionRow(
                                "仰角差",
                                tiltDifference?.let { "%+.1f°".format(it) } ?: "取得中",
                                facingReady,
                            )
                            PrecisionRow("6DoF", if (imuFresh) "受信中" else "待機中", imuFresh)
                            // 逃がしても緑にしない。**進めたことと、精度が足りたことは別**
                            PrecisionRow("磁気精度", compassAccuracyLabel(compassAccuracy), compassAccurate)
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
 * 仰角差の姿勢計。**水平線が中心へ上がってくれば合っている。**
 *
 * 数字の「仰角差 2.4°」だけでは、あとどれだけ・**どちらへ倒すか**が手の動きにならない。
 * 航空機の姿勢計と同じで、**中心の機体マークは固定、水平線だけが動く**。
 * スマホが上を向きすぎていれば水平線は下に出るので、見たまま下げれば 0 に近づく。
 */
@Composable
private fun TiltGauge(
    differenceDegrees: Double?,
    modifier: Modifier = Modifier,
) {
    val geometry = differenceDegrees?.let { tiltGaugeGeometry(it) }
    // 生値は 10Hz で飛ぶ。的の外周と同じだけ均す
    val offset by animateFloatAsState(
        targetValue = geometry?.horizonOffset ?: 0f,
        animationSpec = tween(durationMillis = GAUGE_TWEEN_MS),
        label = "tilt",
    )
    val within = geometry?.within == true

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = size.minDimension / 2f - 3.dp.toPx()
            val ringColor = if (within) SaberaGreen else Color.White.copy(alpha = 0.42f)

            if (geometry != null) {
                // 円の中だけを塗る。**外へはみ出すと隣のダイヤルと地続きに見える**
                val face = Path().apply {
                    addOval(Rect(center - Offset(radius, radius), center + Offset(radius, radius)))
                }
                clipPath(face) {
                    val horizon = center.y + offset * radius
                    drawRect(
                        color = TILT_SKY,
                        topLeft = Offset(center.x - radius, center.y - radius),
                        size = Size(radius * 2, (horizon - (center.y - radius)).coerceAtLeast(0f)),
                    )
                    drawRect(
                        color = TILT_GROUND,
                        topLeft = Offset(center.x - radius, horizon.coerceAtMost(center.y + radius)),
                        size = Size(radius * 2, (center.y + radius - horizon).coerceAtLeast(0f)),
                    )
                    // ピッチの目盛り。**水平線と一緒に動く**ので、どれだけ離れているかが読める
                    for (tick in TILT_TICK_DEGREES) {
                        val ratio = tick / TILT_GAUGE_RANGE_DEG
                        listOf(1.0, -1.0).forEach { side ->
                            val y = horizon - (ratio * side).toFloat() * radius
                            if (y < center.y - radius || y > center.y + radius) return@forEach
                            val arm = radius * TILT_TICK_ARM
                            drawLine(
                                Color.White.copy(alpha = 0.55f),
                                Offset(center.x - arm, y),
                                Offset(center.x + arm, y),
                                1.5.dp.toPx(),
                            )
                        }
                    }
                    val horizonColor = if (within) SaberaGreen else Color.White
                    drawLine(
                        horizonColor,
                        Offset(center.x - radius, horizon),
                        Offset(center.x + radius, horizon),
                        2.5.dp.toPx(),
                    )
                }
                // 振り切れている側の縁に三角を出す。**まだ先があることを端で見せる**
                if (geometry.pegged) {
                    val up = geometry.horizonOffset < 0f
                    val tipY = if (up) center.y - radius + 4.dp.toPx() else center.y + radius - 4.dp.toPx()
                    val baseY = if (up) tipY + 9.dp.toPx() else tipY - 9.dp.toPx()
                    drawPath(
                        Path().apply {
                            moveTo(center.x, tipY)
                            lineTo(center.x - 7.dp.toPx(), baseY)
                            lineTo(center.x + 7.dp.toPx(), baseY)
                            close()
                        },
                        SaberaWarning,
                    )
                }
            }

            drawCircle(ringColor, radius, center, style = Stroke(1.5.dp.toPx()))

            // 機体マーク。**固定**。水平線がここへ重なったら正対
            val markColor = if (within) SaberaGreen else Color.White
            val stroke = 2.5.dp.toPx()
            val inner = radius * 0.16f
            val outer = radius * 0.62f
            drawLine(markColor, Offset(center.x - outer, center.y), Offset(center.x - inner, center.y), stroke)
            drawLine(markColor, Offset(center.x + inner, center.y), Offset(center.x + outer, center.y), stroke)
            drawCircle(markColor, radius = 2.5.dp.toPx(), center = center)
        }

        if (geometry == null) {
            Text("—", style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.45f))
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

/**
 * 揃っていない条件を 1 つだけ文にする。**優先順は直す順**（上を直さないと下は直せない）。
 *
 * **判定していない動作を指示しない。** [headingReady] は「回転ベクトルが 1 件来たか」だけを
 * 見ていて姿勢は見ていないのに、以前は「スマホを立ててください」と出していた。
 * 背面の向きは**画面法線まわりの回転に不変**なので、横に寝かせても方位も仰角も変わらない。
 * 立てても何も変わらないことを人に指示していた（#79）。
 */
internal fun calibrationInstruction(
    imuFresh: Boolean,
    headingReady: Boolean,
    compassReady: Boolean,
    facingReady: Boolean,
    stabilityReady: Boolean,
): String = when {
    !imuFresh -> "グラスの動きを待っています"
    !headingReady -> "スマホの向きを待っています"
    !compassReady -> "スマホを8の字に動かす"
    !facingReady -> "スマホを顔の正面へ"
    !stabilityReady -> "そのまま1秒止める"
    else -> "確認しています"
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

private const val SENSOR_POLL_MS = 100L
private const val IMU_FRESH_MS = 1_000L

/**
 * これだけ待って 6DoF が 1 件も来なければ「返事が無い」とみなす。
 *
 * つながった直後の 1 件目は数秒かかることがあるので、短くしすぎると普通の起動で出てしまう。
 */
private const val GLASS_SILENT_MS = 10_000L

/** 再起動してからつながり直すまでの目安[秒]。**文言と同じ数字をここから出す** */
private const val GLASS_REBOOT_SECONDS = 30

/** 止めてから送り直すまでの間。続けて送ると止まる前の状態に上書きされる */
private const val RECOVERY_GAP_MS = 300L

/** 十字を送り直す間隔と回数。**前の画面の後片付けが届くまで**をまたげればよい */
private const val MARKER_REASSERT_MS = 1_200L
private const val MARKER_REASSERT_TIMES = 5

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
 * 方角ダイヤルと姿勢計の大きさ。**縦に 2 つ積むので 108dp から落とした。**
 *
 * 精度カードが伸びると下の「ホーム」が画面外へ出る。実機で溢れたらここを下げる
 */
private const val DIAL_SIZE_DP = 96

/** 2 つ積んだ左の列の高さ。仕切り線をここに合わせる（ラベル 2 つと間隔を含む） */
private const val DIAL_COLUMN_HEIGHT_DP = 252

/** 姿勢計の空と地。**屋外で見るので中間の色は使わない** */
private val TILT_SKY = Color(0xFF17384F)
private val TILT_GROUND = Color(0xFF4A3520)

/** 水平線から上下へ引く目盛りの位置[度]と、その長さ（半径に対する割合） */
private val TILT_TICK_DEGREES = listOf(5.0, 10.0)
private const val TILT_TICK_ARM = 0.34f

/**
 * 精度条件が揃ったまま、これだけ続いたら自動で確定する。
 *
 * [CalibrationEstimator] の窓が 1.2 秒で、そのうち 0.8 秒ぶんが揃わないと `stable` にならない。
 * ここで 2 秒足すので、実際には**3 秒ほど止めた区間**を渡すことになる。
 */
internal const val AUTO_CONFIRM_MS = 2_000L

/**
 * 保持のあいだに手へ返す刻みの数。
 *
 * **かざしている人は画面を読めない**（腕の先で十字に重ねている）ので、
 * 外周が満ちるのと同じことを振動でも返す。細かくしすぎると鳴りっぱなしになり、
 * 何の合図か分からなくなる（時刻のつまみで踏んだのと同じ）。
 */
private const val HOLD_NOTCHES = 4
