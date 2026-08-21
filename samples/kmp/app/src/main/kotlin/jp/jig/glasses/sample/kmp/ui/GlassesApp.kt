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
import jp.jig.glasses.sample.kmp.starmap.CalibrationSource

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
    // 段階 2（天体アライメント）まで進んだかと、その残差。
    // 残差の意味が合わせ方で変わるので、必ず一緒に持ち回す
    var celestial by rememberSaveable { mutableStateOf(false) }
    var residualDeg by rememberSaveable { mutableDoubleStateOf(-1.0) }
    var targetNames by rememberSaveable { mutableStateOf<String?>(null) }
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
                        celestial = result.source == CalibrationSource.CELESTIAL
                        residualDeg = result.residualDeg ?: -1.0
                        targetNames = result.targetNames
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
                            source = if (celestial) {
                                CalibrationSource.CELESTIAL
                            } else {
                                CalibrationSource.PHONE_SYNC
                            },
                            residualDeg = residualDeg.takeIf { it >= 0.0 },
                            targetNames = targetNames,
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
