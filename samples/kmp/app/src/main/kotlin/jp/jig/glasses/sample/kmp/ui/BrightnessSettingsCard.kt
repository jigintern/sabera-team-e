package jp.jig.glasses.sample.kmp.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import jp.jig.glasses.sample.kmp.glass.GlassBrightness
import kotlin.math.roundToInt

@Composable
internal fun BrightnessSettingsCard(
    level: Int,
    configured: Boolean,
    onLevelChange: (Int) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("グラスの明るさ", style = MaterialTheme.typography.titleMedium)
            Text(
                if (configured) {
                    "手動 ${level + 1}/${GlassBrightness.levelRange.count()}・${GlassBrightness.label(level)}"
                } else {
                    "現在値は取得できません。スライダーを動かすと反映します"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Slider(
                value = level.toFloat(),
                onValueChange = { onLevelChange(it.roundToInt()) },
                valueRange = GlassBrightness.MIN_LEVEL.toFloat()..GlassBrightness.MAX_LEVEL.toFloat(),
                steps = GlassBrightness.levelRange.count() - 2,
            )
        }
    }
}
