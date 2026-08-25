package jp.jig.glasses.sample.kmp.ui.component

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import jp.jig.glasses.sample.kmp.sky.SkyPreset
import jp.jig.glasses.sample.kmp.sky.SkyPresets

/**
 * いま出ている星空の時刻だけを、**つまんで動かす**。
 *
 * **連続再生（2 秒ごとに 10 分ずつ送る）はやめた。** 1 枚 279〜390ms の全画面転送を
 * 繰り返すことになり、**転送のたびにパネルが消えるので原理的に点滅する**
 * （[docs/team-e/11_pitfalls.md] の「なめらかに追従させる」と同じ壁）。
 * つまみなら**離したときの 1 回だけ**送るので、条件を切り替えるのと変わらない。
 */
@Composable
internal fun TimeScrubControls(
    status: String,
    /** つまみの位置。**その夜の中を ±12 時間**（0 が条件で指定した時刻） */
    offsetHours: Float,
    onScrub: (Float) -> Unit,
    onScrubFinished: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
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

/**
 * 圏外でも場所と日時を指定できる、設定画面だけの観測条件。
 *
 * **ふだんは選ぶだけ。** 使う人は天文の初心者で、
 * 「紀元前 3000 年 8 月 24 日 20:30」を打ちたいわけではない。
 * 数字を打つ欄は「細かく指定する」を開いたときだけ出す。
 */
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

/**
 * 選択肢を**横一列のトグル**で出す。
 *
 * **折り返さず横スクロールにしてある。** 場所は 19 個あるので、折り返すと
 * 画面の半分がチップの壁になり、**その下にある「この空を見る」まで届かない**。
 * 3 つとも同じ形にしているのは、**選び方を 1 つだけ覚えれば済む**ようにするため。
 */
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
    Spacer(Modifier.height(2.dp))
    val scroll = rememberScrollState()
    Row(
        Modifier.fillMaxWidth().horizontalScroll(scroll),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (option in options) {
            val chosen = option.label == selected.label
            FilterChip(
                selected = chosen,
                onClick = { onSelect(option) },
                label = { Text(option.label) },
                // **選んだものに印を付ける。** 色の差だけだと、屋外の明るさで見分けにくい
                leadingIcon = if (chosen) {
                    { Text("✓", style = MaterialTheme.typography.labelMedium) }
                } else {
                    null
                },
            )
        }
    }
    // **選んだものは端に隠れないよう先頭へ寄せ直す**（19 個あると流れて見えなくなる）
    LaunchedEffect(selected.label, options.size) {
        val index = options.indexOfFirst { it.label == selected.label }
        if (index >= 0) {
            scroll.animateScrollTo((scroll.maxValue * index / options.size.coerceAtLeast(1)))
        }
    }
}
