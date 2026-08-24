package jp.jig.glasses.sample.kmp.ui.component

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import jp.jig.glasses.sample.kmp.glass.PANEL_HEIGHT
import jp.jig.glasses.sample.kmp.glass.PANEL_WIDTH
import jp.jig.glasses.sample.kmp.sky.GuidanceFrame
import jp.jig.glasses.sample.kmp.sky.RAD
import kotlin.math.cos
import kotlin.math.sin

@Composable
internal fun ObservationPreview(
    bitmap: Bitmap?,
    sending: Boolean,
    transferMs: Long,
    modifier: Modifier = Modifier,
    guidance: GuidanceFrame? = null,
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
                    hint = "首の動きが緩んだら送り始めます",
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (sending && bitmap != null) {
                SendingChip(
                    "送信中（グラスは一時的に消える）",
                    Modifier.align(Alignment.TopStart).padding(8.dp),
                )
            }
            guidance?.let { GuidancePreviewOverlay(it, Modifier.align(Alignment.Center)) }
        }
    }
}

@Composable
private fun GuidancePreviewOverlay(frame: GuidanceFrame, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxSize()) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val mint = Color(0xFF5CFFB0)
        if (frame.arrived) {
            drawCircle(mint, radius = size.minDimension * 0.10f, center = center, style = Stroke(3.dp.toPx()))
            drawCircle(mint.copy(alpha = 0.7f), radius = size.minDimension * 0.14f, center = center, style = Stroke(2.dp.toPx()))
            return@Canvas
        }
        val angle = frame.arrowClockwiseDeg * RAD
        val half = size.minDimension * if (frame.near) 0.08f else 0.13f
        val direction = Offset(sin(angle).toFloat(), -cos(angle).toFloat())
        val tail = center - direction * half
        val tip = center + direction * half
        drawLine(mint, tail, tip, strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round)
        val head = size.minDimension * 0.06f
        for (offset in listOf(-145.0, 145.0)) {
            val a = angle + offset * RAD
            val end = tip + Offset(sin(a).toFloat(), -cos(a).toFloat()) * head
            drawLine(mint, tip, end, strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round)
        }
    }
}

@Composable
internal fun GuidanceCard(
    frame: GuidanceFrame,
    onStop: () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SaberaSurface),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                if (frame.arrived) "${frame.targetName}はこのあたり" else "${frame.targetName}を案内中",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            if (!frame.arrived) {
                Spacer(Modifier.height(4.dp))
                Text("目標まで約${frame.distanceDeg.toInt()}°")
                if (frame.near) Text("もう少し", style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) {
                Text("案内を終了")
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
