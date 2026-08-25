package jp.jig.glasses.sample.kmp.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import jp.jig.glasses.sample.kmp.ui.component.ConstellationBackground
import jp.jig.glasses.sample.kmp.ui.component.SaberaGreen
import jp.jig.glasses.sample.kmp.ui.component.SaberaOnAccent
import jp.jig.glasses.sample.kmp.ui.component.SeasonalConstellationBackground

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
    Box(modifier = Modifier.fillMaxSize()) {
        SeasonalConstellationBackground(
            constellation = constellation,
            modifier = Modifier.fillMaxSize(),
        )

        Column(
            // 下はひとことのぶんを**最初から空けておく**。あとから足すと、
            // 読み込みが終わった瞬間にボタンが飛び上がる（押そうとした先が動く）
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 32.dp, end = 32.dp, top = 24.dp, bottom = TIP_RESERVE),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            // 間隔と中央寄せは Column の arrangement が持つ（横で weight の空きを積まない）
            Image(
                painter = painterResource(R.drawable.hoshishirube_logo),
                contentDescription = "星しるべ",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().widthIn(max = 340.dp).height(96.dp),
            )
            Text(
                text = "星空を、もっと身近に。",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White.copy(alpha = 0.82f),
                textAlign = TextAlign.Center,
            )
            Button(
                onClick = onStart,
                modifier = Modifier.widthIn(max = 320.dp).fillMaxWidth().height(52.dp),
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
                modifier = Modifier.widthIn(max = 320.dp).fillMaxWidth().height(52.dp),
            ) {
                Text("ガイドを作る", color = Color.White)
            }
        }

        // **できるまで何も置かない。** 枠だけ先に出すと、開いた瞬間に空の箱が見える。
        // 下に寄せるのは、**ボタンの位置を動かさない**ため（中央の列に足すと押す場所がずれる）
        if (tip != null) {
            TipCard(
                tip,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 24.dp, vertical = 20.dp)
                    .widthIn(max = 420.dp),
            )
        }
    }
}

/** ひとことのために空けておく高さ。**3 行ぶん＋余白** */
private val TIP_RESERVE = 116.dp

/** 今日のひとこと 1 枚。**背景の星に負けないよう、薄い板を敷いてから字を置く** */
@Composable
private fun TipCard(tip: SkyTip, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color.Black.copy(alpha = 0.42f))
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.AutoAwesome,
                null,
                tint = SaberaGreen,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                tip.header,
                style = MaterialTheme.typography.labelLarge,
                color = SaberaGreen,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            tip.text,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.9f),
        )
    }
}
