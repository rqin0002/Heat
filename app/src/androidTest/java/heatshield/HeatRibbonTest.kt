package heatshield

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import heatshield.domain.BreakSpan
import heatshield.domain.HeatRules
import heatshield.domain.HourHeat
import heatshield.domain.SunProtection
import heatshield.ui.Formats
import heatshield.ui.HeatRibbon
import heatshield.ui.HeatShieldTheme
import heatshield.ui.RibbonKeyHatch
import heatshield.ui.RibbonKeyUv
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Heat ribbon semantics: one spoken node per hour, the crew-rule clause, hours with no forecast, and the 200 % font
 * reflow (Review Focus 4).
 */
@RunWith(AndroidJUnit4::class)
class HeatRibbonTest {
    @get:Rule val compose = createComposeRule()
    private val day = LocalDate.of(2026, 1, 9)
    private val use24Hour = DateFormat.is24HourFormat(InstrumentationRegistry.getInstrumentation().targetContext)

    /**
     * A 7 am to 3 pm shift climbing from Normal to Extreme. Each comment gives the whole number shown (rounded half up),
     * the level judged on it (Normal ≤ 29 < Warm ≤ 31 < Hot ≤ 34 < Very hot ≤ 37 < Extreme) and the whole air degrees
     * against the 35° rule. Air reaches 35° from 12 pm, so hours 12, 13 and 14 are hatched.
     */
    private val shift = listOf(
        hour(7, heat = 24.2, air = 24.2),   // 24 → Normal; air 24, under
        hour(8, heat = 27.1, air = 27.1),   // 27 → Normal; air 27, under
        hour(9, heat = 30.6, air = 30.6),   // 30.6 rounds to 31 → Warm; air 31, under
        hour(10, heat = 31.5, air = 31.0),  // 31.5 rounds half up to 32 → Hot; air 31, under
        hour(11, heat = 34.4, air = 34.1),  // 34 → Hot; air 34, under
        hour(12, heat = 36.2, air = 34.5),  // 36 → Very hot; air 34.5 rounds half up to 35, over
        hour(13, heat = 38.4, air = 37.0),  // 38 → Extreme; air 37, over
        hour(14, heat = 36.8, air = 35.9)   // 36.8 rounds to 37 → Very hot; air 36, over
    )

    @Test
    fun drawsOneSpokenNodePerShiftHour() {
        show(shift)
        compose.onNodeWithTag("heat_ribbon").assertIsDisplayed()
        compose.onNodeWithTag("heat_hour_list").assertDoesNotExist()
        compose.onAllNodes(tagged("ribbon_hour_")).assertCountEquals(8)
        (7..14).forEach { compose.onNodeWithTag("ribbon_hour_" + it.toString().padStart(2, '0')).assertExists() }
        compose.onNodeWithTag("ribbon_hour_07").assertContentDescriptionEquals("${t(7)} to ${t(8)}, Normal, 24 degrees")
        compose.onNodeWithTag("ribbon_hour_09").assertContentDescriptionEquals("${t(9)} to ${t(10)}, Warm, 31 degrees")
        compose.onNodeWithTag("ribbon_hour_10").assertContentDescriptionEquals("${t(10)} to ${t(11)}, Hot, 32 degrees")
        compose.onNodeWithTag("ribbon_hour_11").assertContentDescriptionEquals("${t(11)} to ${t(12)}, Hot, 34 degrees")
    }

    @Test
    fun hatchedHoursNameTheCrewRule() {
        show(shift)
        compose.onNodeWithTag("ribbon_hour_13")
            .assertContentDescriptionEquals("${t(13)} to ${t(14)}, Extreme, 38 degrees, over your 35 degree rule")
        compose.onNodeWithTag("ribbon_hour_12")
            .assertContentDescriptionEquals("${t(12)} to ${t(13)}, Very hot, 36 degrees, over your 35 degree rule")
        compose.onNodeWithTag("ribbon_hour_11").assert(SemanticsMatcher("no crew-rule clause") { node ->
            node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().none { "rule" in it }
        })
    }

