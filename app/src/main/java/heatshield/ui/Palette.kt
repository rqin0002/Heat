package heatshield.ui

import heatshield.domain.HeatLevel
import kotlin.math.roundToLong

// Colour tokens as 0xAARRGGBB Longs (spec §7). No Compose imports, so JVM tests can check contrast
// against exactly the values Theme.kt wraps in Color(...).
// TurnAgain's neutral look adapted to Android (docs/superpowers/specs/2026-10-07-palette-turnagain-adaptation.md):
// grey page, white cards, one blue Action for everything you can do. Warm colours still only ever mean heat.
object Palette {
    // Light
    const val LIGHT_PAPER = 0xFFF5F5F7                 // the page behind the cards
    const val LIGHT_SURFACE = 0xFFFFFFFF               // cards, sheets and bars
    const val LIGHT_SURFACE_HIGH = 0xFFFFFFFF          // raised and selected rows, dialogs
    const val LIGHT_INK = 0xFF1D1D1F
    const val LIGHT_MUTED = 0xFF626269
    const val LIGHT_LINE = 0xFFDEDEE3                  // hairlines and card borders
    const val LIGHT_OUTLINE = 0xFF85858B               // control borders
    const val LIGHT_ACTION = 0xFF0066CC                // the one action colour
    const val LIGHT_ON_ACTION = 0xFFFFFFFF
    const val LIGHT_ACTION_CONTAINER = 0xFFEAF3FF      // selected chips, the chosen window card, tonal buttons
    const val LIGHT_ON_ACTION_CONTAINER = 0xFF004F9E
    const val LIGHT_EMERGENCY = 0xFFA12626             // first aid, unwell, destructive actions
    const val LIGHT_ON_EMERGENCY = 0xFFFFFFFF
    const val LIGHT_EMERGENCY_CONTAINER = 0xFFFFF0F0   // the first-aid banner
    const val LIGHT_SUCCESS = 0xFF1E6B3A
    const val LIGHT_SUCCESS_CONTAINER = 0xFFE8F5EC
    // Neutral notices: Ink text on a white card with a Line border; the icon carries the meaning (no amber, which reads as Warm).
    const val LIGHT_ATTENTION = LIGHT_INK
    const val LIGHT_ATTENTION_SURFACE = 0xFFFFFFFF
    // The second chart series and the UV line: a neutral grey, so the only blue on screen stays Action.
    const val LIGHT_SECONDARY_SERIES = 0xFF5A5A62

    // Dark: layered greys, never pure black. DARK_PAPER is the spec's dark "Background".
    const val DARK_PAPER = 0xFF121214
    const val DARK_SURFACE = 0xFF1C1C1F
    const val DARK_SURFACE_HIGH = 0xFF232327
    const val DARK_INK = 0xFFF2F2F4
    const val DARK_MUTED = 0xFFB3B3BB
    const val DARK_LINE = 0xFF34343A
    const val DARK_OUTLINE = 0xFF8A8A93
    const val DARK_ACTION = 0xFF7CB8FF
    const val DARK_ON_ACTION = 0xFF0B1220
    const val DARK_ACTION_CONTAINER = 0xFF16304D
    const val DARK_ON_ACTION_CONTAINER = 0xFFA6CDFF
    const val DARK_EMERGENCY = 0xFFFF9A9A
    const val DARK_ON_EMERGENCY = DARK_PAPER
    const val DARK_EMERGENCY_CONTAINER = 0xFF3A1C1C
    const val DARK_SUCCESS = 0xFF7FD6A0
    const val DARK_SUCCESS_CONTAINER = 0xFF14301F
    const val DARK_ATTENTION = DARK_INK
    const val DARK_ATTENTION_SURFACE = DARK_SURFACE_HIGH
    const val DARK_SECONDARY_SERIES = 0xFF9A9AA3

    // Heat ramp: lightness falls Normal -> Extreme so it still reads in greyscale.
    const val FILL_NORMAL = 0xFFE5E5EA          // a cool grey, so the neutrals no longer look like heat
    const val FILL_WARM = 0xFFF2C14E
    const val FILL_HOT = 0xFFEE8A3C
    const val FILL_VERY_HOT = 0xFFC2410C
    const val FILL_EXTREME = 0xFF6E1423
    const val ON_FILL_WHITE = 0xFFFFFFFF

    // Dark hero: the level fill at 16 % over DARK_PAPER, with content in a lifted level colour.
    const val DARK_HERO_FILL_ALPHA = 0.16
    const val DARK_LIFTED_NORMAL = 0xFFD1D1D6
    const val DARK_LIFTED_WARM = 0xFFF2C14E
    const val DARK_LIFTED_HOT = 0xFFF29A55
    const val DARK_LIFTED_VERY_HOT = 0xFFE5603A
    const val DARK_LIFTED_EXTREME = 0xFFFF8A80

    private fun lightFill(level: HeatLevel): Long = when (level) {
        HeatLevel.NORMAL -> FILL_NORMAL
        HeatLevel.WARM -> FILL_WARM
        HeatLevel.HOT -> FILL_HOT
        HeatLevel.VERY_HOT -> FILL_VERY_HOT
        HeatLevel.EXTREME -> FILL_EXTREME
    }

    private fun lifted(level: HeatLevel): Long = when (level) {
        HeatLevel.NORMAL -> DARK_LIFTED_NORMAL
        HeatLevel.WARM -> DARK_LIFTED_WARM
        HeatLevel.HOT -> DARK_LIFTED_HOT
        HeatLevel.VERY_HOT -> DARK_LIFTED_VERY_HOT
        HeatLevel.EXTREME -> DARK_LIFTED_EXTREME
    }

    /** Ribbon cell colour. Dark mode uses the lifted level colour so cells stay visible on the dark page. */
    fun fill(level: HeatLevel, dark: Boolean): Long = if (dark) lifted(level) else lightFill(level)

    /** Text on a light-mode fill: Ink for Normal, Warm and Hot; white for Very hot and Extreme. */
    fun onFill(level: HeatLevel): Long = if (level.atLeast(HeatLevel.VERY_HOT)) ON_FILL_WHITE else LIGHT_INK

    fun heroBackground(level: HeatLevel, dark: Boolean): Long =
        if (dark) over(lightFill(level), DARK_HERO_FILL_ALPHA, DARK_PAPER) else lightFill(level)

    fun heroContent(level: HeatLevel, dark: Boolean): Long = if (dark) lifted(level) else onFill(level)

    /** Opaque result of drawing [foreground] at [alpha] over [background], per sRGB channel. */
    fun over(foreground: Long, alpha: Double, background: Long): Long =
        listOf(16, 8, 0).fold(0xFF000000) { argb, shift ->
            val top = (foreground shr shift) and 0xFF
            val bottom = (background shr shift) and 0xFF
            argb or ((top * alpha + bottom * (1 - alpha)).roundToLong() shl shift)
        }
}
