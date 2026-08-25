package jp.jig.glasses.sample.kmp.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import jp.jig.glasses.sample.kmp.sky.SkyPreset
import jp.jig.glasses.sample.kmp.sky.SkyPresets

/**
 * いま出ている星空の時間だけを送る。
 *
 * **アイコン 1 行だけにしてある。** 見出しと説明文を置いた大きなカードは、
 * すぐ下にある星座解説の場所を食う。観測中にいちばん使うのは解説なので、そこを譲らない。
 */
@Composable
internal fun TimePlaybackControls(
    playing: Boolean,
    forward: Boolean,
    status: String,
    /** つまみの位置。**その夜の中を ±12 時間**（0 が指定した時刻） */
    offsetHours: Float,
    onRewind: () -> Unit,
    onStop: () -> Unit,
    onForward: () -> Unit,
    onScrub: (Float) -> Unit,
    onScrubFinished: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            TransportButton(
                shape = TransportShape.REWIND,
                active = playing && !forward,
                description = "時間を戻す",
                onClick = onRewind,
            )
            TransportButton(
                shape = TransportShape.PAUSE,
                active = false,
                enabled = playing,
                description = "時間送りを止める",
                onClick = onStop,
            )
            TransportButton(
                shape = TransportShape.FORWARD,
                active = playing && forward,
                description = "時間を進める",
                onClick = onForward,
            )
        }
        // **離すまで空を送らない。** つまんでいる間ずっと星図を焼くと、
        // 1 枚 332〜390ms かかるので転送が追いつかず、指の動きから遅れて出続ける
        Slider(
            value = offsetHours,
            onValueChange = onScrub,
            onValueChangeFinished = onScrubFinished,
            valueRange = -TIME_SCRUB_HOURS..TIME_SCRUB_HOURS,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "−12時間",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(status, style = MaterialTheme.typography.bodySmall)
            Text(
                "＋12時間",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * つまみで動かせる幅。**その夜の中だけ。**
 *
 * 日をまたいで動かしたいなら「時代」の選択肢を使う。ここを広げると、
 * **1 目盛りが粗くなって「もう少しだけ動かす」ができなくなる**。
 */
const val TIME_SCRUB_HOURS = 12f

private enum class TransportShape { REWIND, PAUSE, FORWARD }

/**
 * 三角と棒だけで描く。
 *
 * `material-icons` を足すと依存が増えるうえ、要るのは 3 つだけ。
 * **形はどれも三角か長方形なので、Canvas で描いたほうが持ち物が減る。**
 * 形だけでは何のボタンか分からないので、読み上げ用の名前は必ず付ける。
 */
@Composable
private fun TransportButton(
    shape: TransportShape,
    active: Boolean,
    description: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val tint = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
        active -> MaterialTheme.colorScheme.primary
        else -> Color.White
    }
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.semantics { contentDescription = description },
    ) {
        Canvas(Modifier.size(18.dp)) {
            val w = size.width
            val h = size.height
            when (shape) {
                TransportShape.PAUSE -> {
                    val bar = w * 0.3f
                    drawPath(rectPath(0f, 0f, bar, h), tint)
                    drawPath(rectPath(w - bar, 0f, w, h), tint)
                }
                // 三角を 2 つ並べる。1 つだけだと「送り続ける」と「1 コマ進める」が見分けられない
                TransportShape.FORWARD -> {
                    drawPath(trianglePath(0f, w / 2f, h, pointsRight = true), tint)
                    drawPath(trianglePath(w / 2f, w, h, pointsRight = true), tint)
                }
                TransportShape.REWIND -> {
                    drawPath(trianglePath(0f, w / 2f, h, pointsRight = false), tint)
                    drawPath(trianglePath(w / 2f, w, h, pointsRight = false), tint)
                }
            }
        }
    }
}

private fun trianglePath(left: Float, right: Float, height: Float, pointsRight: Boolean): Path =
    Path().apply {
        if (pointsRight) {
            moveTo(left, 0f)
            lineTo(right, height / 2f)
            lineTo(left, height)
        } else {
            moveTo(right, 0f)
            lineTo(left, height / 2f)
            lineTo(right, height)
        }
        close()
    }