    @Test
    fun nowHourIsMarkedInsideTheShift() {
        show(shift, now = day.atTime(10, 20))
        compose.onNodeWithTag("ribbon_hour_10")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Now"))
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.StateDescription)).assertCountEquals(1)
    }

    @Test
    fun nowOutsideTheShiftMarksNoHour() {
        show(shift, now = day.atTime(16, 30))
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.StateDescription)).assertCountEquals(0)
    }

    @Test
    fun doubleFontReflowsToAnHourListWithoutTheCanvas() {
        show(shift, fontScale = 2f)
        compose.onNodeWithTag("heat_hour_list").assertIsDisplayed()
        compose.onNodeWithTag("heat_ribbon").assertDoesNotExist()
        compose.onAllNodes(tagged("ribbon_hour_")).assertCountEquals(0)
        compose.onAllNodes(tagged("hour_row_")).assertCountEquals(8)
        compose.onNodeWithTag("hour_row_13").assertContentDescriptionContains("over your 35 degree rule", substring = true)
        compose.onNodeWithTag("hour_row_14").performScrollTo().assertIsDisplayed()
    }

    /** With no forecast hours Today shows its forecast card, so a shift of missing hours alone draws nothing either. */
    @Test
    fun emptyHoursRenderNothing() {
        show(emptyList(), missing = (7..14).map { day.atTime(it, 0) })
        compose.onNodeWithTag("heat_ribbon").assertDoesNotExist()
        compose.onNodeWithTag("heat_hour_list").assertDoesNotExist()
        compose.onAllNodes(tagged("ribbon_missing_")).assertCountEquals(0)
    }

    /**
     * Coverage: the forecast stops after 11 am in a 7 am to 3 pm shift. Hours 12, 13 and 14 are drawn as empty
     * "No forecast" cells after the forecast hours, so the ribbon never passes off five hours as the whole shift.
     */
    @Test
    fun shiftHoursPastTheForecastDrawAsNoForecastCells() {
        show(shift.take(5), now = day.atTime(12, 40), missing = listOf(12, 13, 14).map { day.atTime(it, 0) })
        compose.onNodeWithTag("heat_ribbon").assertIsDisplayed()
        compose.onAllNodes(tagged("ribbon_hour_")).assertCountEquals(5)
        compose.onAllNodes(tagged("ribbon_missing_")).assertCountEquals(3)
        compose.onNodeWithTag("ribbon_missing_12")
            .assertContentDescriptionEquals("${t(12)} to ${t(13)}, No forecast")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Now"))
        compose.onNodeWithTag("ribbon_missing_14").assertContentDescriptionEquals("${t(14)} to ${t(15)}, No forecast")
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.StateDescription)).assertCountEquals(1)
        assertTrue("missing hours must follow 11 am", left("ribbon_hour_11") < left("ribbon_missing_12"))
    }

    /** A gap inside the shift: the missing hours sit in their own time slots, so 12 pm stays under 12 pm. */
    @Test
    fun aGapInsideTheShiftKeepsTheHoursInOrder() {
        show(shift.filter { it.time.hour !in 10..11 }, missing = listOf(day.atTime(10, 0), day.atTime(11, 0)))
        compose.onAllNodes(tagged("ribbon_hour_")).assertCountEquals(6)
        compose.onAllNodes(tagged("ribbon_missing_")).assertCountEquals(2)
        compose.onNodeWithTag("ribbon_missing_10").assertContentDescriptionEquals("${t(10)} to ${t(11)}, No forecast")
        compose.onNodeWithTag("ribbon_hour_12")
            .assertContentDescriptionEquals("${t(12)} to ${t(13)}, Very hot, 36 degrees, over your 35 degree rule")
        val lefts = listOf("ribbon_hour_09", "ribbon_missing_10", "ribbon_missing_11", "ribbon_hour_12").map(::left)
        assertTrue("cells out of time order: $lefts", lefts.zipWithNext().all { (a, b) -> a < b })
    }

    @Test
    fun doubleFontListsMissingHoursAsNoForecast() {
        show(shift.take(5), missing = listOf(12, 13, 14).map { day.atTime(it, 0) }, fontScale = 2f)
        compose.onNodeWithTag("heat_hour_list").assertIsDisplayed()
        compose.onAllNodes(tagged("hour_row_")).assertCountEquals(5)
        compose.onAllNodes(tagged("missing_row_")).assertCountEquals(3)
        compose.onNodeWithTag("missing_row_12").assertContentDescriptionEquals("${t(12)} to ${t(13)}, No forecast")
        compose.onNodeWithTag("missing_row_14").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun oneDegreeIsSingular() {
        show(listOf(hour(7, heat = 0.5, air = 0.5)))  // 0.5 rounds half up to 1 → Normal; truncating would say "0 degrees"
        compose.onNodeWithTag("ribbon_hour_07").assertContentDescriptionEquals("${t(7)} to ${t(8)}, Normal, 1 degree")
    }

    /** The Today keys that tie the hatch and the UV line to their lines: silent to TalkBack. */
    @Test
    fun ribbonKeysStaySilent() {
        compose.setContent {
            HeatShieldTheme(darkTheme = false) {
                Row {
                    RibbonKeyHatch(Modifier.testTag("key_hatch"))
                    RibbonKeyUv(Modifier.testTag("key_uv"))
                }
            }
        }
        compose.onNodeWithTag("key_hatch").assertIsDisplayed()
        compose.onNodeWithTag("key_uv").assertIsDisplayed()
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription)).assertCountEquals(0)
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text)).assertCountEquals(0)
    }

    @Test
    fun breakNotchesAreListedAtLargeFont() {
        val breaks = listOf(heatshield.domain.BreakSpan(heatshield.domain.ShiftRules.instant(day.atTime(10, 2)), heatshield.domain.ShiftRules.instant(day.atTime(10, 17))))
        show(shift, fontScale = 2f, breaks = breaks)
        compose.onNodeWithText("Break ${Formats.time(LocalTime.of(10, 2), use24Hour)} – ${Formats.time(LocalTime.of(10, 17), use24Hour)}").assertExists()
        show(shift, breaks = breaks)
        compose.onNodeWithTag("heat_ribbon").assertIsDisplayed()   // the notch is drawn; hour semantics are unchanged
        compose.onNodeWithTag("ribbon_hour_10").assertContentDescriptionEquals("${t(10)} to ${t(11)}, Hot, 32 degrees")
    }

    /** No personal factors, so effective = base. The level and the hatch follow the whole-degree rule, as Task 5 sets them. */
    private fun hour(h: Int, heat: Double, air: Double): HourHeat {
        val level = HeatRules.baseLevel(heat)
        return HourHeat(
            time = day.atTime(h, 0), airC = air, humidityPct = 35.0, windMs = 2.0, feelsLikeC = heat - 0.4,
            heat = heat, base = level, effective = level, overTrigger = HeatRules.wholeDegrees(air) >= 35.0,
            uv = 8.0, uvForProtection = 8.0
        )
    }

    private fun t(h: Int) = Formats.time(LocalTime.of(h, 0), use24Hour)

    private fun tagged(prefix: String) = SemanticsMatcher("test tag starts with $prefix") {
        it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true
    }

    /** The left edge of the tagged node, so tests can check that cells run in time order. */
    private fun left(tag: String) = compose.onNodeWithTag(tag).getBoundsInRoot().left

    private class Shown(val hours: List<HourHeat>, val now: LocalDateTime?, val fontScale: Float, val missing: List<LocalDateTime>,
                        val breaks: List<BreakSpan>)
    private var shown by mutableStateOf<Shown?>(null)

    /**
     * Renders on a 360 dp phone width with a 16 dp gutter, at the given font scale. [missing] stands in for Task 5's
     * TodayHeat.coverage.missing: shift hours with no forecast hour. setContent runs once per test, so a second call
     * swaps what is shown. The content reads the app's English resources, as MainActivity does, because this test
     * activity would otherwise follow the phone's language: on an Indonesian phone "1 degree" reads "1 degrees" and
     * "7 am" reads "07:00".
     */
    private fun show(hours: List<HourHeat>, now: LocalDateTime? = null, fontScale: Float = 1f,
                     missing: List<LocalDateTime> = emptyList(), breaks: List<BreakSpan> = emptyList()) {
        val next = Shown(hours, now, fontScale, missing, breaks)
        if (shown != null) { compose.runOnIdle { shown = next }; return }
        shown = next
        compose.setEnglishContent {
            val current = shown ?: return@setEnglishContent
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, current.fontScale)) {
                HeatShieldTheme(darkTheme = false) {
                    Column(Modifier.width(360.dp).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                        HeatRibbon(current.hours, current.now, triggerC = 35.0,
                            sunProtection = SunProtection(LocalTime.of(10, 0), LocalTime.of(16, 0)), missing = current.missing,
                            breaks = current.breaks)
                    }
                }
            }
        }
    }
}
