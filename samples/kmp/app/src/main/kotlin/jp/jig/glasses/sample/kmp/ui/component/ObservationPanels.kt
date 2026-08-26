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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import jp.jig.glasses.sample.kmp.glass.PANEL_HEIGHT
import jp.jig.glasses.sample.kmp.glass.PANEL_WIDTH
import jp.jig.glasses.sample.kmp.glass.GuidanceIndicatorGeometry
import jp.jig.glasses.sample.kmp.glass.GuidancePoint
import jp.jig.glasses.sample.kmp.glass.guidanceIndicatorBox
import jp.jig.glasses.sample.kmp.glass.guidanceIndicatorGeometry
import jp.jig.glasses.sample.kmp.glass.guidanceTurnText
import jp.jig.glasses.sample.kmp.sky.GuidanceDirection
import jp.jig.glasses.sample.kmp.sky.GuidanceFrame
import jp.jig.glasses.sample.kmp.sky.GuidanceStage

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
        // **グラスに出るのと同じ大きさで重ねる。** 枠（120×48 など）をパネルの高さで
        // 割った比率をそのまま使うので、プレビューだけ見やすくならない
        // （見やすくすると、実機で読めるかを画面で判断できなくなる）
        val box = guidanceIndicatorBox(frame)
        val scale = size.minDimension * (box.span / PANEL_HEIGHT.toFloat())
        fun at(point: GuidancePoint) = Offset(
            center.x + point.x.toFloat() * scale,
            center.y + point.y.toFloat() * scale,
        )
        when (val geometry = guidanceIndicatorGeometry(frame)) {
            is GuidanceIndicatorGeometry.Arrival -> {
                val stroke = (geometry.strokeHalfWidth * 2.0).toFloat() * scale
                drawCircle(
                    mint,
                    radius = geometry.innerRadius.toFloat() * scale,
                    center = center,
                    style = Stroke(stroke),
                )
                drawCircle(
                    mint,
                    radius = geometry.outerRadius.toFloat() * scale,
                    center = center,
                    style = Stroke(stroke),
                )
                val arm = geometry.starArm.toFloat() * scale
                drawLine(mint, center - Offset(arm * 0.45f, 0f), center + Offset(arm * 0.45f, 0f), stroke)
                drawLine(mint, center - Offset(0f, arm), center + Offset(0f, arm), stroke)
            }
            is GuidanceIndicatorGeometry.Arrow -> {
                // 軸は塗った帯、頭は塗った三角。**細線では描かない**（グラスと同じ理由）
                drawLine(
                    mint,
                    at(geometry.tail),
                    at(geometry.tip),
                    strokeWidth = (geometry.shaftHalfWidth * 2.0).toFloat() * scale,
                    cap = StrokeCap.Round,
                )
                val tip = at(geometry.tip)
                val left = at(geometry.headBase.first())
                val right = at(geometry.headBase.last())
                val head = Path()
                head.moveTo(tip.x, tip.y)
                head.lineTo(left.x, left.y)
                head.lineTo(right.x, right.y)
                head.close()
                drawPath(head, mint)
            }
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
                Text(guidanceInstruction(frame), style = MaterialTheme.typography.bodyMedium)
                // **グラスの 2 行目と同じ数字を出す。** 別の数字を並べると、
                // 同伴者が見ているスマホと本人が見ているグラスで話が食い違う
                val turn = guidanceTurnText(frame)
                Text(
                    if (turn == null) {
                        "目標まで約${frame.distanceDeg.toInt()}°"
                    } else {
                        "$turn（目標まで約${frame.distanceDeg.toInt()}°）"
                    },
                )
                if (frame.near) Text("もう少し", style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) {
                Text("案内を終了")
            }
        }
    }
}

internal fun guidanceInstruction(frame: GuidanceFrame): String = when (frame.stage) {
    GuidanceStage.HORIZONTAL -> when (frame.direction) {
        GuidanceDirection.LEFT -> "まず左を向いてください"
        GuidanceDirection.RIGHT -> "まず右を向いてください"
        else -> "まず左右を合わせてください"
    }
    GuidanceStage.VERTICAL -> when (frame.direction) {
        GuidanceDirection.UP -> "次に上を向いてください"
        GuidanceDirection.DOWN -> "次に下を向いてください"
        else -> "次に上下を合わせてください"
    }
    GuidanceStage.ARRIVED -> "このあたりです"
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
            // **名前は動かさない。** 本文だけが下の枠の中を流れるので、
            // 読んでいる途中でも「何の話か」が画面から消えない
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
                        if (maxTextHeight == null) {
                            Modifier.weight(1f)
                        } else {
                            // **高さを決め打つ。** 短い解説で枠が縮むと、次の 1 文が届くたびに
                            // 下のボタンが動く。流す場所は最初から同じ大きさで空けておく
                            Modifier.heightIn(min = maxTextHeight, max = maxTextHeight)
                        },
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
    /** 主ボタンの次に置くもの（いまはガイドの開始）。**要らないときは出さない** */
    secondaryLabel: String? = null,
    onSecondary: () -> Unit = {},
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
    if (secondaryLabel != null) {
        OutlinedButton(onClick = onSecondary, modifier = Modifier.fillMaxWidth()) {
            Text(secondaryLabel)
        }
    }
}

/**
 * 方位を合わせ直す。**画面のいちばん下に固定する**（流れる中身の外に置く）。
 *
 * 押す機会はいちばん少ないので上には置かない（誤って押すと方位合わせからやり直しになる）。
 * それでも**流れて消えてはいけない** — 星図がずれていると気づいたときに探させることになる。
 */
@Composable
internal fun RecalibrateButton(onRecalibrate: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(onClick = onRecalibrate, modifier = modifier.fillMaxWidth()) {
        Text("方位を合わせ直す")
    }
}

