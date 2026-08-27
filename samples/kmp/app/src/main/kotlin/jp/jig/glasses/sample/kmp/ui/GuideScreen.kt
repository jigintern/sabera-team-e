package jp.jig.glasses.sample.kmp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.unit.dp
import jp.jig.glasses.sample.kmp.BuildConfig
import jp.jig.glasses.sample.kmp.alignment.Locator
import jp.jig.glasses.sample.kmp.guide.GuideMaker
import jp.jig.glasses.sample.kmp.guide.GuideStore
import jp.jig.glasses.sample.kmp.guide.GuideTheme
import jp.jig.glasses.sample.kmp.guide.StarGuide
import jp.jig.glasses.sample.kmp.openai.OpenAiGuide
import jp.jig.glasses.sample.kmp.sky.GuidanceTarget
import jp.jig.glasses.sample.kmp.sky.ObservationDefaults
import jp.jig.glasses.sample.kmp.sky.SkyDensity
import jp.jig.glasses.sample.kmp.glass.BundledData
import jp.jig.glasses.sample.kmp.ui.component.AdvancedSection
import jp.jig.glasses.sample.kmp.ui.component.ConstellationBackground
import jp.jig.glasses.sample.kmp.ui.component.LoadingPanel
import jp.jig.glasses.sample.kmp.ui.component.SaberaDarkColorScheme
import jp.jig.glasses.sample.kmp.ui.component.SaberaGreen
import jp.jig.glasses.sample.kmp.ui.component.SaberaOnAccent
import jp.jig.glasses.sample.kmp.ui.component.SaberaSurface
import jp.jig.glasses.sample.kmp.ui.component.SaberaTypography
import jp.jig.glasses.sample.kmp.ui.component.SaberaWarning
import jp.jig.glasses.sample.kmp.ui.component.SeasonalConstellationBackground
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 星座ガイドの台本を作る画面。**グラスは要らない。**
 *
 * **作るのは電波のあるうち、使うのは空の下。** ここをホームの下に置いてあるのは、
 * 出かける前（家や宿）で作っておけるようにするため。作った台本は端末に残るので、
 * 現地が圏外でも観測画面の設定パネルから始められる。
 *
 * その場で作ることもできる（即興ガイド・toC）。**電波があれば AI が文を書き、
 * 無ければ同梱の 88 星座で組む**（`GuideMaker`）。どちらで作ったかは一覧に出す。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuideScreen(
    constellation: ConstellationBackground,
    onBack: () -> Unit,
    /** 詳細エディタへ。null なら新規、台本を渡せばその続きから */
    onAuthor: (StarGuide?) -> Unit,
    onShare: (StarGuide) -> Unit,
    onImport: () -> Unit,
) {
    val context = LocalContext.current
    val store = remember(context) { GuideStore.of(context) }

    val appContext = context.applicationContext
    val vm = viewModel {
        GuideListViewModel(store, makeDraft = { theme ->
            val now = System.currentTimeMillis()
            val renderer = BundledData.renderer(appContext)
            val lore = BundledData.lore(appContext)
            // **観測地は分かるものを使う。** 測位が無ければ既定の鯖江で組む。
            // 数十 km ずれても、どの星座が空に出ているかはほとんど変わらない
            val located = runCatching { Locator(appContext).lastKnown() }.getOrNull()
            val site = located?.site ?: ObservationDefaults.site
            val targets: List<GuidanceTarget> = withContext(Dispatchers.Default) {
                renderer.guidanceTargets(site, now, SkyDensity.STANDARD)
            }
            val maker = GuideMaker(
                lore = lore::of,
                brightestMagnitude = renderer::brightestMagnitude,
                writer = if (BuildConfig.OPENAI_API_KEY.isEmpty()) {
                    null
                } else {
                    { guideTheme, picked, at, id ->
                        withContext(Dispatchers.IO) {
                            OpenAiGuide(
                                apiKey = BuildConfig.OPENAI_API_KEY,
                                model = BuildConfig.OPENAI_ANSWER_MODEL,
                            ).write(guideTheme, picked, at, id)
                        }
                    }
                },
            )
            maker.make(theme, targets, now, GuideStore.newId(now))
        })
    }
    LaunchedEffect(Unit) { vm.reload() }
    // 画面を出たら作りかけは打ち切り、表示も真っさら（remember のころと同じ見え方）
    DisposableEffect(Unit) { onDispose { vm.leave() } }

    MaterialTheme(colorScheme = SaberaDarkColorScheme, typography = SaberaTypography) {
        Box(Modifier.fillMaxSize()) {
            SeasonalConstellationBackground(constellation, Modifier.fillMaxSize())
            Scaffold(
                containerColor = Color.Transparent,
                // **透ける下地には文字色が付いてこない。** Scaffold は containerColor から
                // 文字色を引くので、Transparent だと既定の黒のまま——カードの外に置いた
                // 見出しが夜空に溶けて読めなくなる
                contentColor = MaterialTheme.colorScheme.onSurface,
                topBar = {
                    TopAppBar(
                        title = { Text("星座ガイド") },
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
                    Modifier.fillMaxSize().padding(padding)
                        .padding(horizontal = 16.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    // **バーのすぐ下から始める。** 上に余白を積むと、
                    // 何をする画面なのかが 1 画面目から押し出される
                    // **手順をそのまま見出しにする。** 説明文で書くより、
                    // いまどこにいるのか（選ぶ／作る）が形で分かる
                    SectionTitle("テーマを選ぶ")
                    Card(
                        Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = SaberaSurface),
                    ) {
                        Column(Modifier.fillMaxWidth().padding(12.dp)) {
                            if (vm.making) {
                                LoadingPanel(
                                    text = "台本を作っています",
                                    hint = "空に出ている星座を調べています",
                                    modifier = Modifier.fillMaxWidth().height(96.dp),
                                )
                            } else {
                                for (theme in GuideTheme.entries) {
                                    val chosen = theme == vm.theme
                                    Row(
                                        Modifier.fillMaxWidth()
                                            .clickable { vm.theme = theme }
                                            .padding(vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text(theme.label, style = MaterialTheme.typography.bodyMedium)
                                            Text(
                                                theme.hint,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        // **選んだものだけ印を付ける。** 押した先で何が起きるかは
                                        // 下の「作成する」が持っているので、行に矢印は要らない
                                        if (chosen) Icon(Icons.Filled.Check, "選んでいます", tint = SaberaGreen)
                                    }
                                }
                            }
                        }
                    }

                    SectionTitle("作成する")
                    Button(
                        onClick = { vm.make(vm.theme) },
                        enabled = !vm.making,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SaberaGreen,
                            contentColor = SaberaOnAccent,
                        ),
                    ) {
                        Text("「${vm.theme.label}」で作る")
                    }

                    vm.notice?.let {
                        Spacer(Modifier.height(12.dp))
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = SaberaGreen)
                    }

                    Spacer(Modifier.height(20.dp))
                    SectionTitle("作った台本", "押すと中身を読めます")
                    if (vm.guides.isEmpty()) {
                        Text(
                            "まだありません",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    for (guide in vm.guides) {
                        GuideCard(
                            guide = guide,
                            onEdit = { onAuthor(guide) },
                            onShare = { onShare(guide) },
                            onDelete = { vm.delete(guide) },
                        )
                        Spacer(Modifier.height(8.dp))
                    }

                    Spacer(Modifier.height(20.dp))
                    OutlinedButton(onClick = onImport, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.QrCode2, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("受け取る")
                    }

                    Spacer(Modifier.height(8.dp))
                    AdvancedSection(expanded = vm.advanced, onToggle = { vm.advanced = !vm.advanced }) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(
                                "テーマ任せではなく、星座も順番も文面も自分で決めます。" +
                                    "想定した日時と場所で組めるので、先の日付のツアーも作れます" +
                                    "（旅行会社のツアー向け）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = { onAuthor(null) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("1 から作りはじめる")
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    Text(
                        "電波の届かない場所でも使えます。" +
                            "始めるのは観測画面の「ガイドを始める」から",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

/** 区画の見出し。**何をする場所かを 1 行で言い切る**（作るのか、選ぶのか） */
@Composable
private fun SectionTitle(title: String, hint: String? = null) {
    Spacer(Modifier.height(12.dp))
    Text(title, style = MaterialTheme.typography.titleMedium)
    if (hint != null) {
        Text(
            hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer(Modifier.height(6.dp))
}

/** 台本 1 本。**中身を読めるようにする**（何を喋るのか分からないまま外へ持ち出させない） */
@Composable
private fun GuideCard(
    guide: StarGuide,
    onEdit: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SaberaSurface),
    ) {
        // **中身はカードを押して開く。** 「中身を読む」ボタンを 1 つ減らせる
        Column(
            Modifier.fillMaxWidth()
                .clickable {
                    expanded = !expanded
                    confirmDelete = false
                }
                .padding(12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    guide.title,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    if (expanded) "閉じる" else "中身を読む",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                // **どう作ったか（AI か同梱か）は出さない。** 読む人が決めるのは
                // 「この台本を使うか」だけで、作り方を知っても選び方は変わらない
                "${guide.size} 星座" + if (guide.locked) "・編集できません" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            guide.derivedFrom?.let {
                Text(
                    "「${it.title}」を元にしています",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(guide.summary, style = MaterialTheme.typography.bodySmall)
            if (expanded) {
                for ((index, step) in guide.steps.withIndex()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "${index + 1}. ${step.targetName}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        step.body,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // **操作は 1 行のアイコンだけ。** 台本が増えるほどボタンの壁になっていた
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // **配った台本は守る。** 客がうっかり直すと、その 1 台だけ違うツアーになる
                if (!guide.locked) {
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Filled.Edit, "直す", tint = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = onShare) {
                        Icon(Icons.Filled.QrCode2, "配る", tint = SaberaGreen)
                    }
                }
                // **消すのは 2 回押させる。** 取り消せないのに、アイコンは指が滑って当たる
                if (confirmDelete) {
                    TextButton(onClick = onDelete) {
                        Text("本当に消す", color = SaberaWarning)
                    }
                } else {
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Filled.Delete, "消す", tint = SaberaWarning)
                    }
                }
            }
        }
    }
}
