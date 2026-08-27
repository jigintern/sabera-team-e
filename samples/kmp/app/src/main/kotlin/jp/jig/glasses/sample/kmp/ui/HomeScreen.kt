package jp.jig.glasses.sample.kmp.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import jp.jig.glasses.sample.kmp.R
import jp.jig.glasses.sample.kmp.narration.SkyTip
import jp.jig.glasses.sample.kmp.ui.component.ACTION_BUTTON_MAX_WIDTH
import jp.jig.glasses.sample.kmp.ui.component.ConstellationBackground
import jp.jig.glasses.sample.kmp.ui.component.SaberaGreen
import jp.jig.glasses.sample.kmp.ui.component.SaberaOnAccent
import jp.jig.glasses.sample.kmp.ui.component.SeasonalConstellationBackground
import jp.jig.glasses.sample.kmp.ui.component.rememberMeteorShowerNotice

/**
 * 入口の画面。
 *
 * **今日のひとことをここにも出す**（[tip]）。グラスの挨拶画面に出しているものと同じで、
 * **起動ごとに変わる**。スマホにも出すのは、
 *
 * - **グラスは 1 人しかかけられない。** 同伴者はここを見る
 * - つなぐ前でも、その夜の空について 1 つは分かる（暗さ・月・流星群・上がってくる衛星）
 *
 * 中身は端末が計算できることだけなので**圏外でも出る**（`SkyTips`）。
 */
@Composable
fun HomeScreen(
    constellation: ConstellationBackground,
    tip: SkyTip?,
    onStart: () -> Unit,
    onGuides: () -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        SeasonalConstellationBackground(
            constellation = constellation,
            modifier = Modifier.fillMaxSize(),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    horizontal = 32.dp,
                    vertical = if (landscape) 8.dp else 24.dp,
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(
                if (landscape) 6.dp else 16.dp,
                Alignment.CenterVertically,
            ),
        ) {
            // 間隔と中央寄せは Column の arrangement が持つ（横で weight の空きを積まない）
            Image(
                painter = painterResource(R.drawable.hoshishirube_logo),
                contentDescription = "星導",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth()
                    .widthIn(max = if (landscape) 280.dp else 340.dp)
                    .height(if (landscape) 64.dp else 96.dp),
            )
            Text(
                text = "星空を、もっと身近に。",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White.copy(alpha = 0.82f),
                textAlign = TextAlign.Center,
            )
            // **ひとことはスタートの手前に置く。** 押したあとの画面へ行ってしまう位置では読まれない。
            // **できる前から高さだけ空けておく**（あとから足すと、押そうとした先が動く）
            Box(
                modifier = Modifier.fillMaxWidth().widthIn(max = 420.dp)
                    .height(if (landscape) TIP_RESERVE_LANDSCAPE else TIP_RESERVE),
                contentAlignment = Alignment.Center,
            ) {
                if (tip != null) TipCard(tip)
            }

            Button(
                onClick = onStart,
                modifier = Modifier.widthIn(max = ACTION_BUTTON_MAX_WIDTH).fillMaxWidth()
                    .height(if (landscape) 44.dp else 52.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SaberaGreen,
                    contentColor = SaberaOnAccent,
                ),
            ) {
                Text("スタート")
            }

            // **ガイドを作るのはここ。** グラスが要らないので、出かける前に用意できる
            // （現地が圏外でも、作った台本は端末に残る）
            OutlinedButton(
                onClick = onGuides,
                modifier = Modifier.widthIn(max = ACTION_BUTTON_MAX_WIDTH).fillMaxWidth()
                    .height(if (landscape) 44.dp else 52.dp),
            ) {
                Text("ガイドを作る", color = Color.White)
            }

            // **通知を決めるのは出かける前。** グラスをかけてからでは、その夜はもう始まっている。
            // ここが入り口なので、切るためのもう 1 か所は観測画面の設定パネルに置いてある
            MeteorShowerNoticeRow()
        }
    }
}

/** ひとことのために空けておく高さ。**小さく 3 行ぶん。できるまでは空のまま置く** */
private val TIP_RESERVE = 84.dp

/** 横 350dp で通知行まで縦 1 列に収めるためのひとこと領域 */
private val TIP_RESERVE_LANDSCAPE = 60.dp

/** 今日のひとこと 1 枚。**背景の星に負けないよう、薄い板を敷いてから字を置く** */
/**
 * 流星群の予告（#70）の入／切。
 *
 * **ボタンにしない。** 押して何かが起きるものではなく、いま入っているかどうかが読めればよい。
 * 許可を聞くのも予約を取り直すのも [rememberMeteorShowerNotice] の中。
 */
@Composable
private fun MeteorShowerNoticeRow() {
    val notice = rememberMeteorShowerNotice()
    Row(
        modifier = Modifier
            .widthIn(max = ACTION_BUTTON_MAX_WIDTH)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .padding(start = 16.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "流星群を知らせる",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
            )
            Text(
                "いちばんよく流れる夜の、前日と当日の夕方に",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.7f),
            )
        }
        Switch(checked = notice.enabled, onCheckedChange = notice.onChange)
    }
}

@Composable
private fun TipCard(tip: SkyTip, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Black.copy(alpha = 0.42f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.AutoAwesome,
                null,
                tint = SaberaGreen,
                modifier = Modifier.size(13.dp),
            )
            Spacer(Modifier.width(5.dp))
            Text(
                tip.header,
                style = MaterialTheme.typography.labelSmall,
                color = SaberaGreen,
            )
        }
        Spacer(Modifier.height(3.dp))
        Text(
            tip.text,
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.9f),
        )
    }
}
