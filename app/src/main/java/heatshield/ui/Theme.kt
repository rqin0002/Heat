package heatshield.ui

import androidx.compose.material3.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp

val Pine = Color(0xFF145A52)
val Ink = Color(0xFF172E2B)
val Muted = Color(0xFF52645E)
val Paper = Color(0xFFF5F7F6)
val Line = Color(0xFFDFE6E2)
val Mint = Color(0xFFE2F0E9)
val Amber = Color(0xFF86510B)
val AmberPale = Color(0xFFFFEBD1)
val ErrorRed = Color(0xFFAC322D)

@Composable
fun HeatShieldTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Pine,
            onPrimary = Color.White,
            primaryContainer = Mint,
            onPrimaryContainer = Ink,
            secondary = Pine,
            onSecondary = Color.White,
            secondaryContainer = Mint,
            onSecondaryContainer = Pine,
            tertiary = Amber,
            onTertiary = Color.White,
            tertiaryContainer = AmberPale,
            onTertiaryContainer = Color(0xFF4C2D00),
            background = Paper,
            onBackground = Ink,
            surface = Color.White,
            onSurface = Ink,
            surfaceVariant = Color(0xFFEDF1EC),
            onSurfaceVariant = Muted,
            surfaceContainerLowest = Color.White,
            surfaceContainerLow = Paper,
            surfaceContainer = Color(0xFFF0F4EE),
            surfaceContainerHigh = Color(0xFFEDF1EC),
            surfaceContainerHighest = Line,
            surfaceBright = Color.White,
            surfaceDim = Line,
            surfaceTint = Pine,
            outline = Color(0xFF7D9288),
            outlineVariant = Line,
            error = ErrorRed
        ),
        shapes = Shapes(
            extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(14.dp), large = RoundedCornerShape(20.dp),
            extraLarge = RoundedCornerShape(28.dp)
        ),
        typography = Typography(
            displaySmall = TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Bold,
                fontSize = 36.sp,
                lineHeight = 40.sp
            ),
            headlineLarge = TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Bold,
                fontSize = 28.sp,
                lineHeight = 34.sp,
                letterSpacing = (-0.6).sp
            ),
            headlineSmall = TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.SemiBold,
                fontSize = 24.sp,
                lineHeight = 30.sp,
                letterSpacing = (-0.4).sp
            ),
            titleLarge = TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.SemiBold,
                fontSize = 20.sp,
                lineHeight = 26.sp,
                letterSpacing = (-0.2).sp
            ),
            titleMedium = TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                lineHeight = 22.sp
            ),
            bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 24.sp),
            bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 21.sp),
            bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp, lineHeight = 17.sp),
            labelLarge = TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                lineHeight = 20.sp
            ),
            labelMedium = TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp,
                lineHeight = 16.sp
            ),
            labelSmall = TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Medium,
                fontSize = 11.sp,
                lineHeight = 15.sp
            )
        ), content = content
    )
}
