package jp.jig.glasses.sample.kmp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun HomeScreen(
    constellation: ConstellationBackground,
    onStart: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        SeasonalConstellationBackground(
            constellation = constellation,
            modifier = Modifier.fillMaxSize(),
        )

        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Spacer(Modifier.weight(1f))

            Text(
                text = "星がたり",
                style = MaterialTheme.typography.displaySmall,
                color = Color.White,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = "星空を、もっと身近に。",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White.copy(alpha = 0.82f),
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.weight(1f))

            Button(
                onClick = onStart,
                modifier = Modifier.fillMaxWidth().widthIn(max = 320.dp).height(52.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF75E6A3),
                    contentColor = Color(0xFF052010),
                ),
            ) {
                Text("スタート")
            }
        }
    }
}
