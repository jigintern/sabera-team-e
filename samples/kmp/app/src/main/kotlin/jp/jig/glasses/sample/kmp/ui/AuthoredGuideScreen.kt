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
import jp.jig.glasses.sample.kmp.support.BundledData
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

    var draft by remember {
        val now = System.currentTimeMillis()
        val located = runCatching { Locator(context).lastKnown() }.getOrNull()
        val site = located?.site ?: ObservationDefaults.site
        val window = GuideWindow(tonightAt(now), GuideSchedule.DEFAULT_MINUTES)
        mutableStateOf(
            initial?.let { AuthoredGuide.edit(it, window, site) }
                ?: AuthoredGuide.empty(GuideStore.newId(now), now, window, site),
        )
    }
    var candidates by remember { mutableStateOf<List<GuidanceTarget>>(emptyList()) }
    var loadingCandidates by remember { mutableStateOf(false) }
    var chat by remember { mutableStateOf<List<GuideChatTurn>>(emptyList()) }
    var chatInput by remember { mutableStateOf("") }
    var chatBusy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var online by remember { mutableStateOf(false) }

    /** その日その時間の空。**候補も判定もここから出す**（1 か所で計算する） */
    suspend fun targetsAtFactory(): (Long) -> List<GuidanceTarget> {
        val renderer = BundledData.renderer(context)
        val site = draft.site
        return { at -> renderer.guidanceTargets(site, at, SkyDensity.STANDARD) }
    }

    fun reloadCandidates() {
        loadingCandidates = true
        scope.launch {
            candidates = runCatching {
                val factory = targetsAtFactory()
                withContext(Dispatchers.Default) {
                    GuideSchedule.candidates(draft.window, factory)
                        .sortedByDescending { it.aim.altDeg }
                }
            }.getOrElse {
                notice = "その日の空を調べられませんでした: ${it.message}"
                emptyList()
            }
            loadingCandidates = false
        }
    }

    LaunchedEffect(Unit) {
        online = Connectivity.online(context)
        reloadCandidates()
    }

    /** 段を足す。**同梱の解説を初期値にする**（白紙から書かせない） */
    fun addTarget(target: GuidanceTarget) {
        scope.launch {
            val body = runCatching { BundledData.lore(context).of(target.nameJa) }.getOrNull().orEmpty()
            draft = draft.plus(
                GuideStep(
                    targetName = target.nameJa,
                    kind = target.kind,
                    intro = "",
                    body = body,
                ),
            )
        }
    }

    fun toggleTarget(target: GuidanceTarget) {
        val at = draft.steps.indexOfFirst { it.targetName == target.nameJa }
        if (at >= 0) draft = draft.removedAt(at) else addTarget(target)
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
                        draft = draft.copy(window = draft.window.copy(startMillis = picked.timeInMillis))
                        reloadCandidates()
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

    /**
     * AI へ渡す前に、端末が先に断る。
     *
     * **打たれた文に候補外の名前があれば、通信せずに返す。** 渡してしまうと AI が
     * 気を利かせて台本に載せ、再生で全部飛んで「何も起きないガイド」になる。
     */
    fun send() {
        val instruction = GuideAsk.sanitizeInput(chatInput) ?: return
        if (chatBusy) return
        chatBusy = true
        chatInput = ""
        scope.launch {
            try {
                val factory = targetsAtFactory()
                val known = withContext(Dispatchers.Default) {
                    factory(draft.window.startMillis).map { it.nameJa }
                }
                val allowed = candidates.map { it.nameJa }.toSet()
                val asked = GuideAsk.namesIn(instruction, known)
                val blocked = asked.filterNot { it in allowed }
                if (blocked.isNotEmpty()) {
                    val name = blocked.first()
                    val from = withContext(Dispatchers.Default) {
                        GuideSchedule.availableFrom(name, null, draft.window, factory)
                    }
                    chat = chat + GuideChatTurn(true, instruction) +
                        GuideChatTurn(false, GuideAsk.rejection(name, from?.let { formatTime(it) }))
                    return@launch
                }
                val reply = withContext(Dispatchers.IO) {
                    OpenAiGuideChat(
                        apiKey = BuildConfig.OPENAI_API_KEY,
                        model = BuildConfig.OPENAI_ANSWER_MODEL,
                    ).reply(instruction, candidates, draft.steps, chat)
                }
                if (reply == null) {
                    chat = chat + GuideChatTurn(true, instruction) +
                        GuideChatTurn(false, "うまく答えられませんでした。もう一度お願いします。")
                    return@launch
                }
                reply.steps?.let { draft = draft.copy(steps = it) }
                val dropped = if (reply.dropped > 0) "（${reply.dropped} 件は空に無いので外しました）" else ""
                chat = chat + GuideChatTurn(true, instruction) +
                    GuideChatTurn(false, reply.reply + dropped)
            } catch (error: Throwable) {
                // **断って続ける。** 通信は落ちるものなので、落ちても台本は残る
                chat = chat + GuideChatTurn(true, instruction) +
                    GuideChatTurn(false, "通信できませんでした: ${error.message}")
            } finally {
                chatBusy = false
            }
        }
    }

    fun save(then: ((StarGuide) -> Unit)? = null) {
        val guide = draft.toGuide()
        scope.launch {
            val saved = withContext(Dispatchers.IO) { store.save(guide) }
            if (!saved) {
                notice = "台本を保存できませんでした"
                return@launch
            }
            notice = "「${guide.title}」を保存しました"
            then?.invoke(guide)
        }
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
                        onValueChange = { draft = draft.copy(title = it.take(GuideCodec.MAX_TITLE_CHARS)) },
                        label = { Text("ツアーの名前") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = draft.summary,
                        onValueChange = { draft = draft.copy(summary = it.take(GuideCodec.MAX_SUMMARY_CHARS)) },
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
                            draft = draft.copy(window = draft.window.copy(minutes = it))
                            reloadCandidates()
                        },
                        onLatLon = { lat, lon ->
                            draft = draft.copy(
                                site = Site(
                                    latDeg = lat.toDoubleOrNull() ?: draft.site.latDeg,
                                    lonDeg = lon.toDoubleOrNull() ?: draft.site.lonDeg,
                                ),
                            )
                        },
                    )
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = { reloadCandidates() }) {
                        Text("この場所と時刻で調べ直す", color = SaberaGreen)
                    }

                    Spacer(Modifier.height(12.dp))
                    CandidateCard(
                        candidates = candidates,
                        chosen = { draft.contains(it) },
                        loading = loadingCandidates,
                        onToggle = { toggleTarget(it) },
                        onRefresh = { reloadCandidates() },
                    )

                    Spacer(Modifier.height(12.dp))
                    GuideChatCard(
                        online = online,
                        keyConfigured = keyConfigured,
                        turns = chat.map { it.fromUser to it.text },
                        input = chatInput,
                        busy = chatBusy,
                        remainingTurns = (GuideAsk.MAX_TURNS - chat.count { it.fromUser }).coerceAtLeast(0),
                        onInput = { chatInput = it },
                        onSend = { send() },
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
                        onMove = { from, to -> draft = draft.moved(from, to) },
                    ) { index, step, handle ->
                        StepCard(
                            index = index,
                            count = draft.steps.size,
                            step = step,
                            slotNote = checks.getOrNull(index)?.let { "${formatTime(it)} ごろ" },
                            handle = handle,
                            onIntro = { draft = draft.replacedAt(index, step.copy(intro = it)) },
                            onBody = { draft = draft.replacedAt(index, step.copy(body = it)) },
                            onMoveUp = { draft = draft.moved(index, index - 1) },
                            onMoveDown = { draft = draft.moved(index, index + 1) },
                            onToggle = { draft = draft.toggledAt(index) },
                            onRemove = { draft = draft.removedAt(index) },
                        )
                    }

                    Spacer(Modifier.height(16.dp))
                    QrBudgetBar(
                        usedRatio = (packedSize.toFloat() / GuideCodec.QR_CAPACITY_BYTES).coerceIn(0f, 1f),
                        remainingBytes = GuideCodec.QR_CAPACITY_BYTES - packedSize,
                    )

                    notice?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = SaberaGreen)
                    }

                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { save() }, modifier = Modifier.weight(1f)) {
                            Text("保存する")
                        }
                        Button(
                            onClick = { save { onShare(it) } },
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
