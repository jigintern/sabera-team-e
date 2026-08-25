package jp.jig.glasses.sample.kmp.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import jp.jig.glasses.sample.kmp.guide.GuideCodec
import jp.jig.glasses.sample.kmp.guide.GuideSchedule
import jp.jig.glasses.sample.kmp.guide.GuideStep
import jp.jig.glasses.sample.kmp.sky.GuidanceTarget
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 詳細エディタ（toB）の部品。
 *
 * **画面の骨は `ui/AuthoredGuideScreen.kt`。** ここには見た目だけを置き、
 * 判断（何が候補になるか・QR に入るか・長すぎるか）は `guide/` が持つ。
 */

private val DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("M/d(E) HH:mm")
private val TIME_ONLY: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

fun formatDateTime(millis: Long): String =
    DATE_TIME.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

fun formatTime(millis: Long): String =
    TIME_ONLY.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

/**
 * 想定したツアー。**再生には使わない**（作るときの検算だけ）。
 *
 * 場所を打ち替えられるのが要点。旅行会社は**事務所で書いて現地で使う**ので、
 * 測位のままだと東京の空でツアーを組んでしまう。
 */
@Composable
internal fun PlanningCard(
    startMillis: Long,
    minutes: Int,
    latDeg: Double,
    lonDeg: Double,
    siteNote: String,
    onPickDateTime: () -> Unit,
    onMinutes: (Int) -> Unit,
    onLatLon: (String, String) -> Unit,
) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = SaberaSurface)) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text("いつ・どこで", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(formatDateTime(startMillis), Modifier.weight(1f))
                OutlinedButton(onClick = onPickDateTime) { Text("日時を選ぶ") }
            }
            Spacer(Modifier.height(8.dp))
            Text("所要時間", style = MaterialTheme.typography.bodySmall, color = SaberaFinePrint)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (choice in GuideSchedule.MINUTE_CHOICES) {
                    FilterChip(
                        selected = choice == minutes,
                        onClick = { onMinutes(choice) },
                        label = { Text("$choice 分") },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = latDeg.toString(),
                    onValueChange = { onLatLon(it, lonDeg.toString()) },
                    label = { Text("緯度") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = lonDeg.toString(),
                    onValueChange = { onLatLon(latDeg.toString(), it) },
                    label = { Text("経度") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
            }
            Text(siteNote, style = MaterialTheme.typography.bodySmall, color = SaberaFinePrint)
        }
    }
}

/** その日その時間に出ている対象。**選べるのはここに出たものだけ**（空に無いものを載せない） */
@Composable
internal fun CandidateCard(
    candidates: List<GuidanceTarget>,
    chosen: (String) -> Boolean,
    loading: Boolean,
    onToggle: (GuidanceTarget) -> Unit,
    onRefresh: () -> Unit,
) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = SaberaSurface)) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("その日に見えるもの", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = onRefresh) { Text("出し直す") }
            }
            when {
                loading -> LoadingPanel(
                    text = "その日の空を調べています",
                    hint = "ツアーの間ずっと見えるものを探しています",
                    modifier = Modifier.fillMaxWidth().height(96.dp),
                )

                candidates.isEmpty() -> Text(
                    "その日時に高く上がるものがありません。時間をずらしてみてください",
                    style = MaterialTheme.typography.bodySmall,
                    color = SaberaFinePrint,
                )

                else -> Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                    for (target in candidates) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(target.nameJa, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "高さ ${target.aim.altDeg.toInt()} 度",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = SaberaFinePrint,
                                )
                            }
                            TextButton(onClick = { onToggle(target) }) {
                                Text(if (chosen(target.nameJa)) "外す" else "入れる")
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * AI との相談。**候補の中からしか選ばせない**ので、ここで打つのは「どう回るか」だけ。
 *
 * 圏外では口ごと出さない（[online] が false）。黙って同梱に落とすと、
 * 書いた本人が「AI が書いた」と思ったまま持ち出すことになる。
 */
@Composable
internal fun GuideChatCard(
    online: Boolean,
    keyConfigured: Boolean,
    turns: List<Pair<Boolean, String>>,
    input: String,
    busy: Boolean,
    remainingTurns: Int,
    onInput: (String) -> Unit,
    onSend: () -> Unit,
) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = SaberaSurface)) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text("AI に相談する", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            when {
                !keyConfigured -> Text(
                    "AI の設定がありません。同梱の解説文を入れて、文面はここで直してください",
                    style = MaterialTheme.typography.bodySmall,
                    color = SaberaWarning,
                )

                !online -> Text(
                    "いまは通信がありません。同梱の解説文を入れます",
                    style = MaterialTheme.typography.bodySmall,
                    color = SaberaWarning,
                )

                else -> {
                    if (turns.isNotEmpty()) {
                        Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
                            for ((fromUser, text) in turns) {
                                Text(
                                    text,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (fromUser) MaterialTheme.colorScheme.onSurface else SaberaGreen,
                                    fontWeight = if (fromUser) FontWeight.Bold else FontWeight.Normal,
                                )
                                Spacer(Modifier.height(6.dp))
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                    OutlinedTextField(
                        value = input,
                        onValueChange = onInput,
                        label = { Text("「秋の星座で 40 分」「子ども向けに」") },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !busy && remainingTurns > 0,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (remainingTurns > 0) "あと $remainingTurns 回" else "この台本での相談は終わりです",
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = SaberaFinePrint,
                        )
                        OutlinedButton(onClick = onSend, enabled = !busy && remainingTurns > 0) {
                            Text(if (busy) "考えています" else "送る")
                        }
                    }
                }
            }
        }
    }
}

