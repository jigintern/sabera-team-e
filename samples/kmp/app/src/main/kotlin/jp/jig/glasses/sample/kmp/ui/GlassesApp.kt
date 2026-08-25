package jp.jig.glasses.sample.kmp.ui

import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.jigglass.glass.CommandManager
import app.jigglass.glass.GlassClient
import app.jigglass.glass.GlassManager
import jp.jig.glasses.sample.kmp.R
import jp.jig.glasses.sample.kmp.alignment.CalibrationResult
import jp.jig.glasses.sample.kmp.glass.GlassTextArt
import jp.jig.glasses.sample.kmp.glass.PANEL_HEIGHT
import jp.jig.glasses.sample.kmp.glass.PANEL_WIDTH
import jp.jig.glasses.sample.kmp.glass.STAR_MAP_IMAGE_ID
import jp.jig.glasses.sample.kmp.glass.clearedCanvasText
import jp.jig.glasses.sample.kmp.narration.SkyTips
import jp.jig.glasses.sample.kmp.narration.tonightSky
import jp.jig.glasses.sample.kmp.sky.ObservationDefaults
import jp.jig.glasses.sample.kmp.sky.SkyDarkness
import jp.jig.glasses.sample.kmp.sky.sunAltitudeDeg
import jp.jig.glasses.sample.kmp.sound.Bgm
import jp.jig.glasses.sample.kmp.sound.BgmScene
import jp.jig.glasses.sample.kmp.sound.SoundPrefs
import jp.jig.glasses.sample.kmp.ui.component.SaberaGreen
import jp.jig.glasses.sample.kmp.ui.component.SaberaOnAccent
import jp.jig.glasses.sample.kmp.ui.component.SaberaSurface
import jp.jig.glasses.sample.kmp.ui.component.SaberaWarning
import jp.jig.glasses.sample.kmp.ui.component.rememberSeasonalConstellation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.random.Random

/**
 * ホームから観測を始め、未接続なら接続確認、接続済みなら方位合わせを経て星図へ進む。
 *
 * SDKの汎用サンプル画面は撤去済み。APIの使い方は上流SDKの公開ドキュメントを参照する。
 */
