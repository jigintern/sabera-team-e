package jp.jig.glasses.sample.kmp.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import app.jigglass.glass.GlassManager
import jp.jig.glasses.sample.kmp.starmap.CalibrationResult

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
    val client by manager.connectedDevice.collectAsState(initial = null)
    val constellation = rememberSeasonalConstellation()

    when (screen) {
        AppScreen.HOME -> HomeScreen(
            constellation = constellation,
            onStart = { screen = AppScreen.CONNECTION },
        )
        AppScreen.CONNECTION -> ConnectionCheckScreen(
            manager = manager,
            client = client,
            constellation = constellation,
            onContinue = { screen = AppScreen.CALIBRATION },
            onHome = { screen = AppScreen.HOME },
        )
        AppScreen.CALIBRATION -> {
            val currentClient = client
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
            val currentClient = client
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
                    onRecalibrate = { screen = AppScreen.CALIBRATION },
                )
            }
        }
    }
}

private enum class AppScreen {
    HOME,
    CONNECTION,
    CALIBRATION,
    STAR_MAP,
}
