package heatshield

import heatshield.domain.HeatLevel
import heatshield.ui.Palette
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// WCAG 2.x contrast for every text pair and control boundary in spec §7 and the palette spec
// (docs/superpowers/specs/2026-10-07-palette-turnagain-adaptation.md), computed from the same Long tokens the theme uses.
class ThemeContrastTest {
    private data class TextPair(val role: String, val foreground: Long, val background: Long, val minimum: Double)
    private class Tokens(
        val paper: Long, val surface: Long, val surfaceHigh: Long, val ink: Long, val muted: Long, val outline: Long,
        val action: Long, val onAction: Long, val actionContainer: Long, val onActionContainer: Long,
        val emergency: Long, val onEmergency: Long, val emergencyContainer: Long,
        val success: Long, val successContainer: Long,
        val attention: Long, val attentionSurface: Long, val secondarySeries: Long
    )

    private val light = with(Palette) {
        Tokens(LIGHT_PAPER, LIGHT_SURFACE, LIGHT_SURFACE_HIGH, LIGHT_INK, LIGHT_MUTED, LIGHT_OUTLINE,
            LIGHT_ACTION, LIGHT_ON_ACTION, LIGHT_ACTION_CONTAINER, LIGHT_ON_ACTION_CONTAINER,
            LIGHT_EMERGENCY, LIGHT_ON_EMERGENCY, LIGHT_EMERGENCY_CONTAINER,
            LIGHT_SUCCESS, LIGHT_SUCCESS_CONTAINER,
            LIGHT_ATTENTION, LIGHT_ATTENTION_SURFACE, LIGHT_SECONDARY_SERIES)
    }
    private val dark = with(Palette) {
        Tokens(DARK_PAPER, DARK_SURFACE, DARK_SURFACE_HIGH, DARK_INK, DARK_MUTED, DARK_OUTLINE,
            DARK_ACTION, DARK_ON_ACTION, DARK_ACTION_CONTAINER, DARK_ON_ACTION_CONTAINER,
            DARK_EMERGENCY, DARK_ON_EMERGENCY, DARK_EMERGENCY_CONTAINER,
            DARK_SUCCESS, DARK_SUCCESS_CONTAINER,
            DARK_ATTENTION, DARK_ATTENTION_SURFACE, DARK_SECONDARY_SERIES)
    }

