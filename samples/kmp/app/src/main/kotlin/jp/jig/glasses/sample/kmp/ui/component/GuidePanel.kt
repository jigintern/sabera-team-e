package jp.jig.glasses.sample.kmp.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import jp.jig.glasses.sample.kmp.guide.GuideProgress
import jp.jig.glasses.sample.kmp.guide.StarGuide

/**
 * 設定パネルの区画「星座ガイド」。**始めるのはここだけ。**
 *
 * 台本を作るのはホームから（グラスが要らず、電波のあるうちに作る）。
 * ここは空の下で開く画面なので、**選んで始める**ことだけができればよい。
 *
 * ガイドが流れている間は、進み具合と操作の説明を出す。**空を見ている人はスマホを見ない**ので、
 * ここは同伴者と、あとから確かめたい人のためにある。
 */
@Composable
internal fun GuidePanel(
    guides: List<StarGuide>,
    progress: GuideProgress?,
    onStart: (StarGuide) -> Unit,
    onStop: () -> Unit,
    onNext: () -> Unit,
    onRepeat: () -> Unit,
) {
    SettingsSection(
        title = "星座ガイド",
        hint = progress?.let { "${it.title}　${it.counter}" }
            ?: "決めた順に星座へ案内して、そのつど解説します",
    ) {
        if (progress != null) {
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
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) {
                Text("ガイドを止める", color = SaberaWarning)
            }
            return@SettingsSection
        }

        if (guides.isEmpty()) {
            // **無いことを黙って出さない。** どこで作れるのかまで書く
            Text(
                "まだ台本がありません。ホーム画面の「ガイドを作る」で用意できます",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SettingsSection
        }

        for (guide in guides) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(guide.title, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "${guide.size} 星座・${guide.origin.label}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(
                    onClick = { onStart(guide) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SaberaGreen,
                        contentColor = SaberaOnAccent,
                    ),
                ) {
                    Text("始める")
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}