@Composable
fun GlassesApp(manager: GlassManager) {
    var screen by rememberSaveable { mutableStateOf(AppScreen.HOME) }
    var headingOffset by rememberSaveable { mutableDoubleStateOf(0.0) }
    var pitchOffset by rememberSaveable { mutableDoubleStateOf(0.0) }
    var headingStd by rememberSaveable { mutableDoubleStateOf(0.0) }
    var pitchStd by rememberSaveable { mutableDoubleStateOf(0.0) }
    var calibrationSamples by rememberSaveable { mutableLongStateOf(0L) }
    var calibratedAt by rememberSaveable { mutableLongStateOf(0L) }
    val connectedClient by manager.connectedDevice.collectAsState(initial = null)
    var observingClient by remember { mutableStateOf<GlassClient?>(null) }
    var connectionLost by rememberSaveable { mutableStateOf(false) }
    val constellation = rememberSeasonalConstellation()
    val context = LocalContext.current
    val splashLogo = remember(context) {
        requireNotNull(BitmapFactory.decodeResource(context.resources, R.drawable.hoshishirube_logo))
    }

    /**
     * 起動直後にグラスへ出すひとことの番号。**起動ごとに変える。**
     *
     * 毎回同じ文が出ると、出ていること自体に気づかなくなる。
     * `SkyTips` 側で件数の剰余を取るので、大きい数でよい。
     */
    val splashTip = remember { Random.nextInt(SPLASH_TIP_SPREAD) }

    /**
     * 起動直後の表示を送る口。**画面が変わるたびに作り直さない。**
     * `createCommandManager()` は呼ぶたびに通知の購読を足すので、積み上がる。
     */
    val splashCommands = remember(connectedClient) { connectedClient?.createCommandManager() }

    /** 戻るキーで観測をやめようとしているか。**一度の誤操作で観測を畳まない** */
    var confirmLeaving by rememberSaveable { mutableStateOf(false) }

    /**
     * BGM は**アプリを開いた時点から鳴らす**（#69）。
     *
     * 以前は星図の画面が [Bgm] を持っていたので、**ホーム・接続・方位合わせが無音**で、
     * 星図に入った瞬間に音が始まっていた。目指しているのはミニプラネタリウムなので、
     * 入口から鳴っているほうがよい。切ってある人（`bgmEnabled`）には鳴らない。
     *
     * ここが持つのは**画面をまたいで鳴らし続けるため**で、音量と曲の指名は設定パネルが動かす。
     */
    val soundScope = rememberCoroutineScope()
    val soundPrefs = remember(context) { SoundPrefs(context) }
    val bgm = remember(context) {
        Bgm(context, soundScope).apply {
            // **鳴らし始めるのは場面が決まってから**（下の LaunchedEffect）
            restore(
                enabled = soundPrefs.bgmEnabled,
                volume = soundPrefs.bgmVolume,
                pinned = soundPrefs.bgmTrack,
            )
        }
    }
    DisposableEffect(bgm) { onDispose { bgm.release() } }

    /**
     * 入口の画面（ホーム・ガイド作成・接続・方位合わせ）で鳴らす曲。
     * **星図と同じ束を共用する**（専用の曲は持たない）。
     *
     * 観測地はまだ測っていないので既定値（[ObservationDefaults]）で太陽高度を出す。
     * **薄暮か夜かを決めるだけ**なので、これで足りる。星図に入ったら
     * [StarMapScreen] が測位済みの観測地で出した値で上書きする。
     */
    LaunchedEffect(screen) {
        if (screen == AppScreen.STAR_MAP) return@LaunchedEffect
        val altitude = sunAltitudeDeg(ObservationDefaults.site, System.currentTimeMillis())
        bgm.scene = BgmScene.of(SkyDarkness.of(altitude))
    }

    /**
     * 戻るキーで 1 つ前の画面へ戻す。
     *
     * **既定のままだと戻るキーでアプリが終わる。** 観測中に終わると方位合わせからやり直しなので、
     * 星図の画面だけは確認を挟む（設定パネルを開いているときは
     * [StarMapScreen] 側の `BackHandler` が先に受けて、パネルを閉じるだけになる）。
     * ホームでは受けない。**そこは終わってよい場所**で、握るとアプリを閉じられなくなる。
     */
    BackHandler(enabled = screen != AppScreen.HOME) {
        when (screen) {
            AppScreen.HOME -> Unit
            AppScreen.GUIDES -> screen = AppScreen.HOME
            AppScreen.CONNECTION -> screen = AppScreen.HOME
            AppScreen.CALIBRATION -> screen = AppScreen.CONNECTION
            AppScreen.STAR_MAP -> confirmLeaving = true
        }
    }

    // connectedDeviceは切断直後にnullになるため、猶予時間中もconnectedを確認できるよう最後のClientを保持する。
    LaunchedEffect(connectedClient) {
        if (connectedClient != null) observingClient = connectedClient
    }

    // 瞬断で観測画面を追い出さないよう、観測中だけ一定間隔でconnectedを確認する。
    LaunchedEffect(screen, observingClient) {
        if (screen != AppScreen.CALIBRATION && screen != AppScreen.STAR_MAP) {
            connectionLost = false
            return@LaunchedEffect
        }
        val client = observingClient ?: return@LaunchedEffect
        var disconnectedAt: Long? = null
        while (true) {
            if (client.connected.value) {
                disconnectedAt = null
            } else {
                val now = SystemClock.elapsedRealtime()
                val startedAt = disconnectedAt ?: now.also { disconnectedAt = it }
                if (now - startedAt >= CONNECTION_LOST_GRACE_MS) connectionLost = true
            }
            delay(CONNECTION_CHECK_INTERVAL_MS)
        }
    }

    /**
     * ホームと接続確認の間、グラスに「星しるべ」と今日のひとことを出す。
     *
     * **つないでから最初の星図が届くまで、グラスは真っ暗だった。** ホームも接続確認も
     * グラスへ一度も送っていなかったので、かけている人には**動いているのかどうかも
     * 分からない**（「タップして無反応が一番よくない」と同じ話）。
     *
     * 採用ロゴとひとことを 1 枚の画像に焼く。ロゴの専用字形と本文の大きさを両立するには、
     * フォントを指定できないテキスト枠では組めない。
     *
     * ひとことは**起動ごとに違うものから始める**（[splashTip]）。毎回同じ文が出ると、
     * 出ていること自体に気づかなくなる。
     *
     * 観測地は既定値（[ObservationDefaults]）。**まだ測位していない**ので、
     * 場所によって変わるメモは少しずれるが、ここは挨拶なので追わない。
     */
    LaunchedEffect(screen, connectedClient) {
        val client = connectedClient
        // ガイドを作っている間もグラスは挨拶のまま。**ここではグラスを使わない**ので、
        // 真っ暗にしてしまうと、つながっているのか分からなくなる
        val greeting = screen == AppScreen.HOME || screen == AppScreen.CONNECTION ||
            screen == AppScreen.GUIDES
        val commands = splashCommands
        if (client == null || commands == null || !greeting) return@LaunchedEffect
        val sky = tonightSky(context, ObservationDefaults.site, System.currentTimeMillis())
        val tip = SkyTips.of(sky, splashTip)
        // **画像に焼く。** テキスト枠では字の大きさを変えられないので、
        // 専用ロゴと本文を中央に揃える組み方ができない
        val art = withContext(Dispatchers.Default) {
            GlassTextArt.splash(logo = splashLogo, body = tip.text)
        }
        try {
            // **先にテキスト枠を掃除する。** ファームは消すまで文字を持ち続けるので、
            // 前に動いていたときの星座名などが残っていると、この画像に重なって出る（実機で踏んだ）
            commands.sendCanvasElements(clearedCanvasText())
            // **動かないので 1 回だけ送る。** 1 枚 332〜390ms かかるが、置いておくだけなら気にならない
            commands.sendCanvasImage(
                id = STAR_MAP_IMAGE_ID,
                x = (PANEL_WIDTH - art.width) / 2,
                y = (PANEL_HEIGHT - art.height) / 2,
                width = art.width,
                height = art.height,
                grayscale = art.gray,
            )
            awaitCancellation()
        } finally {
            // **消してから次の画面へ渡す。** 方位合わせは別の id（十字）を送るので、
            // ここを残すと十字の裏に挨拶が残ったままになる
            withContext(NonCancellable) {
                runCatching { commands.removeCanvasImage(STAR_MAP_IMAGE_ID) }
            }
        }
    }

    when (screen) {
        AppScreen.HOME -> HomeScreen(
            constellation = constellation,
            onStart = { screen = AppScreen.CONNECTION },
            onGuides = { screen = AppScreen.GUIDES },
        )
        AppScreen.GUIDES -> GuideScreen(
            constellation = constellation,
            onBack = { screen = AppScreen.HOME },
        )
        AppScreen.CONNECTION -> ConnectionCheckScreen(
            manager = manager,
            client = connectedClient,
            constellation = constellation,
            onContinue = { screen = AppScreen.CALIBRATION },
            onHome = { screen = AppScreen.HOME },
        )
        AppScreen.CALIBRATION -> {
            val currentClient = observingClient
            if (currentClient == null) {
                ConnectionCheckScreen(
                    manager = manager,
                    client = null,
                    constellation = constellation,
                    onContinue = { screen = AppScreen.CALIBRATION },
                    onHome = { screen = AppScreen.HOME },
                )
            } else {
                CalibrationScreen(
                    client = currentClient,
                    constellation = constellation,
                    onCalibrated = { result ->
                        headingOffset = result.headingOffsetDeg
                        pitchOffset = result.pitchOffsetDeg
                        headingStd = result.headingStdDeg
                        pitchStd = result.pitchStdDeg
                        calibrationSamples = result.sampleCount.toLong()
                        calibratedAt = result.calibratedAt
                        screen = AppScreen.STAR_MAP
                    },
                    onHome = { screen = AppScreen.HOME },
                )
            }
        }
        AppScreen.STAR_MAP -> {
            val currentClient = observingClient
            if (currentClient == null) {
                ConnectionCheckScreen(
                    manager = manager,
                    client = null,
                    constellation = constellation,
                    onContinue = { screen = AppScreen.CALIBRATION },
                    onHome = { screen = AppScreen.HOME },
                )
            } else {
                StarMapScreen(
                    client = currentClient,
                    initialCalibration = calibratedAt.takeIf { it > 0L }?.let {
                        CalibrationResult(
                            headingOffsetDeg = headingOffset,
                            pitchOffsetDeg = pitchOffset,
                            calibratedAt = it,
                            headingStdDeg = headingStd,
                            pitchStdDeg = pitchStd,
                            sampleCount = calibrationSamples.toInt(),
                        )
                    },
                    constellation = constellation,
                    // 画面をまたいで鳴らし続けるので、ここで作ったものを渡す（#69）
                    bgm = bgm,
                    soundPrefs = soundPrefs,
                    onRecalibrate = { screen = AppScreen.CALIBRATION },
                )
            }
        }
    }

    if (connectionLost) {
        ConnectionLostDialog(
            onConnectionCheck = {
                connectionLost = false
                observingClient = null
                screen = AppScreen.CONNECTION
            },
        )
    }

    // 切断のダイアログが出ているなら、そちらが先。重ねて出さない
    if (confirmLeaving && !connectionLost) {
        LeaveObservationDialog(
            onLeave = {
                confirmLeaving = false
                screen = AppScreen.HOME
            },
            onStay = { confirmLeaving = false },
        )
    }
}

