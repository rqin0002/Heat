package heatshield

import android.content.Context
import android.util.TypedValue
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import heatshield.ui.Palette
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The colours Android draws before Compose (window background, accent) and outside the app (the first-aid launcher
 * shortcut) live in XML, which cannot read [Palette]. These pin each one to its token, so the palette has one source.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class PaletteResourcesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun hex(argb: Int) = "#%08X".format(argb)
    private fun assertColour(expected: Long, actual: Int, what: String) = assertEquals(what, hex(expected.toInt()), hex(actual))

    /** The theme's resolved value for a colour attribute such as android:windowBackground. */
    private fun themeColour(attr: Int): Int {
        val theme = context.resources.newTheme().apply { applyStyle(R.style.Theme_HeatShield, true) }
        val value = TypedValue()
        theme.resolveAttribute(attr, value, true)
        return if (value.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT) value.data else context.getColor(value.resourceId)
    }

    @Test
    fun lightWindowColoursMatchPaperAndAction() {
        assertColour(Palette.LIGHT_PAPER, context.getColor(R.color.paper_window), "paper_window")
        assertColour(Palette.LIGHT_ACTION, context.getColor(R.color.action_window), "action_window")
        assertColour(Palette.LIGHT_PAPER, themeColour(android.R.attr.windowBackground), "windowBackground")
        assertColour(Palette.LIGHT_ACTION, themeColour(android.R.attr.colorAccent), "colorAccent")
    }

    @Test
    @Config(qualifiers = "night")
    fun darkWindowColoursMatchPaperAndAction() {
        assertColour(Palette.DARK_PAPER, context.getColor(R.color.paper_window), "paper_window")
        assertColour(Palette.DARK_ACTION, context.getColor(R.color.action_window), "action_window")
        assertColour(Palette.DARK_PAPER, themeColour(android.R.attr.windowBackground), "windowBackground")
        assertColour(Palette.DARK_ACTION, themeColour(android.R.attr.colorAccent), "colorAccent")
    }

    // The launcher draws the shortcut on its own background, so the icon keeps the light Emergency red in both themes.
    @Test
    fun firstAidShortcutIsEmergencyRed() {
        assertColour(Palette.LIGHT_EMERGENCY, context.getColor(R.color.first_aid_shortcut), "first_aid_shortcut")
    }

    @Test
    @Config(qualifiers = "night")
    fun firstAidShortcutStaysEmergencyRedInDarkMode() {
        assertColour(Palette.LIGHT_EMERGENCY, context.getColor(R.color.first_aid_shortcut), "first_aid_shortcut")
    }
}