/**
 * 段 1 つ。**掴む場所は左の印だけ**（本文を長押ししたときに動き出さないように）。
 *
 * 本文の字数はここに出す。**黙って切らない**ので、超えたら赤くして書き出しを止める。
 */
@Composable
internal fun StepCard(
    index: Int,
    count: Int,
    step: GuideStep,
    slotNote: String?,
    handle: Modifier,
    onIntro: (String) -> Unit,
    onBody: (String) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onToggle: () -> Unit,
    onRemove: () -> Unit,
) {
    val tooLong = step.body.length > GuideCodec.MAX_BODY_CHARS
    Card(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (step.enabled) SaberaSurface else SaberaSurfaceVariant,
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 掴む印。**ここだけがドラッグの入口**
                Text("⋮⋮", modifier = handle.padding(end = 8.dp), color = SaberaFinePrint)
                Column(Modifier.weight(1f)) {
                    Text("${index + 1}. ${step.targetName}", style = MaterialTheme.typography.bodyMedium)
                    slotNote?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = SaberaFinePrint)
                    }
                }
                // 配るときに外す。**段は消さない**ので翌週そのまま戻せる
                Switch(checked = step.enabled, onCheckedChange = { onToggle() })
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = step.intro,
                onValueChange = onIntro,
                label = { Text("向く前の一言（方角は端末が言います）") },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = step.body,
                onValueChange = onBody,
                label = { Text("解説") },
                isError = tooLong,
                supportingText = {
                    Text(
                        "${step.body.length} / ${GuideCodec.MAX_BODY_CHARS} 字" +
                            if (tooLong) "　グラスに入りません" else "",
                        color = if (tooLong) MaterialTheme.colorScheme.error else SaberaFinePrint,
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                // **ドラッグが実機で滑ったときの逃げ道。** 押せば必ず 1 つ動く
                TextButton(onClick = onMoveUp, enabled = index > 0) { Text("▲") }
                TextButton(onClick = onMoveDown, enabled = index < count - 1) { Text("▼") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onRemove) { Text("消す", color = SaberaWarning) }
            }
        }
    }
}

/** QR にあとどれだけ入るか。**あふれる前に気づけないと、書いたあとで消すことになる** */
@Composable
internal fun QrBudgetBar(usedRatio: Float, remainingBytes: Int) {
    val over = remainingBytes < 0
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (over) "QR に入りません（${-remainingBytes} バイト超過）" else "QR の残り $remainingBytes バイト",
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = if (over) MaterialTheme.colorScheme.error else SaberaFinePrint,
            )
        }
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { usedRatio },
            modifier = Modifier.fillMaxWidth().height(6.dp),
            color = if (over) MaterialTheme.colorScheme.error else SaberaGreen,
        )
    }
}

/** 上級者向けに畳んでおく見出し。**ふだんは目に入らないが、探せば見つかる** */
@Composable
internal fun AdvancedSection(
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        TextButton(onClick = onToggle, modifier = Modifier.fillMaxWidth()) {
            Text(
                (if (expanded) "▼ " else "▶ ") + "詳しく作る（旅行会社・ツアー向け）",
                style = MaterialTheme.typography.bodySmall,
                color = SaberaFinePrint,
            )
        }
        if (expanded) {
            Spacer(Modifier.size(4.dp))
            content()
        }
    }
}
