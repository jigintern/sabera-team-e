package jp.jig.glasses.sample.kmp.ui

import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import jp.jig.glasses.sample.kmp.glass.GlassBrightness
import kotlin.math.roundToInt

/**
 * グラスの明るさ。**SDK は現在値を返さない**ので、最後にこのアプリから送った値だけを覚えている。
 *
 * 自動調整に任せると手動値が表示へ反映されないので、送るときは自動調整を先に切る
 * （[jp.jig.glasses.sample.kmp.glass.GlassBrightness]）。設定パネルの区画として組む。
 */
@Composable
internal fun BrightnessSettings(
    level: Int,
    configured: Boolean,
    onLevelChange: (Int) -> Unit,
) {
    SettingsSection(
        "グラスの明るさ",
        if (configured) {
            "手動 ${level + 1}/${GlassBrightness.levelRange.count()}・${GlassBrightness.label(level)}"
        } else {
            "現在値は取得できません。スライダーを動かすと反映します"
        },
    ) {
        Slider(
            value = level.toFloat(),
            onValueChange = { onLevelChange(it.roundToInt()) },
            valueRange = GlassBrightness.MIN_LEVEL.toFloat()..GlassBrightness.MAX_LEVEL.toFloat(),
            steps = GlassBrightness.levelRange.count() - 2,
        )
    }
}
