package jp.jig.glasses.sample.kmp.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.unit.dp
import jp.jig.glasses.sample.kmp.BuildConfig
import jp.jig.glasses.sample.kmp.alignment.Locator
import jp.jig.glasses.sample.kmp.guide.AuthoredGuide
import jp.jig.glasses.sample.kmp.guide.GuideAsk
import jp.jig.glasses.sample.kmp.guide.GuideCodec
import jp.jig.glasses.sample.kmp.guide.GuideSchedule
import jp.jig.glasses.sample.kmp.guide.GuideStep
import jp.jig.glasses.sample.kmp.guide.GuideStore
import jp.jig.glasses.sample.kmp.guide.GuideWindow
import jp.jig.glasses.sample.kmp.guide.StarGuide
import jp.jig.glasses.sample.kmp.openai.GuideChatTurn
import jp.jig.glasses.sample.kmp.openai.OpenAiGuideChat
import jp.jig.glasses.sample.kmp.sky.GuidanceTarget
import jp.jig.glasses.sample.kmp.sky.ObservationDefaults
import jp.jig.glasses.sample.kmp.sky.Site
import jp.jig.glasses.sample.kmp.sky.SkyDensity
import jp.jig.glasses.sample.kmp.glass.BundledData
import jp.jig.glasses.sample.kmp.support.Connectivity
import jp.jig.glasses.sample.kmp.ui.component.CandidateCard
import jp.jig.glasses.sample.kmp.ui.component.ConstellationBackground
import jp.jig.glasses.sample.kmp.ui.component.GuideChatCard
import jp.jig.glasses.sample.kmp.ui.component.PlanningCard
import jp.jig.glasses.sample.kmp.ui.component.QrBudgetBar
import jp.jig.glasses.sample.kmp.ui.component.ReorderableColumn
import jp.jig.glasses.sample.kmp.ui.component.SaberaDarkColorScheme
import jp.jig.glasses.sample.kmp.ui.component.SaberaFinePrint
import jp.jig.glasses.sample.kmp.ui.component.SaberaGreen
import jp.jig.glasses.sample.kmp.ui.component.SaberaOnAccent
import jp.jig.glasses.sample.kmp.ui.component.SaberaTypography
import jp.jig.glasses.sample.kmp.ui.component.SeasonalConstellationBackground
import jp.jig.glasses.sample.kmp.ui.component.StepCard
import jp.jig.glasses.sample.kmp.ui.component.formatTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * 詳細ガイドの編集画面（toB）。**旅行会社が自分のツアー用に台本を作り込む。**
 *
 * 即興ガイド（toC・`GuideScreen`）との違いは、**人が全部握れる**こと。
 * 星座を選び、順番を決め、文面を書く。AI は**候補の中から選んで文を書くだけ**で、
 * 星座を決めるのは端末のまま（16_guide.md の 4 条件の③）。
 *
 * **想定した日時と場所は、作るときの検算にしか使わない。**
 * 再生はいまの空で引き直すので、書き置いた方角が別の夜に嘘になることは起きない。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthoredGuideScreen(
    constellation: ConstellationBackground,
    initial: StarGuide?,
    onBack: () -> Unit,
    onShare: (StarGuide) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember(context) { GuideStore.of(context) }
    val keyConfigured = BuildConfig.OPENAI_API_KEY.isNotEmpty()

    val appContext = context.applicationContext
    val vm = viewModel {
        val now = System.currentTimeMillis()
        val located = runCatching { Locator(appContext).lastKnown() }.getOrNull()
        val site = located?.site ?: ObservationDefaults.site
        val window = GuideWindow(tonightAt(now), GuideSchedule.DEFAULT_MINUTES)
        val initialDraft = initial?.let { AuthoredGuide.edit(it, window, site) }
            ?: AuthoredGuide.empty(GuideStore.newId(now), now, window, site)
        AuthoredGuideViewModel(
            initialDraft = initialDraft,
            store = store,
            targetsAtFactory = {
                val renderer = BundledData.renderer(appContext)
                val at = { millis: Long -> renderer.guidanceTargets(site, millis, SkyDensity.STANDARD) }
                at
            },
            loreOf = { name -> BundledData.lore(appContext).of(name).orEmpty() },
            askAi = { instruction, targets, steps, turns ->
                OpenAiGuideChat(
                    apiKey = BuildConfig.OPENAI_API_KEY,
                    model = BuildConfig.OPENAI_ANSWER_MODEL,
                ).reply(instruction, targets, steps, turns)
                    ?.let { GuideChatReply(it.reply, it.steps, it.dropped) }
            },
        )
    }
    val draft = vm.draft

    LaunchedEffect(Unit) {
        vm.online = Connectivity.online(appContext)
        vm.reloadCandidates()
    }

    fun pickDateTime() {
        val calendar = Calendar.getInstance().apply { timeInMillis = draft.window.startMillis }
        DatePickerDialog(
            context,
            { _, year, month, day ->
                TimePickerDialog(
                    context,
                    { _, hour, minute ->
                        val picked = Calendar.getInstance().apply {
                            set(year, month, day, hour, minute, 0)
                            set(Calendar.MILLISECOND, 0)
                        }
                        vm.onWindowStartPicked(picked.timeInMillis)
                    },
                    calendar.get(Calendar.HOUR_OF_DAY),
                    calendar.get(Calendar.MINUTE),
                    true,
                ).show()
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH),
        ).show()
    }

    val checks = remember(draft.steps.size, draft.window) {
        draft.steps.indices.map { GuideSchedule.slotMillis(draft.window, it, draft.steps.size) }
    }
    // **1 文字打つたびに圧縮しない。** 段が変わったときだけ測り直す
    val packedSize = remember(draft.steps) { GuideCodec.pack(draft.toGuide()).size }
    val shareable = draft.steps.any { it.enabled } &&
        draft.tooLongSteps.isEmpty() &&
        packedSize <= GuideCodec.QR_CAPACITY_BYTES

    MaterialTheme(colorScheme = SaberaDarkColorScheme, typography = SaberaTypography) {
        Box(Modifier.fillMaxSize()) {
            SeasonalConstellationBackground(constellation, Modifier.fillMaxSize())
            Scaffold(
                containerColor = Color.Transparent,
                topBar = {
                    TopAppBar(
                        title = { Text("詳しく作る") },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = Color(0xA608111B),
                            titleContentColor = Color.White,
                        ),
                        navigationIcon = {
                            TextButton(onClick = onBack) { Text("戻る", color = Color.White) }
                        },
                    )
                },
            ) { padding ->
                Column(
                    Modifier.fillMaxSize().padding(padding).padding(16.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    OutlinedTextField(
                        value = draft.title,
                        onValueChange = { vm.draft = draft.copy(title = it.take(GuideCodec.MAX_TITLE_CHARS)) },
                        label = { Text("ツアーの名前") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = draft.summary,
                        onValueChange = { vm.draft = draft.copy(summary = it.take(GuideCodec.MAX_SUMMARY_CHARS)) },
                        label = { Text("一行の説明") },
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Spacer(Modifier.height(12.dp))
                    PlanningCard(
                        startMillis = draft.window.startMillis,
                        minutes = draft.window.minutes,
                        latDeg = draft.site.latDeg,
                        lonDeg = draft.site.lonDeg,
                        siteNote = "ツアーをする場所の緯度経度。事務所で書くなら打ち替えてください",
                        onPickDateTime = { pickDateTime() },
                        onMinutes = {
                            vm.draft = draft.copy(window = draft.window.copy(minutes = it))
                            vm.reloadCandidates()
                        },
                        onLatLon = { lat, lon ->
                            vm.draft = draft.copy(
                                site = Site(
                                    latDeg = lat.toDoubleOrNull() ?: draft.site.latDeg,
                                    lonDeg = lon.toDoubleOrNull() ?: draft.site.lonDeg,
                                ),
                            )
                        },
                    )
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = { vm.reloadCandidates() }) {
                        Text("この場所と時刻で調べ直す", color = SaberaGreen)
                    }

                    Spacer(Modifier.height(12.dp))
                    CandidateCard(
                        candidates = vm.candidates,
                        chosen = { draft.contains(it) },
                        loading = vm.loadingCandidates,
                        onToggle = { vm.toggleTarget(it) },
                        onRefresh = { vm.reloadCandidates() },
                    )

                    Spacer(Modifier.height(12.dp))
                    GuideChatCard(
                        online = vm.online,
                        keyConfigured = keyConfigured,
                        turns = vm.chat.map { it.fromUser to it.text },
                        input = vm.chatInput,
                        busy = vm.chatBusy,
                        remainingTurns = (GuideAsk.MAX_TURNS - vm.chat.count { it.fromUser }).coerceAtLeast(0),
                        onInput = { vm.chatInput = it },
                        onSend = { vm.send(::formatTime) },
                    )

                    Spacer(Modifier.height(16.dp))
                    Text("回る順番", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "長押しして掴むと動かせます。▲▼ でも動きます",
                        style = MaterialTheme.typography.bodySmall,
                        color = SaberaFinePrint,
                    )
                    Spacer(Modifier.height(4.dp))
                    if (draft.steps.isEmpty()) {
                        Text(
                            "まだ 1 段もありません。上の一覧から入れてください",
                            style = MaterialTheme.typography.bodySmall,
                            color = SaberaFinePrint,
                        )
                    }
                    ReorderableColumn(
                        items = draft.steps,
                        onMove = { from, to -> vm.draft = draft.moved(from, to) },
                    ) { index, step, handle ->
                        StepCard(
                            index = index,
                            count = draft.steps.size,
                            step = step,
                            slotNote = checks.getOrNull(index)?.let { "${formatTime(it)} ごろ" },
                            handle = handle,
                            onIntro = { vm.draft = draft.replacedAt(index, step.copy(intro = it)) },
                            onBody = { vm.draft = draft.replacedAt(index, step.copy(body = it)) },
                            onMoveUp = { vm.draft = draft.moved(index, index - 1) },
                            onMoveDown = { vm.draft = draft.moved(index, index + 1) },
                            onToggle = { vm.draft = draft.toggledAt(index) },
                            onRemove = { vm.draft = draft.removedAt(index) },
                        )
                    }

                    Spacer(Modifier.height(16.dp))
                    QrBudgetBar(
                        usedRatio = (packedSize.toFloat() / GuideCodec.QR_CAPACITY_BYTES).coerceIn(0f, 1f),
                        remainingBytes = GuideCodec.QR_CAPACITY_BYTES - packedSize,
                    )

                    vm.notice?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = SaberaGreen)
                    }

                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { vm.save() }, modifier = Modifier.weight(1f)) {
                            Text("保存する")
                        }
                        Button(
                            onClick = { vm.save { onShare(it) } },
                            enabled = shareable,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = SaberaGreen,
                                contentColor = SaberaOnAccent,
                            ),
                        ) {
                            Text("配る")
                        }
                    }
                    if (!shareable && draft.steps.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            when {
                                draft.tooLongSteps.isNotEmpty() ->
                                    "長すぎる解説があります（${draft.tooLongSteps.map { it + 1 }.joinToString("、")} 段目）"
                                else -> "QR に入りません。段を外すか、解説を短くしてください"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

/**
 * 台本を作るときの既定の開始時刻。**今夜の 20 時。**
 *
 * いまが昼なら今日の 20 時、20 時を回っていれば 1 時間後。
 * 星の見えない時刻を既定にすると、開いた瞬間に候補が空になる。
 */
private fun tonightAt(nowMillis: Long): Long {
    val calendar = Calendar.getInstance().apply { timeInMillis = nowMillis }
    if (calendar.get(Calendar.HOUR_OF_DAY) >= DEFAULT_HOUR) {
        return nowMillis + GuideWindow.MINUTE_MS * 60
    }
    calendar.set(Calendar.HOUR_OF_DAY, DEFAULT_HOUR)
    calendar.set(Calendar.MINUTE, 0)
    calendar.set(Calendar.SECOND, 0)
    calendar.set(Calendar.MILLISECOND, 0)
    return calendar.timeInMillis
}

private const val DEFAULT_HOUR = 20
