package jp.jig.glasses.sample.kmp.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import jp.jig.glasses.sample.kmp.R
import jp.jig.glasses.sample.kmp.ui.component.ConstellationBackground
import jp.jig.glasses.sample.kmp.ui.component.SaberaGreen
import jp.jig.glasses.sample.kmp.ui.component.SaberaOnAccent
import jp.jig.glasses.sample.kmp.ui.component.SeasonalConstellationBackground

@Composable
fun HomeScreen(
    constellation: ConstellationBackground,
    onStart: () -> Unit,
    onGuides: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        SeasonalConstellationBackground(
            constellation = constellation,
            modifier = Modifier.fillMaxSize(),
        )

        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 24.dp),
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
    }
}
