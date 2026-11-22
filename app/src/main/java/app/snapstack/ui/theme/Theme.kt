package app.snapstack.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

val Violet = Color(0xFF8C7BFF)
val Mint = Color(0xFF5EF2D6)
val Pink = Color(0xFFFF7AC6)
val Gain = Color(0xFF2FD17C)
val Loss = Color(0xFFFF5A6E)

val HoloBrush = Brush.linearGradient(listOf(Mint, Violet, Pink))

private val Dark = darkColorScheme(
    primary = Violet,
    onPrimary = Color(0xFF140F33),
    primaryContainer = Color(0xFF2C2366),
    onPrimaryContainer = Color(0xFFE3DEFF),
    secondary = Mint,
    onSecondary = Color(0xFF00382F),
    secondaryContainer = Color(0xFF113F3A),
    onSecondaryContainer = Color(0xFFB9FFF0),
    tertiary = Pink,
    background = Color(0xFF0E1020),
    onBackground = Color(0xFFE6E6F2),
    surface = Color(0xFF0E1020),
    onSurface = Color(0xFFE6E6F2),
    surfaceVariant = Color(0xFF232642),
    onSurfaceVariant = Color(0xFFB4B6CF),
    surfaceContainerLowest = Color(0xFF0A0C18),
    surfaceContainerLow = Color(0xFF14172B),
    surfaceContainer = Color(0xFF181B31),
    surfaceContainerHigh = Color(0xFF1F2340),
    surfaceContainerHighest = Color(0xFF282C4C),
    outline = Color(0xFF4A4E70),
    outlineVariant = Color(0xFF2E3252),
    error = Loss,
)

private val Light = lightColorScheme(
    primary = Color(0xFF5B4BC4),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE6E1FF),
    onPrimaryContainer = Color(0xFF1B1066),
    secondary = Color(0xFF00897B),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFC8F5EC),
    onSecondaryContainer = Color(0xFF00382F),
    tertiary = Color(0xFFC2378A),
    background = Color(0xFFF7F6FC),
    onBackground = Color(0xFF16172A),
    surface = Color(0xFFF7F6FC),
    onSurface = Color(0xFF16172A),
    surfaceVariant = Color(0xFFE4E3F0),
    onSurfaceVariant = Color(0xFF4C4D66),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF1F0F9),
    surfaceContainer = Color(0xFFECEBF6),
    surfaceContainerHigh = Color(0xFFE6E5F2),
    surfaceContainerHighest = Color(0xFFE0DFEE),
    outline = Color(0xFF7A7B95),
    outlineVariant = Color(0xFFCACAD9),
    error = Color(0xFFD32F45),
)

private val base = Typography()
private val AppTypography = base.copy(
    displaySmall = base.displaySmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp, fontFeatureSettings = "tnum"),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
)

val Numeric = TextStyle(fontFeatureSettings = "tnum")

@Composable
fun SnapStackTheme(content: @Composable () -> Unit) {
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
    MaterialTheme(colorScheme = if (dark) Dark else Light, typography = AppTypography, content = content)
}
