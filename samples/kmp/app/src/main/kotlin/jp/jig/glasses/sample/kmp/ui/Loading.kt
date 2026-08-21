package jp.jig.glasses.sample.kmp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * 待っていることの見せ方。
 *
 * **このアプリは黙る場面が多い。** 星表の読み込み、TLE の読み込み、測位、
 * AI の返事、そして**星図の転送（実測 332〜390ms。その間グラスは前の絵を消す）**。
 * 文字だけだと「止まったのか、進んでいるのか」が分からないので、回るものを添える。
 *
 * 色はテーマの既定（紫）ではなく SABERA の緑にする。夜空の画面に載せるため。
 */

/**
 * 1 行ぶんの待ち表示。カードや一覧の中に混ぜて使う。
 *
 * @param text いま何を待っているか。「読み込み中」だけでなく**何を**待っているかを書く
 */
@Composable
fun LoadingLine(
    text: String,
    modifier: Modifier = Modifier,
    // 既定は明るい面（星図の画面）に載る色。夜空の画面だけ [SaberaGreen] を渡す
    color: Color = MaterialTheme.colorScheme.primary,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            color = color,
            strokeWidth = 2.dp,
        )
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

/**
 * 絵が出る場所そのものに置く待ち表示。
 *
 * **方位合わせの直後がこれに当たる。** 星図の画面に来ても、星表を読んで 1 枚目を
 * 送り終わるまで数秒かかる。何も出ないと「壊れた」と思われる。
 *
 * @param hint 待つ以外にしてほしいことがあれば書く（例「首を止めると出ます」）
 */
@Composable
fun LoadingPanel(
    text: String,
    hint: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier,
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(28.dp),
            color = SaberaGreen,
            strokeWidth = 3.dp,
        )
        Spacer(Modifier.padding(top = 10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = SaberaGreen)
        if (hint != null) {
            Spacer(Modifier.padding(top = 4.dp))
            Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * すでに絵が出ている上に重ねる小さな札。
 *
 * 転送中はグラスから絵が消えるので、**スマホのプレビューが「いま映っているもの」ではなくなる**。
 * その食い違いを黙って放置しないための札。
 */
@Composable
fun SendingChip(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xCC0C151D))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(12.dp),
            color = SaberaGreen,
            strokeWidth = 2.dp,
        )
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, color = SaberaGreen)
    }
}
