package jp.jig.glasses.sample.kmp.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.jigglass.glass.GlassManager

private enum class ConnectedScreen {
    COMMAND,
    TELEPROMPTER,
    AI_CHAT,
    TRANSLATE,
    IMAGE,
    NAVI,
    IMU,
    MIC,
    LAYOUT,
    CANVAS,
    STAR_MAP,
}

@Composable
fun GlassesApp(manager: GlassManager) {
    val client by manager.connectedDevice.collectAsState(initial = null)
    // 星図を試すのが目的なので、繋がったらそのまま星図に入る。他のサンプルは「戻る」の先にある
    var screen by remember { mutableStateOf(ConnectedScreen.STAR_MAP) }

    val currentClient = client
    if (currentClient == null) {
        ScanScreen(manager = manager)
        return
    }

    when (screen) {
        ConnectedScreen.COMMAND -> CommandScreen(
            manager = manager,
            client = currentClient,
            onOpenTeleprompterScreen = { screen = ConnectedScreen.TELEPROMPTER },
            onOpenAiChatScreen = { screen = ConnectedScreen.AI_CHAT },
            onOpenTranslateScreen = { screen = ConnectedScreen.TRANSLATE },
            onOpenImageScreen = { screen = ConnectedScreen.IMAGE },
            onOpenNaviScreen = { screen = ConnectedScreen.NAVI },
            onOpenImuScreen = { screen = ConnectedScreen.IMU },
            onOpenMicScreen = { screen = ConnectedScreen.MIC },
            onOpenLayoutScreen = { screen = ConnectedScreen.LAYOUT },
            onOpenCanvasScreen = { screen = ConnectedScreen.CANVAS },
            onOpenStarMapScreen = { screen = ConnectedScreen.STAR_MAP },
        )

        ConnectedScreen.TELEPROMPTER -> TeleprompterScreen(
            client = currentClient,
            onBack = { screen = ConnectedScreen.COMMAND },
        )

        ConnectedScreen.AI_CHAT -> AiChatScreen(
            client = currentClient,
            onBack = { screen = ConnectedScreen.COMMAND },
        )

        ConnectedScreen.TRANSLATE -> TranslateScreen(
            client = currentClient,
            onBack = { screen = ConnectedScreen.COMMAND },
        )

        ConnectedScreen.IMAGE -> ImageScreen(
            client = currentClient,
            onBack = { screen = ConnectedScreen.COMMAND },
        )

        ConnectedScreen.NAVI -> NaviScreen(
            client = currentClient,
            onBack = { screen = ConnectedScreen.COMMAND },
        )

        ConnectedScreen.IMU -> ImuScreen(
            client = currentClient,
            onBack = { screen = ConnectedScreen.COMMAND },
        )

        ConnectedScreen.MIC -> MicScreen(
            client = currentClient,
            onBack = { screen = ConnectedScreen.COMMAND },
        )

        ConnectedScreen.LAYOUT -> LayoutScreen(
            client = currentClient,
            onBack = { screen = ConnectedScreen.COMMAND },
        )

        ConnectedScreen.CANVAS -> CanvasScreen(
            client = currentClient,
            onBack = { screen = ConnectedScreen.COMMAND },
        )

        ConnectedScreen.STAR_MAP -> StarMapScreen(
            client = currentClient,
            onBack = { screen = ConnectedScreen.COMMAND },
        )
    }
}
