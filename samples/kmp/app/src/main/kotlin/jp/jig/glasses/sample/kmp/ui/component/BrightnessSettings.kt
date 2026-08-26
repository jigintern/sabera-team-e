package jp.jig.glasses.sample.kmp.ui.component

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import jp.jig.glasses.sample.kmp.glass.GlassBrightness
import kotlin.math.roundToInt

/**
 * グラスの明るさ。**SDK は現在値を返さない**ので、最後にこのアプリから送った値だけを覚えている。
 *
 * ファーム側の自動調整に任せると手動値が表示へ反映されないので、送るときは自動調整を先に切る
 * （[jp.jig.glasses.sample.kmp.glass.GlassBrightness]）。**アプリ側の「空の暗さに合わせる」は別物**で、
 * こちらは太陽高度から段を決めて手動値として送っている。
 *
 * **既定は空に合わせる。** 夜の屋外で設定パネルを開かせないため。
 * スライダーを動かしたらその夜は手動のままにする（**合わせた値を勝手に戻さない**）。
 * 覚えさせないのは、翌日はまた合わせるところから始めたほうがよいから。
 */
@Composable
internal fun BrightnessSettings(
    level: Int,
    configured: Boolean,
    auto: Boolean,
    onLevelChange: (Int) -> Unit,
    onAutoRestore: () -> Unit,
) {
    SettingsSection(
        "グラスの明るさ",
        Icons.Filled.Brightness6,
        when {
            auto -> "空の暗さに合わせている（${GlassBrightness.label(level)}）"
            configured -> "手動 ${level + 1}/${GlassBrightness.levelRange.count()}・${GlassBrightness.label(level)}"
            else -> "動かすと反映する（現在値は読み戻せない）"
        },
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Brightness6,
                "明るさ",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 12.dp).size(22.dp),
            )
            Slider(
                value = level.toFloat(),
                onValueChange = { onLevelChange(it.roundToInt()) },
                valueRange = GlassBrightness.MIN_LEVEL.toFloat()..GlassBrightness.MAX_LEVEL.toFloat(),
                steps = GlassBrightness.levelRange.count() - 2,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "${level + 1}/${GlassBrightness.levelRange.count()}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // 手動にしたあと戻す道。**戻せないと、一度触っただけでその夜ずっと手動になる**
        if (!auto) {
            OutlinedButton(onClick = onAutoRestore, modifier = Modifier.fillMaxWidth()) {
                Text("空の暗さに合わせる", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
