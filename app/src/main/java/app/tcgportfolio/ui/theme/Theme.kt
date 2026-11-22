package app.tcgportfolio.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/*
 * "Vault" theme: ink-black and warm paper surfaces, a gold accent taken from the logo's card,
 * and teal from its scanner brackets.
 */

val Gold = Color(0xFFF5C451)
val GoldLight = Color(0xFFFFE29A)
val GoldDeep = Color(0xFFE39A2E)
val Teal = Color(0xFF5ED6C8)

/** Gold gradient of the logo's card: the shutter ring and the portfolio value card. */
val AccentBrush = Brush.linearGradient(listOf(GoldLight, Gold, GoldDeep))

/** Gains and losses, readable on each background. */
data class MarketColors(val gain: Color, val loss: Color)

private val DarkMarket = MarketColors(gain = Color(0xFF34D399), loss = Color(0xFFF87171))
private val LightMarket = MarketColors(gain = Color(0xFF0B7A54), loss = Color(0xFFC62828))
val LocalMarketColors = staticCompositionLocalOf { DarkMarket }

/** Scanner overlay colors: always drawn over the camera picture, so the same in both modes. */
val ScanVerified = DarkMarket.gain

private val Dark = darkColorScheme(
    primary = Gold,
    onPrimary = Color(0xFF3A2800),
    primaryContainer = Color(0xFF4D3700),
    onPrimaryContainer = Color(0xFFFFE3A3),
    inversePrimary = Color(0xFF8A5A00),
    secondary = Teal,
    onSecondary = Color(0xFF00332E),
    secondaryContainer = Color(0xFF0E4A44),
    onSecondaryContainer = Color(0xFFB6F5EC),
    tertiary = Color(0xFFFF9B7A),
    onTertiary = Color(0xFF4A1300),
    tertiaryContainer = Color(0xFF6B2A12),
    onTertiaryContainer = Color(0xFFFFDBCF),
    background = Color(0xFF0B0F14),
    onBackground = Color(0xFFE8ECF1),
    surface = Color(0xFF0B0F14),
    onSurface = Color(0xFFE8ECF1),
    surfaceVariant = Color(0xFF1E2630),
    onSurfaceVariant = Color(0xFFA9B4C2),
    surfaceTint = Gold,
    surfaceContainerLowest = Color(0xFF070A0E),
    surfaceContainerLow = Color(0xFF10151C),
    surfaceContainer = Color(0xFF141A22),
    surfaceContainerHigh = Color(0xFF1A212B),
    surfaceContainerHighest = Color(0xFF222A35),
    inverseSurface = Color(0xFFE8ECF1),
    inverseOnSurface = Color(0xFF161A1F),
    outline = Color(0xFF4A5563),
    outlineVariant = Color(0xFF2A333F),
    error = Color(0xFFF87171),
    onError = Color(0xFF450A0A),
)

private val Light = lightColorScheme(
    primary = Color(0xFF8A5A00),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE3A3),
    onPrimaryContainer = Color(0xFF2B1C00),
    inversePrimary = Gold,
    secondary = Color(0xFF00796E),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFC4F1EA),
    onSecondaryContainer = Color(0xFF00201C),
    tertiary = Color(0xFFB4441F),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDBCF),
    onTertiaryContainer = Color(0xFF3A0B00),
    background = Color(0xFFFAF8F4),
    onBackground = Color(0xFF161A1F),
    surface = Color(0xFFFAF8F4),
    onSurface = Color(0xFF161A1F),
    surfaceVariant = Color(0xFFECE7DD),
    onSurfaceVariant = Color(0xFF4F5560),
    surfaceTint = Color(0xFF8A5A00),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF5F2EC),
    surfaceContainer = Color(0xFFF0ECE5),
    surfaceContainerHigh = Color(0xFFEAE6DE),
    surfaceContainerHighest = Color(0xFFE4DFD6),
    inverseSurface = Color(0xFF2B3036),
    inverseOnSurface = Color(0xFFF2EFE9),
    outline = Color(0xFF7D838D),
    outlineVariant = Color(0xFFD4CFC6),
    error = Color(0xFFC62828),
    onError = Color.White,
)

private val base = Typography()
private val AppTypography = base.copy(
    displaySmall = base.displaySmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp, fontFeatureSettings = "tnum"),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.25).sp),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
)

val Numeric = TextStyle(fontFeatureSettings = "tnum")

@Composable
fun TcgPortfolioTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalMarketColors provides if (dark) DarkMarket else LightMarket) {
        MaterialTheme(colorScheme = if (dark) Dark else Light, typography = AppTypography, content = content)
    }
}
