package jp.jig.glasses.sample.kmp.ui.component

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Typography
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import jp.jig.glasses.sample.kmp.R

internal val SaberaGreen = Color(0xFF75E6A3)
internal val SaberaOnAccent = Color(0xFF052010)
internal val SaberaWarning = Color(0xFFFFC66D)
internal val SaberaSurface = Color(0xE6152028)
internal val SaberaSurfaceVariant = Color(0xE6243039)

/** 段階の選択（空の濃さなど）で「いまこれ」を示す下地 */
internal val SaberaSelected = Color(0xFF2D6A4F)

/** 帰属表示のような、読めればよい細かい字 */
internal val SaberaFinePrint = Color(0xFF8A9BA8)

internal val MPlusRoundedFontFamily = FontFamily(
    Font(R.font.m_plus_rounded_1c_regular, FontWeight.Normal),
    Font(R.font.m_plus_rounded_1c_medium, FontWeight.Medium),
    Font(R.font.m_plus_rounded_1c_bold, FontWeight.Bold),
)

private val DefaultTypography = Typography()

/** サイズ・行間・字間は既定値のまま、用途に応じて書体とウェイトだけを揃える。 */
internal val SaberaTypography = DefaultTypography.copy(
    displayLarge = DefaultTypography.displayLarge.withMPlusRounded(FontWeight.Bold),
    displayMedium = DefaultTypography.displayMedium.withMPlusRounded(FontWeight.Bold),
    displaySmall = DefaultTypography.displaySmall.withMPlusRounded(FontWeight.Bold),
    headlineLarge = DefaultTypography.headlineLarge.withMPlusRounded(FontWeight.Bold),
    headlineMedium = DefaultTypography.headlineMedium.withMPlusRounded(FontWeight.Bold),
    headlineSmall = DefaultTypography.headlineSmall.withMPlusRounded(FontWeight.Bold),
    titleLarge = DefaultTypography.titleLarge.withMPlusRounded(FontWeight.Medium),
    titleMedium = DefaultTypography.titleMedium.withMPlusRounded(FontWeight.Medium),
    titleSmall = DefaultTypography.titleSmall.withMPlusRounded(FontWeight.Medium),
    bodyLarge = DefaultTypography.bodyLarge.withMPlusRounded(FontWeight.Normal),
    bodyMedium = DefaultTypography.bodyMedium.withMPlusRounded(FontWeight.Normal),
    bodySmall = DefaultTypography.bodySmall.withMPlusRounded(FontWeight.Normal),
    labelLarge = DefaultTypography.labelLarge.withMPlusRounded(FontWeight.Medium),
    labelMedium = DefaultTypography.labelMedium.withMPlusRounded(FontWeight.Medium),
    labelSmall = DefaultTypography.labelSmall.withMPlusRounded(FontWeight.Medium),
)

private fun TextStyle.withMPlusRounded(weight: FontWeight) = copy(
    fontFamily = MPlusRoundedFontFamily,
    fontWeight = weight,
)

internal val SaberaDarkColorScheme = darkColorScheme(
    primary = SaberaGreen,
    surface = SaberaSurface,
    surfaceVariant = SaberaSurfaceVariant,
    onSurface = Color(0xFFF4F8F5),
    onSurfaceVariant = Color(0xFFC5D0CB),
)
