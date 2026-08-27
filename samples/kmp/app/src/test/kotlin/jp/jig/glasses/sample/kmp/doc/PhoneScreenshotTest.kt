package jp.jig.glasses.sample.kmp.doc

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.jigglass.glass.CommandManager
import jp.jig.glasses.sample.kmp.guide.GuideStore
import jp.jig.glasses.sample.kmp.sound.Bgm
import jp.jig.glasses.sample.kmp.sound.SoundPrefs
import jp.jig.glasses.sample.kmp.narration.SkyTip
import jp.jig.glasses.sample.kmp.ui.AuthoredGuideScreen
import app.jigglass.glass.GlassManager
import jp.jig.glasses.sample.kmp.ui.CalibrationScreen
import jp.jig.glasses.sample.kmp.ui.ConnectionCheckScreen
import jp.jig.glasses.sample.kmp.ui.GuideScreen
import jp.jig.glasses.sample.kmp.ui.GuideShareScreen
import jp.jig.glasses.sample.kmp.ui.HomeScreen
import jp.jig.glasses.sample.kmp.ui.StarMapScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * スマホ側のデモ画像を、**アプリと同じ Compose を描いて**書き出す。
 *
 * 絵に描き起こすと実装から離れていくので、実物の画面をそのまま撮る。
 * BLE は無いので[FakeGlass] が「つながったふり」をし、6DoF は
 * [settle] が流し込む——**星図のプレビューは実際に描画されたもの**。
 *
 * **これは実機の写真ではない。** 端末ごとの字送り・実際の転送時間・測位の結果は出ない。
 * 出るのは「どの画面に何が並んでいるか」まで。
 *
 *   ./gradlew :app:testDebugUnitTest --tests '*PhoneScreenshotTest*'
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
class PhoneScreenshotTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `入口`() {
        show {
            HomeScreen(
                constellation = DemoData.constellation(),
                tip = SkyTip("今夜のひとこと", "月はまだ出ていません。暗い星まで見える夜です。"),
                onStart = {},
                onGuides = {},
            )
        }
        shoot("phone-home")
    }

    @Test
    fun `つなぐ`() {
        val manager = GlassManager(rule.activity)
        show {
            ConnectionCheckScreen(
                manager = manager,
                client = FakeGlass.client(),
                constellation = DemoData.constellation(),
                onContinue = {},
                onHome = {},
                onDisconnect = {},
            )
        }
        settle(800)
        shoot("phone-connection")
    }

    @Test
    fun `方位合わせ`() {
        val sensors = DemoSensors(rule.activity)
        show {
            CalibrationScreen(
                client = FakeGlass.client(),
                constellation = DemoData.constellation(),
                onCalibrated = {},
                onHome = {},
            )
        }
        settle(6_000) { sensors.aim(DEMO_AZIMUTH, -DEMO_PITCH.toDouble()) }
        shoot("phone-calibration")
    }

    /** 星空・設定・開発者用画面は同じ画面の 3 ページなので、1 回の起動で撮る */
    @Test
    fun `星空と設定`() {
        showStarMap()
        useNightSky()
        settle(6_000)
        shoot("phone-star-map")

        rule.onNodeWithText("星空の条件").performClick()
        settle(600)
        shoot("phone-sky-condition")
        rule.onNodeWithText("星空の条件").performClick()

        rule.onNodeWithContentDescription("設定").performClick()
        settle(600)
        shoot("phone-settings")

        rule.onNodeWithText("開発者用画面").performScrollTo().performClick()
        settle(600)
        // **畳んだままでは何が入っているか分からない。** 見本では開いて撮る
        for (section in listOf("観測の状態", "記録")) {
            rule.onNodeWithText(section).performScrollTo().performClick()
            settle(200)
        }
        shoot("phone-developer")
    }

    @Test
    fun `ガイド一覧`() {
        GuideStore.of(rule.activity).save(DemoData.guide())
        show {
            GuideScreen(
                constellation = DemoData.constellation(),
                onBack = {},
                onAuthor = {},
                onShare = {},
                onImport = {},
            )
        }
        settle(1_500)
        shoot("phone-guides")
    }

    @Test
    fun `台本を書く`() {
        show {
            AuthoredGuideScreen(
                constellation = DemoData.constellation(),
                initial = DemoData.guide(),
                onBack = {},
                onShare = {},
            )
        }
        settle(1_000)
        shoot("phone-guide-author")
    }

    @Test
    fun `台本を配る`() {
        show {
            GuideShareScreen(
                constellation = DemoData.constellation(),
                guide = DemoData.guide(),
                onBack = {},
            )
        }
        settle(1_500)
        shoot("phone-guide-share")
    }

    // ------------------------------------------------------------------
    // 撮る道具
    // ------------------------------------------------------------------

    /**
     * 星図の画面を出す。
     *
     * **テーマは包まない。** 画面が自分で `SaberaDarkColorScheme` を敷いているので、
     * ここで重ねると実機と違う色で撮れる。
     */
    private fun showStarMap() {
        val context = rule.activity
        show {
            StarMapScreen(
                client = FakeGlass.client(),
                initialCalibration = null,
                constellation = DemoData.constellation(),
                bgm = Bgm(context, CoroutineScope(SupervisorJob())),
                soundPrefs = SoundPrefs(context),
                onRecalibrate = {},
                onGuides = {},
                onRequestLeave = {},
            )
        }
    }

    /**
     * **昼に撮っても夜空が写るようにする。**
     *
     * 時計は動かせない（`System.currentTimeMillis()` は実時間のまま）ので、
     * アプリ自身の「星空の条件」で**その日の 21 時**へ寄せる。既定は 1 万年前なので、
     * 時代だけ「今夜」に戻してから決める。
     */
    private fun useNightSky() {
        rule.onNodeWithText("星空の条件").performClick()
        rule.onNodeWithText("1万年前").performClick()
        rule.onNodeWithText("今夜").performClick()
        rule.onNodeWithText("この空を見る").performClick()
    }

    private fun show(content: @Composable () -> Unit) {
        rule.setContent { content() }
        rule.waitForIdle()
    }

    private fun shoot(name: String) {
        rule.waitForIdle()
        val view = rule.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val out = File(OUTPUT_DIR, "$name.png")
        out.parentFile!!.mkdirs()
        FileOutputStream(out).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("$name ${bitmap.width}x${bitmap.height}")
    }

    /**
     * 6DoF を流しながら時間を進める。
     *
     * **同じ向きのまま送り続ける**ので、描き直しの条件（0.18 秒静止）を満たして
     * 星図が 1 枚焼かれる。`Thread.sleep` を挟むのは、アプリが実時間で
     * 静止を測っているため（仮想時計では止まったことにならない）。
     */
    private fun settle(totalMs: Long, each: () -> Unit = {}) {
        var elapsed = 0L
        while (elapsed < totalMs) {
            each()
            FakeGlass.imu.tryEmit(
                CommandManager.ImuData(
                    System.currentTimeMillis(), 0, 0, 1_000, 0f, 0f, 0f, DEMO_PITCH, DEMO_YAW,
                ),
            )
            rule.mainClock.advanceTimeBy(STEP_MS)
            ShadowLooper.idleMainLooper(STEP_MS, TimeUnit.MILLISECONDS)
            rule.waitForIdle()
            Thread.sleep(SLEEP_MS)
            elapsed += STEP_MS
        }
    }

    private companion object {
        const val OUTPUT_DIR = "build/doc-images/phone"
        const val STEP_MS = 100L
        const val SLEEP_MS = 20L

        /** 見上げた向き。**南の空を 25° 見上げている** */
        const val DEMO_PITCH = -25f
        const val DEMO_YAW = 0f
        const val DEMO_AZIMUTH = 180.0
    }
}