    private fun channel(argb: Long, shift: Int): Double {
        val c = ((argb shr shift) and 0xFF).toDouble() / 255.0
        return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }
    private fun luminance(argb: Long) = 0.2126 * channel(argb, 16) + 0.7152 * channel(argb, 8) + 0.0722 * channel(argb, 0)
    private fun contrast(a: Long, b: Long): Double {
        val (lighter, darker) = listOf(luminance(a), luminance(b)).sortedDescending()
        return (lighter + 0.05) / (darker + 0.05)
    }
    private fun hex(argb: Long) = "#%06X".format(argb and 0xFFFFFF)
    private fun assertPairs(theme: String, pairs: List<TextPair>) {
        val failures = pairs.mapNotNull { pair ->
            val ratio = contrast(pair.foreground, pair.background)
            if (ratio >= pair.minimum) null
            else "$theme ${pair.role}: ${hex(pair.foreground)} on ${hex(pair.background)} = ${"%.2f".format(ratio)} < ${pair.minimum}"
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private fun textPairs(t: Tokens) = listOf(
        // Page, cards and the raised surface (dialogs, pickers, selected rows) all carry body and supporting text.
        TextPair("body on paper", t.ink, t.paper, BODY),
        TextPair("body on card", t.ink, t.surface, BODY),
        TextPair("body on high surface", t.ink, t.surfaceHigh, BODY),
        TextPair("muted on paper", t.muted, t.paper, BODY),
        TextPair("muted on card", t.muted, t.surface, BODY),
        TextPair("muted on high surface", t.muted, t.surfaceHigh, BODY),
        // Action: text buttons and links on every surface, the filled button, and the tonal button and selected chip.
        TextPair("text button on paper", t.action, t.paper, BODY),
        TextPair("text button on card", t.action, t.surface, BODY),
        TextPair("text button on high surface", t.action, t.surfaceHigh, BODY),
        TextPair("primary button label", t.onAction, t.action, BODY),
        TextPair("tonal button label", t.onActionContainer, t.actionContainer, BODY),
        TextPair("action text on selected surface", t.action, t.actionContainer, BODY),
        TextPair("body on selected surface", t.ink, t.actionContainer, BODY),
        // The on-break card is the Action container: its suggestion line is Muted, its unwell line Emergency.
        TextPair("muted on selected surface", t.muted, t.actionContainer, BODY),
        TextPair("emergency text on selected surface", t.emergency, t.actionContainer, BODY),
        // Emergency text: field errors, the record Delete text button (Step 22), Today's "Someone unwell? Heat first aid"
        // link (Task 8) and the top bar's "First aid" action (Task 11). The top bar and Today sit on Paper.
        TextPair("emergency text on paper", t.emergency, t.paper, BODY),
        TextPair("emergency text on card", t.emergency, t.surface, BODY),
        TextPair("emergency text on high surface", t.emergency, t.surfaceHigh, BODY),
        TextPair("Call 000 label", t.onEmergency, t.emergency, BODY),
        // The first-aid banner: emergency headings and body steps on the emergency container.
        TextPair("emergency heading on first-aid banner", t.emergency, t.emergencyContainer, BODY),
        TextPair("body on first-aid banner", t.ink, t.emergencyContainer, BODY),
        TextPair("success on success container", t.success, t.successContainer, BODY),
        TextPair("success on paper", t.success, t.paper, BODY),
        TextPair("success on card", t.success, t.surface, BODY),
        TextPair("body on success container", t.ink, t.successContainer, BODY),
        // Neutral notices: Attention is Ink and the icon carries the meaning, so these are body pairs.
        TextPair("attention on paper", t.attention, t.paper, BODY),
        TextPair("attention on card", t.attention, t.surface, BODY),
        TextPair("attention on attention surface", t.attention, t.attentionSurface, BODY),
        TextPair("body on attention surface", t.ink, t.attentionSurface, BODY),
        TextPair("second series value on paper", t.secondarySeries, t.paper, BODY)
    )

    @Test
    fun contrastHelperMatchesWcagReferenceValues() {
        assertEquals(21.0, contrast(0xFF000000, 0xFFFFFFFF), 0.01)
        assertEquals(1.0, contrast(0xFFF5F5F7, 0xFFF5F5F7), 0.0001)
        assertEquals(4.48, contrast(0xFF777777, 0xFFFFFFFF), 0.01)
    }

    @Test
    fun lightTextPairsMeetWcag() = assertPairs("light", textPairs(light))

    @Test
    fun darkTextPairsMeetWcag() = assertPairs("dark", textPairs(dark))

    @Test
    fun heroTextMeetsWcagAtEveryLevelInLight() {
        assertPairs("light", HeatLevel.entries.flatMap { level ->
            val content = Palette.heroContent(level, dark = false)
            val background = Palette.heroBackground(level, dark = false)
            listOf(
                TextPair("${level.name} hero numeral (88 sp)", content, background, LARGE),
                TextPair("${level.name} level word (28 sp)", content, background, LARGE),
                TextPair("${level.name} hero caption (17 sp)", content, background, BODY)
            )
        })
    }

    @Test
    fun darkHeroContentMeetsBodyContrastAtEveryLevel() {
        assertPairs("dark", HeatLevel.entries.map { level ->
            TextPair("${level.name} hero content", Palette.heroContent(level, dark = true),
                Palette.heroBackground(level, dark = true), BODY)
        })
    }

    @Test
    fun darkHeatCellsMeetNonTextContrastAgainstPaper() {
        assertPairs("dark", HeatLevel.entries.map { level ->
            TextPair("${level.name} ribbon cell", Palette.fill(level, dark = true), Palette.DARK_PAPER, NON_TEXT)
        })
    }

    @Test
    fun darkHeatColoursArePairwiseDistinct() {
        val ramp = HeatLevel.entries.map { it.name to Palette.fill(it, dark = true) }
        ramp.forEachIndexed { index, (level, colour) ->
            ramp.drop(index + 1).forEach { (otherLevel, otherColour) ->
                assertTrue("$level and $otherLevel share ${hex(colour)}", colour != otherColour)
            }
        }
    }

    // The palette spec's ink-on-fill and white-on-fill pairs for the ribbon cells and the light hero, at body size.
    @Test
    fun textOnEveryLightHeatFillMeetsWcag() {
        assertPairs("light", HeatLevel.entries.map { level ->
            TextPair("text on ${level.name} fill", Palette.onFill(level), Palette.fill(level, dark = false), BODY)
        })
    }

    // The ramp must still read in greyscale: each light fill is strictly darker than the one before it.
    @Test
    fun heatRampLuminanceFallsStrictlyFromNormalToExtreme() {
        val ramp = HeatLevel.entries.map { it.name to luminance(Palette.fill(it, dark = false)) }
        ramp.zipWithNext().forEach { (lower, higher) ->
            assertTrue("${higher.first} (${"%.3f".format(higher.second)}) is not darker than ${lower.first} " +
                "(${"%.3f".format(lower.second)})", higher.second < lower.second)
        }
    }

    // WCAG 1.4.11: a control's boundary, and a filled control against the page, needs 3:1.
    @Test
    fun controlBoundariesMeetNonTextContrast() {
        listOf("light" to light, "dark" to dark).forEach { (theme, t) ->
            assertPairs(theme, listOf(
                TextPair("control outline on paper", t.outline, t.paper, NON_TEXT),
                TextPair("control outline on card", t.outline, t.surface, NON_TEXT),
                TextPair("control outline on high surface", t.outline, t.surfaceHigh, NON_TEXT),
                TextPair("primary button fill on paper", t.action, t.paper, NON_TEXT),
                TextPair("primary button fill on card", t.action, t.surface, NON_TEXT),
                TextPair("Call 000 fill on paper", t.emergency, t.paper, NON_TEXT),
                TextPair("first-aid banner border on its fill", t.emergency, t.emergencyContainer, NON_TEXT)
            ))
        }
    }

    private companion object {
        const val BODY = 4.5
        const val LARGE = 3.0
        const val NON_TEXT = 3.0
    }
}
