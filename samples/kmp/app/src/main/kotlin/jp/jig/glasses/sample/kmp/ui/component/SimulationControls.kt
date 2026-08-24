package jp.jig.glasses.sample.kmp.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import jp.jig.glasses.sample.kmp.sky.CityCatalog

/** 圏外でも場所と日時を指定できる、スマホ側のシミュレーション操作。 */
@Composable
internal fun SimulationControls(
    status: String,
    simulation: Boolean,
    playing: Boolean,
    cityText: String,
    dateText: String,
    timeText: String,
    message: String?,
    onCityChange: (String) -> Unit,
    onDateChange: (String) -> Unit,
    onTimeChange: (String) -> Unit,
    onApply: () -> Unit,
    onPlay: () -> Unit,
    onStop: () -> Unit,
    onReturnLive: () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SaberaSurface),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text("時間を指定した星空", style = MaterialTheme.typography.titleMedium)
            Text(
                status,
                color = if (simulation) MaterialTheme.colorScheme.primary else Color.White,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = cityText,
                onValueChange = onCityChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("都市（例：シドニー）") },
                singleLine = true,
            )
            Text(
                "対応：${CityCatalog.cities.joinToString("・") { it.nameJa }}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = dateText,
                    onValueChange = onDateChange,
                    modifier = Modifier.weight(1f),
                    label = { Text("日付（省略可）") },
                    placeholder = { Text("2026/8/24") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = timeText,
                    onValueChange = onTimeChange,
                    modifier = Modifier.weight(0.72f),
                    label = { Text("時刻") },
                    placeholder = { Text("20:30") },
                    singleLine = true,
                )
            }
            Button(onClick = onApply, modifier = Modifier.fillMaxWidth()) {
                Text("この条件の空を表示")
            }
            if (message != null) {
                Text(message, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(4.dp))
            }
            if (simulation) {
                if (playing) {
                    OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) {
                        Text("時間再生を停止")
                    }
                } else {
                    OutlinedButton(onClick = onPlay, modifier = Modifier.fillMaxWidth()) {
                        Text("時間を進める（2秒ごとに10分）")
                    }
                }
                OutlinedButton(onClick = onReturnLive, modifier = Modifier.fillMaxWidth()) {
                    Text("現在の空に戻る")
                }
            }
            Text(
                "都市データと日時計算は圏外でも利用できます",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
