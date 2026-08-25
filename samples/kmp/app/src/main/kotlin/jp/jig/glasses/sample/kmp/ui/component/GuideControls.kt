package jp.jig.glasses.sample.kmp.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import jp.jig.glasses.sample.kmp.guide.GuideProgress
import jp.jig.glasses.sample.kmp.guide.StarGuide

/**
 * 台本を選んで始める（観測画面の畳んだ側から開く）。
 *
 * **始める口は 1 か所だけ。** 設定パネルの中に置いていたときは、上のバーの「設定」を押し、
 * 区画まで送って「始める」を押す 3 手だった。空の下で押すものは、**開いた画面のまま押せる**
 * ところに置く。
 *
 * ボタンではなくダイアログで選ばせるのは、**台本が増えてもメイン画面が太らない**ため
 * （押すものは常に「ガイドを始める」の 1 つ）。
 *
 * 台本を作るのはホームから（グラスが要らず、電波のあるうちに作れる）。
 */
@Composable
internal fun GuidePickerDialog(
    guides: List<StarGuide>,
    onStart: (StarGuide) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth().widthIn(max = 360.dp),
            colors = CardDefaults.cardColors(containerColor = SaberaSurface),
        ) {
            Column(Modifier.fillMaxWidth().padding(20.dp)) {
                Text("ガイドを選ぶ", style = MaterialTheme.typography.titleLarge, color = Color.White)
                Spacer(Modifier.height(4.dp))
                Text(
                    "決めた順に星座へ案内して、そのつど解説します",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                // 台本が増えても画面から溢れないよう、一覧だけを高さで区切って送る
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                ) {
                    for (guide in guides) {
                        Column(
                            Modifier.fillMaxWidth()
                                .clickable { onStart(guide) }
                                .padding(vertical = 10.dp),
                        ) {
                            Text(guide.title, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "${guide.size} 星座・${guide.origin.label}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text("やめる")
                }
            }
        }
    }
}

/**
 * ガイドが流れている間の進み具合（観測画面の畳んだ側）。
 *
 * **空を見ている人はスマホを見ない**ので、ここは同伴者と、あとから確かめたい人のためにある。
 * グラスでできること（1 回タップで次へ・2 回でもう一度）も書いておく。
 *
 * **止めるのはここに置かない。** 主ボタンが「ガイドを止める」に変わっている
 * （止める口を 2 つ並べると、送るつもりで止めてしまう）。
 */
@Composable
internal fun GuideProgressCard(
    progress: GuideProgress,
    onNext: () -> Unit,
    onRepeat: () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SaberaSurface),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(progress.title, style = MaterialTheme.typography.titleSmall)
                Text(progress.counter, style = MaterialTheme.typography.titleSmall, color = SaberaGreen)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "${progress.stepName}　${progress.phase.label}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { (progress.stepIndex + 1f) / progress.stepCount },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "グラスは1回タップで次へ、2回タップでもう一度。長押しで質問できます",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onRepeat, modifier = Modifier.weight(1f)) {
                    Text("もう一度")
                }
                OutlinedButton(onClick = onNext, modifier = Modifier.weight(1f)) {
                    Text("次へ")
                }
            }
        }
    }
}