/**
 * 観測をやめるかの確認。
 *
 * **方位合わせをやり直すことになるので、一度の戻るキーでは畳まない。**
 * ホームへ戻ってもう一度観測に入るには、接続確認と方位合わせを通る必要がある。
 */
@Composable
private fun LeaveObservationDialog(onLeave: () -> Unit, onStay: () -> Unit) {
    Dialog(onDismissRequest = onStay) {
        Card(
            modifier = Modifier.fillMaxWidth().widthIn(max = 360.dp),
            colors = CardDefaults.cardColors(containerColor = SaberaSurface),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "観測をやめますか",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "ホームへ戻ると、方位合わせからやり直しになります",
                    color = Color.White.copy(alpha = 0.68f),
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = onStay,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SaberaGreen,
                        contentColor = SaberaOnAccent,
                    ),
                ) {
                    Text("観測を続ける")
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onLeave, modifier = Modifier.fillMaxWidth()) {
                    Text("やめてホームへ", color = SaberaWarning)
                }
            }
        }
    }
}

@Composable
private fun ConnectionLostDialog(onConnectionCheck: () -> Unit) {
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
    ) {
        Card(
            modifier = Modifier.fillMaxWidth().widthIn(max = 360.dp),
            colors = CardDefaults.cardColors(containerColor = SaberaSurface),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "SABERAとの接続が切れました",
                    style = MaterialTheme.typography.titleLarge,
                    color = SaberaWarning,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "SABERAの電源とBluetoothを確認してください",
                    color = Color.White,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "接続確認画面に戻って接続状態を確認してください",
                    color = Color.White.copy(alpha = 0.68f),
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = onConnectionCheck,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SaberaGreen,
                        contentColor = SaberaOnAccent,
                    ),
                ) {
                    Text("接続確認へ")
                }
            }
        }
    }
}

private enum class AppScreen {
    HOME,

    /** ガイドの台本を作る。**グラスをつなぐ前に通る**ので、接続の外側に置く */
    GUIDES,

    CONNECTION,
    CALIBRATION,
    STAR_MAP,
}

/** 起動ごとのひとことを散らす幅。件数より十分大きければよい */
private const val SPLASH_TIP_SPREAD = 1_000

private const val CONNECTION_CHECK_INTERVAL_MS = 1_000L
private const val CONNECTION_LOST_GRACE_MS = 2_000L
