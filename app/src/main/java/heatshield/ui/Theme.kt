package heatshield.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import heatshield.R
import heatshield.domain.HeatLevel

// ---------- Colour ----------

/**
 * The palette spec's tokens as Compose colours. [action] is the one action colour; warm colours only mean heat, so
 * [attention] is Ink and a notice's icon carries its meaning. [isDark] picks the heat ramp's dark variants.
 */
@Immutable
data class HeatShieldColors(
    val paper: Color, val surface: Color, val surfaceHigh: Color, val ink: Color, val muted: Color, val line: Color,
    val outline: Color, val action: Color, val onAction: Color, val actionContainer: Color, val onActionContainer: Color,
    val emergency: Color, val onEmergency: Color, val emergencyContainer: Color,
    val success: Color, val successContainer: Color,
    val attention: Color, val attentionSurface: Color, val secondarySeries: Color, val isDark: Boolean
)

private val LightColors = with(Palette) {
    HeatShieldColors(
        paper = Color(LIGHT_PAPER), surface = Color(LIGHT_SURFACE), surfaceHigh = Color(LIGHT_SURFACE_HIGH),
        ink = Color(LIGHT_INK), muted = Color(LIGHT_MUTED), line = Color(LIGHT_LINE), outline = Color(LIGHT_OUTLINE),
        action = Color(LIGHT_ACTION), onAction = Color(LIGHT_ON_ACTION),
        actionContainer = Color(LIGHT_ACTION_CONTAINER), onActionContainer = Color(LIGHT_ON_ACTION_CONTAINER),
        emergency = Color(LIGHT_EMERGENCY), onEmergency = Color(LIGHT_ON_EMERGENCY),
        emergencyContainer = Color(LIGHT_EMERGENCY_CONTAINER),
        success = Color(LIGHT_SUCCESS), successContainer = Color(LIGHT_SUCCESS_CONTAINER),
        attention = Color(LIGHT_ATTENTION), attentionSurface = Color(LIGHT_ATTENTION_SURFACE),
        secondarySeries = Color(LIGHT_SECONDARY_SERIES), isDark = false
    )
}

private val DarkColors = with(Palette) {
    HeatShieldColors(
        paper = Color(DARK_PAPER), surface = Color(DARK_SURFACE), surfaceHigh = Color(DARK_SURFACE_HIGH),
        ink = Color(DARK_INK), muted = Color(DARK_MUTED), line = Color(DARK_LINE), outline = Color(DARK_OUTLINE),
        action = Color(DARK_ACTION), onAction = Color(DARK_ON_ACTION),
        actionContainer = Color(DARK_ACTION_CONTAINER), onActionContainer = Color(DARK_ON_ACTION_CONTAINER),
        emergency = Color(DARK_EMERGENCY), onEmergency = Color(DARK_ON_EMERGENCY),
        emergencyContainer = Color(DARK_EMERGENCY_CONTAINER),
        success = Color(DARK_SUCCESS), successContainer = Color(DARK_SUCCESS_CONTAINER),
        attention = Color(DARK_ATTENTION), attentionSurface = Color(DARK_ATTENTION_SURFACE),
        secondarySeries = Color(DARK_SECONDARY_SERIES), isDark = true
    )
}

// Material roles map onto the tokens (palette spec, "Material 3 roles"): Action is primary and secondary, Emergency is
// error, the page is background, cards are surface and the lowest containers, SURFACE_HIGH raises dialogs and pickers.
// surfaceTint stays transparent so cards stay flat, and there is no dynamic colour: heat and Action mean the same thing
// on every phone.
private fun colorSchemeOf(c: HeatShieldColors): ColorScheme =
    (if (c.isDark) darkColorScheme() else lightColorScheme()).copy(
        primary = c.action, onPrimary = c.onAction,
        primaryContainer = c.actionContainer, onPrimaryContainer = c.onActionContainer,
        inversePrimary = Color(if (c.isDark) Palette.LIGHT_ACTION else Palette.DARK_ACTION),
        secondary = c.action, onSecondary = c.onAction,
        secondaryContainer = c.actionContainer, onSecondaryContainer = c.onActionContainer,
        tertiary = c.attention, onTertiary = if (c.isDark) c.paper else c.surface,
        tertiaryContainer = c.attentionSurface, onTertiaryContainer = c.ink,
        background = c.paper, onBackground = c.ink,
        surface = c.surface, onSurface = c.ink,
        surfaceVariant = c.line, onSurfaceVariant = c.muted, surfaceTint = Color.Transparent,
        inverseSurface = c.ink, inverseOnSurface = c.paper,
        error = c.emergency, onError = c.onEmergency,
        errorContainer = c.emergencyContainer, onErrorContainer = c.emergency,
        outline = c.outline, outlineVariant = c.line,
        surfaceBright = if (c.isDark) c.surfaceHigh else c.surface, surfaceDim = if (c.isDark) c.paper else c.line,
        surfaceContainerLowest = c.surface, surfaceContainerLow = c.surface, surfaceContainer = c.surface,
        surfaceContainerHigh = c.surfaceHigh, surfaceContainerHighest = c.line
    )

private val LightScheme = colorSchemeOf(LightColors)
private val DarkScheme = colorSchemeOf(DarkColors)

