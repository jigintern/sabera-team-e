package jp.jig.glasses.sample.kmp.ui.component

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

internal val SaberaGreen = Color(0xFF75E6A3)
internal val SaberaOnAccent = Color(0xFF052010)
internal val SaberaWarning = Color(0xFFFFC66D)
internal val SaberaSurface = Color(0xE6152028)
internal val SaberaSurfaceVariant = Color(0xE6243039)

/** 段階の選択（空の濃さなど）で「いまこれ」を示す下地 */
internal val SaberaSelected = Color(0xFF2D6A4F)

/** 帰属表示のような、読めればよい細かい字 */
internal val SaberaFinePrint = Color(0xFF8A9BA8)

internal val SaberaDarkColorScheme = darkColorScheme(
    primary = SaberaGreen,
    surface = SaberaSurface,
    surfaceVariant = SaberaSurfaceVariant,
    onSurface = Color(0xFFF4F8F5),
    onSurfaceVariant = Color(0xFFC5D0CB),
)
