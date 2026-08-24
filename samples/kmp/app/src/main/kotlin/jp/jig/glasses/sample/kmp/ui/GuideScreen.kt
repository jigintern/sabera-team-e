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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
import jp.jig.glasses.sample.kmp.support.BundledData
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
import kotlinx.coroutines.launch
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
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember(context) { GuideStore.of(context) }

    var guides by remember { mutableStateOf<List<StarGuide>>(emptyList()) }
    var making by remember { mutableStateOf(false) }
    /** 直前に何が起きたか。**作ったのに何も言わないと、できたのか分からない** */
    var notice by remember { mutableStateOf<String?>(null) }

    fun reload() {
        scope.launch { guides = withContext(Dispatchers.IO) { store.list() } }
    }
    LaunchedEffect(store) { reload() }

    fun make(theme: GuideTheme) {
        if (making) return
        making = true
        notice = null
        scope.launch {
            try {
                val now = System.currentTimeMillis()
                val renderer = BundledData.renderer(context)
                val lore = BundledData.lore(context)
                // **観測地は分かるものを使う。** 測位が無ければ既定の鯖江で組む。
                // 数十 km ずれても、どの星座が空に出ているかはほとんど変わらない
                val located = runCatching { Locator(context).lastKnown() }.getOrNull()
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
                val draft = maker.make(theme, targets, now, GuideStore.newId(now))
                if (draft == null) {
                    notice = "いまの空には案内できる星座がありません。日が暮れてから試してください"
                    return@launch
                }
                val saved = withContext(Dispatchers.IO) { store.save(draft.guide) }
                notice = when {
                    !saved -> "台本を保存できませんでした"
                    draft.fellBackReason != null ->
                        "「${draft.guide.title}」を作りました（${draft.fellBackReason}。同梱の解説で組みました）"
                    else -> "「${draft.guide.title}」を作りました（AI が書きました）"
                }
                reload()
            } catch (error: Throwable) {
                // **落ちるより断って続ける**（05_app-flow.md）。星表が読めないこともある
                notice = "台本を作れませんでした: ${error.message}"
            } finally {
                making = false
            }
        }
    }

    MaterialTheme(colorScheme = SaberaDarkColorScheme, typography = SaberaTypography) {
        Box(Modifier.fillMaxSize()) {
            SeasonalConstellationBackground(constellation, Modifier.fillMaxSize())
            Scaffold(
                containerColor = Color.Transparent,
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
                    Modifier.fillMaxSize().padding(padding).padding(16.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        "決めた順に星座へ案内して、そのつど解説します。" +
                            "作った台本は端末に残るので、電波の届かない場所でも使えます",
                        style = MaterialTheme.typography.bodyMedium,
                    )

                    Spacer(Modifier.height(16.dp))
                    Text("いまの空から作る", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Card(
                        Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = SaberaSurface),
                    ) {
                        Column(Modifier.fillMaxWidth().padding(12.dp)) {
                            if (making) {
                                LoadingPanel(
                                    text = "台本を作っています",
                                    hint = "空に出ている星座を調べています",
                                    modifier = Modifier.fillMaxWidth().height(96.dp),
                                )
                            } else {
                                for (theme in GuideTheme.entries) {
                                    Row(
                                        Modifier.fillMaxWidth(),
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
                                        Button(
                                            onClick = { make(theme) },
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = SaberaGreen,
                                                contentColor = SaberaOnAccent,
                                            ),
                                        ) {
                                            Text("作る")
                                        }
                                    }
                                    Spacer(Modifier.height(8.dp))
                                }
                                // **キーが無くても作れることを書く。** 使えないと思わせない
                                Text(
                                    if (BuildConfig.OPENAI_API_KEY.isEmpty()) {
                                        "AI の設定がないので、同梱の解説文で組みます"
                                    } else {
                                        "電波があれば AI が文を書き、届かなければ同梱の解説文で組みます"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    notice?.let {
                        Spacer(Modifier.height(12.dp))
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = SaberaGreen)
                    }

                    Spacer(Modifier.height(20.dp))
                    Text("作った台本", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    if (guides.isEmpty()) {
                        Text(
                            "まだありません",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    for (guide in guides) {
                        GuideCard(
                            guide = guide,
                            onDelete = {
                                store.delete(guide.id)
                                notice = "「${guide.title}」を消しました"
                                reload()
                            },
                        )
                        Spacer(Modifier.height(8.dp))
                    }

                    Spacer(Modifier.height(16.dp))
                    Text(
                        "始めるのは観測画面の「設定」から。グラスをつないでから選びます",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** 台本 1 本。**中身を読めるようにする**（何を喋るのか分からないまま外へ持ち出させない） */
@Composable
private fun GuideCard(guide: StarGuide, onDelete: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SaberaSurface),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(guide.title, style = MaterialTheme.typography.titleSmall)
            Text(
                "${guide.size} 星座・${guide.origin.label}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (expanded) "閉じる" else "中身を読む")
                }
                TextButton(onClick = onDelete, modifier = Modifier.weight(1f)) {
                    Text("消す", color = SaberaWarning)
                }
            }
        }
    }
}
