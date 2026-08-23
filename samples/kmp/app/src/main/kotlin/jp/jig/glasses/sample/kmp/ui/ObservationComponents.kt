package jp.jig.glasses.sample.kmp.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import jp.jig.glasses.sample.kmp.starmap.MoonPhase
import jp.jig.glasses.sample.kmp.starmap.PANEL_HEIGHT
import jp.jig.glasses.sample.kmp.starmap.PANEL_WIDTH
import jp.jig.glasses.sample.kmp.starmap.SkyBodyMark
import jp.jig.glasses.sample.kmp.starmap.SkyDarkness
import jp.jig.glasses.sample.kmp.starmap.SkyDensity
import kotlin.math.roundToInt

@Composable
internal fun ObservationPreview(
    bitmap: Bitmap?,
    sending: Boolean,
    transferMs: Long,
    modifier: Modifier = Modifier,
) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = Color.Black)) {
        Box(Modifier.fillMaxWidth().aspectRatio(PANEL_WIDTH / PANEL_HEIGHT.toFloat())) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth().background(Color.Black),
                )
            } else {
                LoadingPanel(
                    text = if (sending) "1 枚目を送信中（約 $transferMs ms）" else "送信を待っている",
                    hint = "首を止めると送ります（0.4 秒じっとする）",
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (sending && bitmap != null) {
                SendingChip(
                    "送信中（グラスは一時的に消える）",
                    Modifier.align(Alignment.TopStart).padding(8.dp),
                )
            }
        }
    }
}

@Composable
internal fun NarrationPanel(
    status: String,
    subject: String,
    text: String,
    failed: Boolean,
) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SaberaSurface),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(status, color = MaterialTheme.colorScheme.primary)
            if (subject.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(subject, style = MaterialTheme.typography.titleLarge)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text,
                modifier = Modifier.fillMaxWidth().heightIn(max = 160.dp).verticalScroll(rememberScrollState()),
                style = MaterialTheme.typography.bodyLarge,
                color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
internal fun ObservationActions(
    primaryLabel: String,
    onPrimary: () -> Unit,
    onRecalibrate: () -> Unit,
) {
    Spacer(Modifier.height(12.dp))
    Button(
        onClick = onPrimary,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = SaberaOnAccent,
        ),
    ) {
        Text(primaryLabel)
    }
    OutlinedButton(onClick = onRecalibrate, modifier = Modifier.fillMaxWidth()) {
        Text("方位を合わせ直す")
    }
}

/**
 * 「いまの空」。**他の星見アプリ（Sky Guide / SkySafari / Stellarium）が必ず出している情報**を
 * 1 枚に畳む。空の状態・月の見え方・視野の惑星・どこまで描いているか。
 *
 * **グラスには出さない。** かけている人は空を見ていて、読めるのは星図と名前まで。
 * これは**同伴者がスマホで見る**ためのもの（app-flow.md の役割分担）。
 */
@Composable
internal fun SkyNowCard(
    darkness: SkyDarkness,
    moon: MoonPhase?,
    bodies: List<SkyBodyMark>,
    density: SkyDensity,
    /** 重ねている衛星の数。出していないときは null */
    satellites: Int?,
) {
    SettingsSection("いまの空") {
        SkyNowRow(
            "空の状態",
            when (darkness) {
                SkyDarkness.DAY -> "昼。肉眼では星は見えない"
                SkyDarkness.CIVIL -> "薄明。明るい星と惑星から見えてくる"
                SkyDarkness.NIGHT -> "夜。星が見える"
            },
        )
        SkyNowRow(
            "月",
            moon?.let {
                "%s（月齢 %.1f・輝面 %d%%）".format(it.nameJa, it.ageDays, (it.illuminated * 100).roundToInt())
            } ?: "計算中",
        )
        SkyNowRow(
            "視野の月と惑星",
            if (bodies.isEmpty()) "いまは無い" else bodies.joinToString("・") { it.nameJa },
        )
        SkyNowRow(
            "描いている星",
            "%s・%.1f 等まで".format(density.label, density.limitMagnitude),
        )
        SkyNowRow(
            "人工衛星",
            satellites?.let { if (it == 0) "空に出ていない" else "$it 機が空に出ている" } ?: "重ねていない",
        )
    }
}

@Composable
private fun SkyNowRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            label,
            modifier = Modifier.weight(0.34f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, modifier = Modifier.weight(0.66f), style = MaterialTheme.typography.bodySmall)
    }
}