val LocalHeatShieldColors: ProvidableCompositionLocal<HeatShieldColors> = staticCompositionLocalOf { LightColors }

/** Heat-level colours for the hero and the ribbon, wrapping the [Palette] Longs. */
object HeatPalette {
    fun fill(level: HeatLevel, dark: Boolean): Color = Color(Palette.fill(level, dark))
    fun onFill(level: HeatLevel): Color = Color(Palette.onFill(level))
    fun heroBackground(level: HeatLevel, dark: Boolean): Color = Color(Palette.heroBackground(level, dark))
    fun heroContent(level: HeatLevel, dark: Boolean): Color = Color(Palette.heroContent(level, dark))
}

// Legacy names kept as composable getters so existing screens compile and follow dark mode.
val PrimaryAction: Color
    @Composable @ReadOnlyComposable get() = HeatShieldTheme.colors.action
val Ink: Color
    @Composable @ReadOnlyComposable get() = HeatShieldTheme.colors.ink
val Muted: Color
    @Composable @ReadOnlyComposable get() = HeatShieldTheme.colors.muted
val Paper: Color
    @Composable @ReadOnlyComposable get() = HeatShieldTheme.colors.paper
val Line: Color
    @Composable @ReadOnlyComposable get() = HeatShieldTheme.colors.line
val SelectedSurface: Color
    @Composable @ReadOnlyComposable get() = HeatShieldTheme.colors.actionContainer
val Attention: Color
    @Composable @ReadOnlyComposable get() = HeatShieldTheme.colors.attention
val AttentionSurface: Color
    @Composable @ReadOnlyComposable get() = HeatShieldTheme.colors.attentionSurface
val ErrorRed: Color
    @Composable @ReadOnlyComposable get() = HeatShieldTheme.colors.emergency
val ControlOutline: Color
    @Composable @ReadOnlyComposable get() = HeatShieldTheme.colors.outline
val SecondarySeries: Color
    @Composable @ReadOnlyComposable get() = HeatShieldTheme.colors.secondarySeries

// ---------- Type ----------

// Both families are variable fonts; each weight asks the wght axis for that exact value.
@OptIn(ExperimentalTextApi::class)
private fun variableFont(resId: Int, weight: FontWeight) =
    Font(resId, weight, variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)))

private val BundledWeights = listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold)

val AtkinsonNext: FontFamily = FontFamily(BundledWeights.map { variableFont(R.font.atkinson_hyperlegible_next, it) })
val AtkinsonMono: FontFamily = FontFamily(BundledWeights.map { variableFont(R.font.atkinson_hyperlegible_mono, it) })

private fun type(family: FontFamily, size: Int, height: TextUnit, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontFamily = family, fontSize = size.sp, lineHeight = height, fontWeight = weight, letterSpacing = 0.sp
)

// Spec §7 roles. Nothing is smaller than 15 sp. Mono carries numerals and clocks.
internal val HeatShieldTypography = Typography(
    displayLarge = type(AtkinsonMono, 88, 1.05.em, FontWeight.Medium),     // hero numeral; em keeps the ratio when auto-sized to 56 sp
    displayMedium = type(AtkinsonMono, 56, 60.sp, FontWeight.Medium),
    displaySmall = type(AtkinsonMono, 36, 44.sp, FontWeight.Medium),       // break clock, log figures
    headlineLarge = type(AtkinsonNext, 28, 34.sp, FontWeight.SemiBold),    // level word
    headlineMedium = type(AtkinsonNext, 24, 30.sp, FontWeight.SemiBold),
    headlineSmall = type(AtkinsonNext, 22, 28.sp),                         // screen title
    titleLarge = type(AtkinsonNext, 22, 28.sp),                            // top bar title
    titleMedium = type(AtkinsonNext, 18, 24.sp, FontWeight.SemiBold),      // section
    titleSmall = type(AtkinsonNext, 15, 20.sp, FontWeight.Medium),
    bodyLarge = type(AtkinsonNext, 17, 24.sp),                             // body
    bodyMedium = type(AtkinsonNext, 17, 24.sp),
    bodySmall = type(AtkinsonNext, 15, 20.sp),
    labelLarge = type(AtkinsonNext, 15, 20.sp, FontWeight.Medium),         // label and button text
    labelMedium = type(AtkinsonNext, 15, 20.sp, FontWeight.Medium),
    labelSmall = type(AtkinsonNext, 15, 20.sp, FontWeight.Medium)
)

// ---------- Space and shape ----------

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
    val card = RoundedCornerShape(20.dp)      // TurnAgain's card corner
}

private val HeatShieldShapes = Shapes(
    extraSmall = UiShape.small, small = UiShape.control,
    medium = UiShape.control, large = UiShape.card,
    extraLarge = RoundedCornerShape(24.dp)
)

// ---------- Theme ----------

@Composable
fun HeatShieldTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalHeatShieldColors provides if (darkTheme) DarkColors else LightColors) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkScheme else LightScheme,
            shapes = HeatShieldShapes, typography = HeatShieldTypography, content = content
        )
    }
}

object HeatShieldTheme {
    val colors: HeatShieldColors
        @Composable @ReadOnlyComposable get() = LocalHeatShieldColors.current
}
