package jp.jig.glasses.sample.kmp.ui.component

import android.graphics.Bitmap
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import jp.jig.glasses.sample.kmp.glass.PANEL_HEIGHT
import jp.jig.glasses.sample.kmp.glass.PANEL_WIDTH

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
        }
    }
}

@Composable
internal fun NarrationPanel(
    status: String,
    subject: String,
    text: String,
    failed: Boolean,
    modifier: Modifier = Modifier,
    // **null = 高さを親に預ける。** 本文が余った縦を吸うので、後ろのボタンが下端へ落ちる。
    // 横画面は縦幅が 350dp ほどしかなく、上限で切ると解説の下に何もない帯が残る
    maxTextHeight: Dp? = 160.dp,
) {
    val fill = maxTextHeight == null
    Card(
        modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SaberaSurface),
    ) {
        // 親から高さをもらったときだけ縦も埋める（縦画面で入れると画面いっぱいに伸びる）
        Column((if (fill) Modifier.fillMaxSize() else Modifier.fillMaxWidth()).padding(12.dp)) {
            if (status.isNotEmpty()) {
                Text(status, color = MaterialTheme.colorScheme.primary)
            }
            if (subject.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(subject, style = MaterialTheme.typography.titleLarge)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text,
                modifier = Modifier.fillMaxWidth()
                    // 直に null を見る（fill 経由だとスマートキャストが効かない）
                    .then(
                        if (maxTextHeight == null) Modifier.weight(1f)
                        else Modifier.heightIn(max = maxTextHeight),
                    )
                    .verticalScroll(rememberScrollState()),
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
