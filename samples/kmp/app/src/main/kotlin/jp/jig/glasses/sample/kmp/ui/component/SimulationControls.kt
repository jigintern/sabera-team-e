package jp.jig.glasses.sample.kmp.ui.component

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import jp.jig.glasses.sample.kmp.sky.SkyPreset
import jp.jig.glasses.sample.kmp.sky.SkyPresets

/**
 * いま出ている星空の時刻を動かす。
 *
 * **押すたび・離すたびに 1 枚だけ送る。** 連続再生（2 秒ごとに 10 分ずつ自動で送る）は
 * やめた。1 枚 279〜390ms の全画面転送を繰り返すことになり、**転送のたびにパネルが
 * 消えるので原理的に点滅する**（[docs/team-e/11_pitfalls.md]）。
 * ボタンは「1 時間ずつ動かす」に読み替えてあるので、1 回押して 1 回描き直すだけで済む。
 */
@Composable
internal fun TimeScrubControls(
    /** いま出ている（つまんでいる間は**これから出す**）空の時刻 */
    label: String,
    /** 基準からのずれ。つまんでいる間だけ出す */
    detail: String?,
    scrubbing: Boolean,
    /** つまみの位置。**その夜の中を ±12 時間**（0 が条件で指定した時刻） */
    offsetHours: Float,
    onScrub: (Float) -> Unit,
    onScrubFinished: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    Column(Modifier.fillMaxWidth()) {
        // **読みは、つまみの上に置く。** 指がスライダーに乗るので、下だと隠れる。
        // **1 時間ずつのボタンは置かない** — つまみが 15 分刻みで止まるので同じことが指でできる
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.titleMedium,
                color = if (scrubbing) MaterialTheme.colorScheme.primary else Color.White,
            )
            if (detail != null) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        // **離すまで空を送らない。** つまんでいる間ずっと星図を焼くと、
        // 1 枚 332〜390ms かかるので転送が追いつかず、指の動きから遅れて出続ける
        var lastNotch by remember { mutableStateOf(Int.MIN_VALUE) }
        Slider(
            value = offsetHours,
            onValueChange = { value ->
                // **目盛りをまたいだときだけ震わせる。** 動かすたびに震わせると、
                // 指の細かい揺れで鳴り続けて何の合図か分からなくなる
                val notch = Math.round(value / TIME_SCRUB_NOTCH_HOURS)
                if (notch != lastNotch) {
                    lastNotch = notch
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
                onScrub(value)
            },
            onValueChangeFinished = {
                lastNotch = Int.MIN_VALUE
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onScrubFinished()
            },
            valueRange = -TIME_SCRUB_HOURS..TIME_SCRUB_HOURS,
            // **15 分刻みで止める。** 星は 4 分で 1° しか動かないので、
            // それより細かく選ばせても見分けられないうえ、読みが半端な数字になる
            steps = TIME_SCRUB_STEPS,
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

/** 15 分刻み。±12 時間 ＝ 96 目盛りなので、あいだの数はその 1 つ手前 */
const val TIME_SCRUB_STEPS = 95

/** 震わせる間隔。目盛りと同じ 15 分 */
private const val TIME_SCRUB_NOTCH_HOURS = 0.25f

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
    message: String?,
    onPlaceChange: (SkyPreset) -> Unit,
    onEraChange: (SkyPreset) -> Unit,
    onTimeChange: (SkyPreset) -> Unit,
    onApply: () -> Unit,
    onReturnLive: () -> Unit,
    /** 見出しのすぐ下に置くもの（時刻を動かすつまみ）。**同じ「いつの空か」の操作なので束ねる** */
    header: @Composable ColumnScope.() -> Unit = {},
) {
    // **普段は「いまの空」で足りる。** 見出しに状態を出したうえで畳んでおき、
    // 場所や時代を変えたい人だけが開く（開閉は覚えない＝次に開いたときは畳んである）
    CollapsibleSection("星空の条件", Icons.Filled.Schedule, status) {
        Column(Modifier.fillMaxWidth()) {
            header()
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
            // **押すものは「この空を見る」1 つに見せる。** 戻る口は再現中しか要らないので、
            // 枠を持たない文字にして、並んだボタンに見せない
            if (simulation) {
                TextButton(onClick = onReturnLive, modifier = Modifier.fillMaxWidth()) {
                    Text("現在の空に戻る")
                }
            }
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
