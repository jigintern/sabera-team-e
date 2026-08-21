package jp.jig.glasses.sample.kmp.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** 画面をまたいで見た目を揃える、全幅の主要アクション。 */
@Composable
internal fun CommandButton(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
    ) {
        Text(label)
    }
}
