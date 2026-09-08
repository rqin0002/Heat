package heatshield.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Neutral surfaces carry structure. Colour is reserved for action, attention
// and chart comparison; none of these roles depends on colour alone.
val PrimaryAction = Color(0xFF30363D)
val Ink = Color(0xFF22272D)
val Muted = Color(0xFF60676E)
val Paper = Color(0xFFFAFAF9)
val Line = Color(0xFFDFE1E2)
val SelectedSurface = Color(0xFFE8EAEC)
val Attention = Color(0xFF824936)
val AttentionSurface = Color(0xFFF1EFED)
val ErrorRed = Color(0xFFB3261E)
val ControlOutline = Color(0xFF7B8289)
val SecondarySeries = Color(0xFF5C7385)

object UiSpace {
    val tiny = 4.dp
    val small = 8.dp
    val compact = 12.dp
    val related = 16.dp
    val section = 24.dp
    val large = 32.dp
}

object UiShape {
    val small = RoundedCornerShape(6.dp)
    val control = RoundedCornerShape(12.dp)
    val card = RoundedCornerShape(16.dp)
}

private fun type(size: Int, height: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontFamily = FontFamily.SansSerif, fontSize = size.sp, lineHeight = height.sp,
    fontWeight = weight, letterSpacing = 0.sp
)

@Composable
fun HeatShieldTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = PrimaryAction, onPrimary = Color.White,
            primaryContainer = SelectedSurface, onPrimaryContainer = Ink,
            secondary = PrimaryAction, onSecondary = Color.White,
            secondaryContainer = SelectedSurface, onSecondaryContainer = PrimaryAction,
            tertiary = Attention, onTertiary = Color.White,
            tertiaryContainer = AttentionSurface, onTertiaryContainer = Ink,
            background = Paper, onBackground = Ink,
            surface = Color.White, onSurface = Ink,
            surfaceVariant = Color(0xFFF1F2F2), onSurfaceVariant = Muted,
            surfaceContainerLowest = Color.White, surfaceContainerLow = Paper,
            surfaceContainer = Color(0xFFF3F3F2), surfaceContainerHigh = Color(0xFFEDEEEF),
            surfaceContainerHighest = Line, surfaceBright = Color.White, surfaceDim = Line,
            surfaceTint = Color.Transparent, outline = ControlOutline, outlineVariant = Line,
            error = ErrorRed, onError = Color.White,
            errorContainer = Color(0xFFF9E9E7), onErrorContainer = Color(0xFF5F1915)
        ),
        shapes = Shapes(
            extraSmall = UiShape.small, small = UiShape.control,
            medium = UiShape.control, large = UiShape.card,
            extraLarge = RoundedCornerShape(24.dp)
        ),
        typography = Typography(
            displayLarge = type(48, 54, FontWeight.Medium),
            displayMedium = type(40, 46, FontWeight.Medium),
            displaySmall = type(36, 40, FontWeight.Medium).copy(fontFeatureSettings = "tnum"),
            headlineLarge = type(28, 34, FontWeight.SemiBold),
            headlineMedium = type(24, 30, FontWeight.SemiBold),
            headlineSmall = type(22, 28, FontWeight.SemiBold),
            titleLarge = type(22, 28, FontWeight.SemiBold),
            titleMedium = type(18, 24, FontWeight.Medium),
            titleSmall = type(16, 22, FontWeight.Medium),
            bodyLarge = type(16, 24), bodyMedium = type(16, 24), bodySmall = type(14, 20),
            labelLarge = type(16, 24, FontWeight.Medium),
            labelMedium = type(14, 20, FontWeight.Medium),
            labelSmall = type(14, 20, FontWeight.Medium)
        ), content = content
    )
}