private fun rectPath(left: Float, top: Float, right: Float, bottom: Float): Path =
    Path().apply {
        moveTo(left, top)
        lineTo(right, top)
        lineTo(right, bottom)
        lineTo(left, bottom)
        close()
    }

/**
 * 圏外でも場所と日時を指定できる、設定画面だけの観測条件。
 *
 * **ふだんは選ぶだけ。** 使う人は天文の初心者で、
 * 「紀元前 3000 年 8 月 24 日 20:30」を打ちたいわけではない。
 * 数字を打つ欄は「細かく指定する」を開いたときだけ出す。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SkyConditionSettings(
    status: String,
    simulation: Boolean,
    place: SkyPreset,
    era: SkyPreset,
    time: SkyPreset,
    detailed: Boolean,
    cityText: String,
    eraText: String,
    dateText: String,
    timeText: String,
    message: String?,
    onPlaceChange: (SkyPreset) -> Unit,
    onEraChange: (SkyPreset) -> Unit,
    onTimeChange: (SkyPreset) -> Unit,
    onDetailedChange: (Boolean) -> Unit,
    onCityTextChange: (String) -> Unit,
    onEraTextChange: (String) -> Unit,
    onDateTextChange: (String) -> Unit,
    onTimeTextChange: (String) -> Unit,
    onApply: () -> Unit,
    onReturnLive: () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SaberaSurface),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text("星空の条件", style = MaterialTheme.typography.titleMedium)
            Text(
                status,
                color = if (simulation) MaterialTheme.colorScheme.primary else Color.White,
                style = MaterialTheme.typography.bodyMedium,
            )

            PresetRow("場所", SkyPresets.places, place, onPlaceChange)
            PresetRow("時代", SkyPresets.eras, era, onEraChange)
            PresetRow("時刻", SkyPresets.times, time, onTimeChange)

            Spacer(Modifier.height(10.dp))
            Button(onClick = onApply, modifier = Modifier.fillMaxWidth()) {
                Text("この空を見る")
            }
            if (message != null) {
                Spacer(Modifier.height(4.dp))
                Text(message, style = MaterialTheme.typography.bodySmall)
            }
            if (simulation) {
                Spacer(Modifier.height(4.dp))
                OutlinedButton(onClick = onReturnLive, modifier = Modifier.fillMaxWidth()) {
                    Text("現在の空に戻る")
                }
            }

            TextButton(onClick = { onDetailedChange(!detailed) }) {
                Text(if (detailed) "細かい指定を閉じる" else "細かく指定する")
            }
            if (detailed) {
                // **打ったほうを優先する。** わざわざ開いて入れた指定を、
                // 上の選択肢が黙って上書きしたら開いた意味がない
                Text(
                    "打った欄が優先されます。空欄なら上の選択肢を使います",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = cityText,
                    onValueChange = onCityTextChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("都市") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = eraText,
                    onValueChange = onEraTextChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("時代") },
                    placeholder = { Text("紀元前3000年 / 2000年後") },
                    singleLine = true,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = dateText,
                        onValueChange = onDateTextChange,
                        modifier = Modifier.weight(1f),
                        label = { Text("日付") },
                        placeholder = { Text("2026/8/24") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = timeText,
                        onValueChange = onTimeTextChange,
                        modifier = Modifier.weight(0.72f),
                        label = { Text("時刻") },
                        placeholder = { Text("20:30") },
                        singleLine = true,
                    )
                }
            }
            Text(
                "都市データと日時計算は圏外でも使えます",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PresetRow(
    title: String,
    options: List<SkyPreset>,
    selected: SkyPreset,
    onSelect: (SkyPreset) -> Unit,
) {
    Spacer(Modifier.height(8.dp))
    Text(
        title,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (option in options) {
            FilterChip(
                selected = option.label == selected.label,
                onClick = { onSelect(option) },
                label = { Text(option.label) },
            )
        }
    }
}
