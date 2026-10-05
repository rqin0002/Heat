package heatshield

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import heatshield.data.ForecastHour
import heatshield.data.ForecastSnapshot
import heatshield.data.Freshness
import heatshield.data.MelbourneZone
import heatshield.domain.BreakRule
import heatshield.domain.ContextReasoner
import heatshield.domain.Coverage
import heatshield.domain.CrewRuleLine
import heatshield.domain.HeatLevel
import heatshield.domain.HeatProfile
import heatshield.domain.HeatRules
import heatshield.domain.HourHeat
import heatshield.domain.NextStep
import heatshield.domain.OpenAnswerKind
import heatshield.domain.PersonalFactors
import heatshield.domain.SourceLabel
import heatshield.domain.SunProtection
import heatshield.domain.TodayHeat
import heatshield.domain.UvCategory
import heatshield.ui.Formats
import heatshield.ui.HeatShieldTheme
import heatshield.ui.ShiftAction
import heatshield.ui.ShiftUiState
import heatshield.ui.TodayContent
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Today as a gardener sees it, from fixed TodayHeat values, and for Review Focus 1 from the real reasoner.
 * No network, Room or wall clock is involved.
 */
@RunWith(AndroidJUnit4::class)
class TodayContentTest {
    @get:Rule val compose = createComposeRule()

    private val date = LocalDate.of(2026, 1, 9)
    private val nowMillis = millisAt(10)
    private val use24Hour = DateFormat.is24HourFormat(InstrumentationRegistry.getInstrumentation().targetContext)
    private val sites = listOf("Birrarung Marr", "Carlton Gardens", "Royal Park")
    private val profile = HeatProfile(site = "Birrarung Marr", taskName = "Digging & planting",
        shiftStartMinute = 420, shiftEndMinute = 900, triggerC = 35.0, factors = PersonalFactors())
    private val whyFooter =
        "Open-Meteo forecast for inner Melbourne, shared by all three parks. Limits and all sources: Profile › About & sources."
    // An answer still standing on the open shift; with no reminder time its line is the answer itself.
    private val cantStop = OpenAnswerKind.CANT_STOP
    private val stopLine = "Stop heavy work outdoors. Check your crew's hot-day rule."
    private val keepWorking = "If your crew keeps working, take breaks in the shade and follow your crew's rule."
    private val selected = mutableListOf<String>()
    private var refreshing by mutableStateOf(false)
    private var refreshes = 0
    private var previews = 0
    private var plans = 0
    private var breaks = 0
    private var breakSheets = 0
    private var firstAid = 0

    // Air temperatures for the hours 7 am to 2 pm of a 7 am – 3 pm shift. Unless a test says otherwise, the 10 am hour
    // (index 3) is "now". By default the heat number H equals air. Every expected level and numeral is derived from
    // whole degrees (D1): a level is judged on HeatRules.wholeDegrees(H), rounded half up (Normal ≤ 29, Warm 30–31,
    // Hot 32–34, Very hot 35–37, Extreme ≥ 38), Formats.temperature prints that same number, and an hour counts against
    // the 35° crew rule when wholeDegrees(air) >= 35. The peak is the first hour with the highest raw H.

    // Whole degrees 18 19 21 22 23 24 24 23: all Normal. Peak 24.0 at 12 pm (the first 24): "24°"; now 22°.
    // No hour is Hot even for heavy work, and air never shows 35°: no cut-off, no crew hours.
    private val coolDay = listOf(18.0, 19.0, 21.0, 22.0, 23.0, 24.0, 24.0, 23.0)
    // Whole degrees 24 26 28 30 31 31 31 29: Normal to 9 am, Warm from 10 am. Peak 31.0 at 12 pm (both 30.5s also
    // show 31° but are lower): Warm "31°"; now 30°. Heavy work adds one level, so the hero reads "Hot" "for you".
    // Cut-off 10 am: the first Warm hour, which is Hot for heavy work. Air never shows 35°.
    private val warmDay = listOf(24.0, 26.0, 28.0, 30.0, 30.5, 31.0, 30.5, 29.0)
    // Whole degrees 26 29 32 34 36 37 36 33: Normal to 8 am, Hot from 9 am, Very hot 11 am to 1 pm. Peak 36.5 at
    // 12 pm rounds half up: Very hot "37°"; now 34°. Air shows 35° or more at 11 am, 12 pm and 1 pm: 3 h from 11 am.
    // Cut-off 9 am, the first Hot hour.
    private val veryHotDay = listOf(26.0, 29.0, 32.0, 34.0, 35.5, 36.5, 36.0, 33.0)
    // Whole degrees 33 36 39 41 43 42 40 38: Hot at 7 am, Very hot at 8 am, Extreme from 9 am. Peak 42.9 at 11 am:
    // Extreme "43°"; now 41°, already Extreme. Air shows 35° or more from 8 am: 7 h. 7 am is already Hot, so heavy
    // jobs are cut off from the start of the shift. suggestedCadence(EXTREME) is null (A8): no work/break split.
    private val extremeDay = listOf(33.0, 36.0, 39.0, 41.0, 42.9, 42.0, 40.0, 38.0)
    // Whole degrees 27 29 33 36 39 39 37 34: Normal at 7 and 8 am, Hot at 9 am, Very hot at 10 am, Extreme at 11 am
    // (38.5 shows 39°) and 12 pm. Peak 39.0 at 12 pm: Extreme "39°". Read at 8:20, now is 29°, Normal. Air shows 35°
    // or more from 10 am to 1 pm: 4 h. Cut-off 9 am, the first Hot hour (Normal hours are not raised for heavy work).
    private val lateExtremeDay = listOf(27.0, 29.0, 33.0, 36.0, 38.5, 39.0, 37.0, 34.0)
    // With feels-like 5.2° above air, H is 33.2 34.2 35.2 36.2 37.2 38.2 37.7 36.2,
    // so whole degrees 33 34 35 36 37 38 38 36: Hot to 8 am, Very hot 9 to 11 am, Extreme at 12 and 1 pm.
    // Peak 38.2 at 12 pm: Extreme "38°"; now 36°, Very hot, so the stop line is timed from 12 pm. Air peaks at
    // 33.0 and shows "33°", so no hour reaches the 35° rule.
    private val humidDay = listOf(28.0, 29.0, 30.0, 31.0, 32.0, 33.0, 32.5, 31.0)
    // Whole degrees 25 27 29 30 31 32 31 30. Peak exactly 31.5 at 12 pm, which shows 32° and so is Hot: "Hot 32°",
    // never "Warm 32°". Now 30°.
    private val hotEdgeDay = listOf(25.0, 27.0, 29.0, 30.0, 31.0, 31.5, 31.2, 29.5)
    // Whole degrees 27 29 31 32 34 35 34 32. Air 34.5 at 12 pm shows 35° and so reaches the 35° rule; 34.4 at 1 pm
    // shows 34° and does not: 1 h from 12 pm. Peak 34.5: Very hot "35°"; now 32°.
    private val nearRuleDay = listOf(27.0, 29.0, 31.0, 32.0, 33.8, 34.5, 34.4, 32.0)

    @Test
    fun normalDayShowsTheQuietHeroLinesAndPreviewRow() {
        show(day(coolDay))
        hero("Normal", "24°", "Shift peak ${at(12)} · now 22°")
        compose.onNodeWithTag("hero_numeral_label", useUnmergedTree = true).assertTextEquals("air in shade")
        compose.onNodeWithTag("hero_qualifier", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("today_extreme").assertDoesNotExist()
        line("today_line_crew", "Air forecast to stay under 35° during your shift")
        line("today_line_heavy", "No heavy-job cut-off today")
        compose.onNodeWithTag("today_line_plan").assertDoesNotExist()
        line("today_line_uv", "UV High · sun protection ${at(10)} – ${at(16)}")
        compose.onNodeWithTag("today_line_cadence").assertDoesNotExist()
        compose.onNodeWithTag("today_line_next").assertDoesNotExist()
        // A forecast for inner Melbourne, not a reading at this park: the stamp says whose forecast it is (A8).
        line("today_line_updated", "Open-Meteo forecast for inner Melbourne · updated 14 min ago")
        line("today_preview_row", "Normal heat today. Preview a hot day").performClick()
        compose.onNodeWithTag("today_unwell").performScrollTo().assertTextEquals("Someone unwell? Heat first aid").performClick()
        compose.onNode(hasText("Plan work windows") and hasClickAction()).performScrollTo().performClick()
        compose.onNodeWithTag("shift_start").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(1, 1, 1, 1), listOf(previews, firstAid, plans, breaks)) }
    }

    @Test
    fun bumpedHotDaySaysForYouAndSuggestsACadence() {
        show(day(warmDay, heavyWork = true, refreshedBy = "background"))
        hero("Hot", "31°", "Shift peak ${at(12)} · now 30°")
        compose.onNodeWithTag("hero_qualifier", useUnmergedTree = true).assertTextEquals("for you")
        compose.onNodeWithTag("today_extreme").assertDoesNotExist()
        line("today_line_crew", "Air forecast to stay under 35° during your shift")
        // 10:20 is past the 10 am cut-off, so the line no longer advises a time that has gone.
        line("today_line_heavy", "Heavy jobs were best before ${at(10)}")
        line("today_line_cadence", "Suggested at the peak: 45 min work, 15 min break in shade")
        line("today_line_updated", "Open-Meteo forecast for inner Melbourne · updated 14 min ago (background)")
        compose.onNodeWithTag("today_preview_row").assertDoesNotExist()
        compose.onNodeWithTag("today_stale").assertDoesNotExist()
    }

    @Test
    fun veryHotDayCountsTheCrewRuleHoursOnAir() {
        show(day(veryHotDay))
        hero("Very hot", "37°", "Shift peak ${at(12)} · now 34°")
        compose.onNodeWithTag("today_extreme").assertDoesNotExist()
        // The crew and UV lines lead with silent ribbon keys, so each line's merged text is the line alone.
        line("today_line_crew", "Air forecast to reach 35° from ${at(11)} (3 h of your shift)")
        line("today_line_uv", "UV High · sun protection ${at(10)} – ${at(16)}")
        line("today_line_heavy", "Heavy jobs were best before ${at(9)}")
        line("today_line_cadence", "If work goes ahead: 30 min work, 15 min break in shade")
        compose.onNodeWithTag("today_preview_row").assertDoesNotExist()
    }

    // Spec §5.10 and §12, A8: at Extreme the app says stop heavy work outdoors, and offers no work/break split.
    @Test
    fun extremeDaySaysStopHeavyWorkOutdoors() {
        show(day(extremeDay))
        hero("Extreme", "43°", "Shift peak ${at(11)} · now 41°")
        // Extreme from 9 am, and 10 am is already Extreme, so the instruction applies now and carries no time.
        line("today_extreme", stopLine)
        assertTrue("The Extreme line sits directly under the hero",
            top(compose.onNodeWithTag("heat_hero")) < top(compose.onNodeWithTag("today_extreme")) &&
                top(compose.onNodeWithTag("today_extreme")) < top(compose.onNodeWithTag("heat_ribbon")))
        line("today_line_crew", "Air forecast to reach 35° from ${at(8)} (7 h of your shift)")
        // 7 am, the first shift hour, is already Hot (and Very hot for heavy work).
        line("today_line_heavy", "Hot for heavy work, or over your rule, from the start of your shift")
        line("today_line_cadence", keepWorking)
        compose.onAllNodes(hasText("min work", substring = true)).assertCountEquals(0)
        compose.onNodeWithTag("today_preview_row").assertDoesNotExist()
        compose.onNodeWithTag("today_extreme").performScrollTo().performClick()
        // A7: the stop line is HeatShield's own rule; the workplace rule it borrows its 38° step from is named in the text.
        assertEquals(listOf(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.getString(heatshield.ui.sourceLabelResource(SourceLabel.HEATSHIELD_RULE))), sourceLabelsIn("why_row_extreme"))
        compose.onNodeWithTag("why_row_extreme").assertTextContains("Banyule", substring = true)
    }

    @Test
    fun extremeLaterInTheShiftSaysFromWhenAndHeavyJobsBefore() {
        show(day(lateExtremeDay, nowHour = 8), now = millisAt(8))
        hero("Extreme", "39°", "Shift peak ${at(12)} · now 29°")
        // 8 am is Normal: the morning is not told to stop yet, only from when.
        line("today_extreme", "From ${at(11)}: stop heavy work outdoors. Check your crew's hot-day rule.")
        line("today_line_heavy", "Heavy jobs before ${at(9)}")
        line("today_line_crew", "Air forecast to reach 35° from ${at(10)} (4 h of your shift)")
        line("today_line_cadence", keepWorking)
    }

    // A still, humid shift: the hero reads Extreme while air stays under the rule, and both say what they measure.
    @Test
    fun humidDayNamesTheFeelsLikeNumeralAndKeepsTheRuleOnAir() {
        show(day(humidDay, feelsAbove = 5.2))
        hero("Extreme", "38°", "Shift peak ${at(12)} · now 36°")
        compose.onNodeWithTag("hero_numeral_label", useUnmergedTree = true).assertTextEquals("feels like in shade · air 33°")
        line("today_line_crew", "Air forecast to stay under 35° during your shift")
        line("today_extreme", "From ${at(12)}: stop heavy work outdoors. Check your crew's hot-day rule.")
        assertEveryDegreeHasAWhyRow { compose.onNodeWithTag("hero_numeral", useUnmergedTree = true).performClick() }
        compose.onNodeWithTag("why_row_feels_like").assertTextContains("Feels like 38° in shade · air 33° at ${at(12)}")
    }

    // Whole degrees: a heat number of 31.5 shows as 32° and is judged as 32, so the level and the digits agree.
    @Test
    fun levelBoundaryShowsTheDigitsOfItsOwnLevel() {
        show(day(hotEdgeDay))
        hero("Hot", "32°", "Shift peak ${at(12)} · now 30°")
        compose.onNodeWithTag("hero_numeral", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("why_row_level").assertTextContains("Hot at a heat number of 32°")
    }

    // Whole degrees: air that shows the rule's number reaches the rule, and air that shows one degree less does not.
    @Test
    fun airShownAtTheRuleCountsAsReachingIt() {
        show(day(nearRuleDay))
        hero("Very hot", "35°", "Shift peak ${at(12)} · now 32°")
        line("today_line_crew", "Air forecast to reach 35° from ${at(12)} (1 h of your shift)")
        compose.onNodeWithTag("today_line_crew").performScrollTo().performClick()
        compose.onNodeWithTag("why_row_feels_like").assertTextContains("air 35°", substring = true)
    }

    @Test
    fun staleForecastSaysHowOldAndRetryCallsBack() {
        show(day(veryHotDay, freshness = Freshness.STALE, updatedMinutesAgo = 7 * 60 + 5,
            error = "Forecast could not be reached. Check your connection and retry."))
        compose.onNodeWithTag("heat_hero").assertExists()
        compose.onNodeWithTag("today_line_cadence").assertDoesNotExist()
        compose.onNodeWithText("Forecast is 7 h old, so today's numbers may have changed.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Forecast could not be reached. Check your connection and retry.").assertExists()
        assertTrue("The age warning sits above the numbers it qualifies",
            top(compose.onNodeWithTag("today_stale")) < top(compose.onNodeWithTag("heat_hero")))
        compose.onNodeWithTag("today_unwell").performScrollTo().performClick()
        compose.onNode(hasText("Retry") and hasClickAction()).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(1, 1), listOf(refreshes, firstAid)) }
    }

    @Test
    fun staleWhySheetExplainsWithheldCadenceWithoutCallingTheDayNormal() {
        show(day(veryHotDay, freshness = Freshness.STALE, updatedMinutesAgo = 7 * 60))
        compose.onNodeWithTag("hero_numeral", useUnmergedTree = true).performScrollTo().performClick()
        compose.onNodeWithTag("why_row_cadence").performScrollTo()
            .assertTextContains("Break suggestions need a current forecast.", substring = true)
        compose.onNodeWithText("No break suggestion at Normal heat").assertDoesNotExist()
    }

    /**
     * Spec §5.13 (from M3): a stale forecast times no break and nudges no start, so with break-due alerts on Today says they
     * pause, after how old the forecast is; with alerts off it says only the age.
     */
    @Test
    fun staleForecastSaysBreakAlertsPauseWhenTheyAreOn() {
        val on = heatshield.background.AlertStatus(alertsOn = true, notificationsAllowed = true, exactAllowed = true, breakChannelSilent = false, asked = true)
        show(day(veryHotDay, freshness = Freshness.STALE, updatedMinutesAgo = 7 * 60 + 5), alertStatus = on)
        compose.onNodeWithText("Forecast is 7 h old, so today's numbers may have changed. Break alerts are paused until it updates.")
            .performScrollTo().assertIsDisplayed()
        // With alerts off the age alone: staleForecastSaysHowOldAndRetryCallsBack matches that exact text.
    }

    @Test
    fun aForecastNotCoveringTodaySaysBreakAlertsPauseWhenTheyAreOn() {
        val on = heatshield.background.AlertStatus(alertsOn = true, notificationsAllowed = true, exactAllowed = true, breakChannelSilent = false, asked = true)
        show(reasoned((0..23).toList(), day = date.plusDays(1)), alertStatus = on)
        compose.onNodeWithText("This forecast doesn't cover today. Break alerts are paused until it updates.").assertIsDisplayed()
    }

    @Test
    fun agingForecastStillOffersItsQualifiedCadence() {
        show(day(veryHotDay, freshness = Freshness.AGING, updatedMinutesAgo = 90))
        compose.onNodeWithTag("today_line_cadence").performScrollTo().assertExists()
    }

    // Review Focus 1, through the real reasoner: a cache that holds only tomorrow's hours.
    @Test
    fun tomorrowOnlyForecastSaysItDoesNotCoverToday() {
        show(reasoned((0..23).toList(), day = date.plusDays(1)))
        compose.onNodeWithTag("today_stale").assertExists()
        compose.onNodeWithText("This forecast doesn't cover today.").assertIsDisplayed()
        compose.onNodeWithTag("heat_hero").assertDoesNotExist()
        compose.onNodeWithTag("today_unwell").performScrollTo().assertIsDisplayed()
        compose.onNode(hasText("Retry") and hasClickAction()).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, refreshes) }
    }

    // Review Focus 1 and A3: the forecast ends with the 11 am hour, inside a 7 am – 3 pm shift. It is fresh, but it
    // covers the shift only to 12 pm, so no line speaks for the whole shift.
    // Air 25.5 26.0 26.5 27.0 27.5 at 7–11 am (22 + h/2), humidity 40 %, wind 2 m/s: feels like stays below air
    // (27.5 gives about 26.9), so H is the air. Whole degrees 26 26 27 27 28: all Normal, and Normal is never raised
    // for heavy work, so nothing sets a cut-off. Peak 27.5 at 11 am: "28°"; now (10 am) 27°. Air never shows 35°.
    // UV is 5 (Moderate) from 10 am and 1 before, so sun protection runs from 10 am to the end of the forecast.
    @Test
    fun forecastEndingMidShiftSpeaksOnlyForTheCoveredHours() {
        show(reasoned((0..11).toList()))
        hero("Normal", "28°", "Peak in available forecast · ${at(11)} · now 27°")
        compose.onAllNodes(tagged("ribbon_hour_")).assertCountEquals(5)
        compose.onAllNodes(tagged("ribbon_missing_")).assertCountEquals(3)
        compose.onNodeWithTag("today_stale").assertDoesNotExist()
        line("today_line_crew", "Available hours are below 35°; some shift hours are missing")
        line("today_line_heavy", "No cut-off found in available hours; some shift hours are missing")
        line("today_line_uv", "UV Moderate in available hours · some UV hours are missing; use sun protection for outdoor work")
        compose.onAllNodes(hasText("during your shift", substring = true)).assertCountEquals(0)
    }

    @Test
    fun internalGapNeverBecomesAnUntilAssurance() {
        show(reasoned((0..23).filterNot { it == 10 || it == 11 }))
        line("today_line_crew", "Available hours are below 35°; some shift hours are missing")
        line("today_line_heavy", "No cut-off found in available hours; some shift hours are missing")
        compose.onNodeWithTag("ribbon_missing_10").assertExists()
        compose.onNodeWithTag("ribbon_missing_11").assertExists()
        line("today_line_uv", "UV Moderate in available hours · some UV hours are missing; use sun protection for outdoor work")
    }

    @Test
    fun completeShiftDoesNotHideMissingUvOutsideTheShift() {
        // Shift 07–15 is complete; 18:00 UV is absent. UV still cannot claim full-day coverage.
        show(reasoned((0..23).filterNot { it == 18 }))
        line("today_line_crew", "Air forecast to stay under 35° during your shift")
        line("today_line_uv", "UV Moderate in available hours · some UV hours are missing; use sun protection for outdoor work")
    }

    @Test
    fun breakKeepsOpenAnswerAndIndependentExtremeAdvice() {
        compose.setEnglishContent {
            HeatShieldTheme {
                Column { heatshield.ui.BreakHeatAdvice(day(extremeDay), NextStep.OpenAnswer(cantStop)) }
            }
        }
        compose.onNodeWithTag("break_open_answer").assertTextEquals("You said: Can't stop yet")
        compose.onNodeWithTag("break_extreme").assertExists()
        compose.onNodeWithTag("break_extreme_crew").assertTextEquals(
            "If your crew keeps working, take breaks in the shade and follow your crew's rule.")
    }

    // Spec §5.10: Break's stop line, cadence and level are HeatShield suggestions, labelled under "Why this suggestion?"
    // as Today's Why sheet labels them. An open answer alone, or no current forecast, suggests nothing: no label.
    @Test
    fun breakLabelsOnlyItsOwnSuggestionsAsHeatShieldRules() {
        var heat by mutableStateOf(day(extremeDay))
        var step by mutableStateOf<NextStep>(NextStep.OpenAnswer(cantStop))
        compose.setEnglishContent {
            HeatShieldTheme { Column { heatshield.ui.BreakWhy(heat, step) } }
        }
        compose.onNode(hasText("Why this suggestion?") and hasClickAction()).performClick()
        fun labels() = compose.onAllNodesWithContentDescription("Source: ${androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.getString(heatshield.ui.sourceLabelResource(SourceLabel.HEATSHIELD_RULE))}")
        // The stop line shows under an open answer, so the advice is still HeatShield's.
        labels().assertCountEquals(1)
        compose.runOnIdle { heat = day(veryHotDay); step = ContextReasoner.nextStep(heat, null) }
        labels().assertCountEquals(1) // the cadence
        compose.runOnIdle { step = NextStep.OpenAnswer(cantStop) }
        labels().assertCountEquals(0) // only the answer, no stop line
        compose.runOnIdle { heat = day(coolDay); step = NextStep.None }
        labels().assertCountEquals(1) // "Normal heat today", the level
        compose.runOnIdle { heat = day(coolDay, freshness = Freshness.STALE, updatedMinutesAgo = 8 * 60) }
        labels().assertCountEquals(0) // no current forecast for the shift
        compose.runOnIdle { heat = missing(error = null) }
        labels().assertCountEquals(0)
    }

    @Test
    fun shiftOutsideTheForecastHoursSaysSo() {
        show(reasoned((0..18).toList(), profile = profile.copy(shiftStartMinute = 1200, shiftEndMinute = 1380)))
        compose.onNodeWithTag("today_no_hours").assertExists()
        compose.onNodeWithText("The forecast doesn't cover your shift hours today.").assertIsDisplayed()
        compose.onNodeWithTag("heat_hero").assertDoesNotExist()
    }

    // Review Focus 3: offline first run, no forecast ever fetched.
    @Test
    fun missingForecastShowsRetryWithoutInventingHeat() {
        refreshing = true
        show(missing(error = "Forecast could not be reached. Check your connection and retry."))
        compose.onNodeWithText("Getting today's forecast…").assertIsDisplayed()
        compose.runOnIdle { refreshing = false }
        compose.onNodeWithText("No forecast yet. Connect once to get today's heat.").assertIsDisplayed()
        compose.onNodeWithText("Forecast could not be reached. Check your connection and retry.").assertExists()
        compose.onNodeWithTag("today_missing").assertExists()
        compose.onNodeWithTag("heat_hero").assertDoesNotExist()
        compose.onNodeWithTag("today_line_crew").assertDoesNotExist()
        compose.onNodeWithTag("park_switcher").assertTextEquals("Fri 9 Jan · Birrarung Marr")
        compose.onNode(hasText("Retry") and hasClickAction()).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, refreshes) }
        line("today_preview_row", "Preview a hot day")
        compose.onNodeWithTag("today_unwell").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, firstAid) }
    }

    @Test
    fun parkSwitcherListsEveryParkAndReportsTheChoice() {
        show(day(coolDay))
        compose.onNodeWithTag("park_switcher").assertTextEquals("Fri 9 Jan · Birrarung Marr").performClick()
        sites.forEach { compose.onNode(hasText(it) and hasAnyAncestor(isPopup())).assertExists() }
        compose.onNode(hasText("Royal Park") and hasAnyAncestor(isPopup())).performClick()
        compose.runOnIdle { assertEquals(listOf("Royal Park"), selected) }
    }

    @Test
    fun savedWorkWindowShowsUnderTheLinesAndOpensThePlan() {
        show(day(veryHotDay), savedWindow = "07:00–09:00")
        line("today_line_plan", "Your work window: ${at(7)} – ${at(9)}").performClick()
        compose.runOnIdle { assertEquals(1, plans) }
    }

    // A1: an open answer is Today's next step. It sits beside the stop line, not in place of it, and opens the check-in.
    @Test
    fun openAnswerShowsAsTheNextStepBesideTheStopLine() {
        show(day(extremeDay), nextStep = NextStep.OpenAnswer(cantStop))
        line("today_extreme", stopLine)
        line("today_line_cadence", keepWorking)
        line("today_line_next", "You said: Can't stop yet")
        // Spec §10: the next step sits with Today's lines, above the forecast stamp that closes them.
        // Unclipped layout positions, so the order holds whichever line is scrolled into view.
        fun y(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().positionInRoot.y
        assertTrue("The open answer comes before the forecast stamp", y("today_line_next") < y("today_line_updated"))
        compose.onNodeWithTag("today_line_next").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(1, 0), listOf(breakSheets, breaks)) }
    }

    // A1 and Review Focus 6: the answer outlives the forecast, so it shows on the no-forecast card too.
    @Test
    fun openAnswerPersistsWithNoForecast() {
        show(missing(error = null), nextStep = NextStep.OpenAnswer(cantStop))
        compose.onNodeWithTag("today_missing").assertExists()
        compose.onNodeWithTag("heat_hero").assertDoesNotExist()
        line("today_line_next", "You said: Can't stop yet")
    }

    @Test
    fun whySheetGivesEveryNumberExactlyOneSource() {
        show(day(veryHotDay))
        assertEveryDegreeHasAWhyRow { compose.onNodeWithTag("today_line_crew").performScrollTo().performClick() }
        compose.onNodeWithText("Why this?").assertExists()
        val more = top(compose.onNodeWithText("Other numbers on Today"))
        assertTrue("The tapped line's row comes first", top(compose.onNodeWithTag("why_row_crew_rule")) < more)
        assertTrue("The other rows follow", top(compose.onNodeWithTag("why_row_level")) > more)
        // A7: the level and every action HeatShield composes are HeatShield rules; the 35° preset is a workplace example.
        val expected = mapOf(
            "level" to SourceLabel.HEATSHIELD_RULE, "heat_number" to SourceLabel.HEATSHIELD_RULE,
            "now" to SourceLabel.HEATSHIELD_RULE, "feels_like" to SourceLabel.BOM,
            "crew_rule" to SourceLabel.WORKPLACE_RULE_EXAMPLE, "heavy_jobs" to SourceLabel.HEATSHIELD_RULE,
            "uv" to SourceLabel.BOM, "sun_all_year" to SourceLabel.WORKSAFE_VIC,
            "cadence" to SourceLabel.HEATSHIELD_RULE, "union_rest" to SourceLabel.UNION_STARTING_POINT,
            "forecast_age" to SourceLabel.HEATSHIELD_RULE
        )
        expected.forEach { (key, label) ->
            assertEquals("Source labels in the $key row", listOf(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.getString(heatshield.ui.sourceLabelResource(label))), sourceLabelsIn("why_row_$key"))
        }
        // Not raised, not Extreme, and no forecast number filed under a regulator's chip.
        listOf("adjustments", "extreme", "peak_air").forEach { compose.onNodeWithTag("why_row_$it").assertDoesNotExist() }
        compose.onAllNodes(tagged("why_row_")).assertCountEquals(expected.size)
        compose.onNodeWithTag("why_row_crew_rule").assertTextContains("Air forecast to reach 35° from ${at(11)} (3 h of your shift)")
        compose.onNodeWithTag("why_row_crew_rule")
            .assertTextContains("Confirm the number with your team leader", substring = true)
        compose.onNodeWithTag("why_row_crew_rule")
            .assertTextContains("Crew rules usually go by a BOM reading on the day", substring = true)
        compose.onNodeWithTag("why_row_heat_number")
            .assertTextContains("never shows a heat number below the forecast air temperature", substring = true)
        compose.onNodeWithTag("why_row_now").assertTextContains("Heat number 34° now (the hour from ${at(10)})")
        compose.onNodeWithText(whyFooter).assertExists()
    }

    // A7: a rule that is not one of the presets (35°, 38°) is the gardener's own setting, not a workplace example.
    // Air whole degrees on the very hot day: 26 29 32 34 36 37 36 33, so a 33° rule is reached from 10 am, 5 h.
    @Test
    fun customCrewRuleIsLabelledAsYourSetting() {
        show(day(veryHotDay, triggerC = 33.0))
        line("today_line_crew", "Air forecast to reach 33° from ${at(10)} (5 h of your shift)").performClick()
        assertEquals(listOf(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.getString(heatshield.ui.sourceLabelResource(SourceLabel.YOUR_SETTING))), sourceLabelsIn("why_row_crew_rule"))
        compose.onNodeWithTag("why_row_crew_rule").assertTextContains("Confirm it with your team leader", substring = true)
        // A custom number is not credited to the AWU clause or to Banyule.
        compose.onNodeWithTag("why_row_crew_rule").assert(!hasText("AWU", substring = true))
    }

    @Test
    fun tappingTheHeroExplainsTheLevelAndTheAdjustment() {
        show(day(warmDay, heavyWork = true))
        compose.onNodeWithTag("hero_numeral", useUnmergedTree = true).performClick()
        assertTrue("The level row comes first",
            top(compose.onNodeWithTag("why_row_level")) < top(compose.onNodeWithText("Other numbers on Today")))
        // The row opens on the hero's own word ("Hot", "for you"), then the level from the heat number alone.
        compose.onNodeWithTag("why_row_level").assertTextContains("Hot for you · Warm at a heat number of 31°")
        // A7: the level is HeatShield's rule; the text names the numbers it borrows and where they come from.
        compose.onNodeWithTag("why_row_level").assertTextContains("borrow VTHC's rest table", substring = true)
        compose.onNodeWithTag("why_row_level").assertTextContains("HeatShield applies them to the heat number", substring = true)
        assertEquals(listOf(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.getString(heatshield.ui.sourceLabelResource(SourceLabel.HEATSHIELD_RULE))), sourceLabelsIn("why_row_level"))
        compose.onNodeWithTag("why_row_adjustments").assertTextContains("Raised to Hot for you")
        assertEquals(listOf(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.getString(heatshield.ui.sourceLabelResource(SourceLabel.HEATSHIELD_RULE))), sourceLabelsIn("why_row_adjustments"))
    }

    // Review Focus 4: 200 % font on a 360 dp phone.
    @Test
    fun largeFontOnANarrowPhoneKeepsTheHeroAndListsHours() {
        show(day(veryHotDay), largeFontNarrowPhone = true)
        fun layoutOf(tag: String) = mutableListOf<TextLayoutResult>().also { out ->
            compose.onNodeWithTag(tag, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(out) }
        }.single()
        compose.onNodeWithTag("hero_numeral", useUnmergedTree = true).assertIsDisplayed().assertTextEquals("37°")
        val numeral = layoutOf("hero_numeral")
        assertFalse("hero numeral clipped", numeral.hasVisualOverflow)
        // Accept any size that renders the complete number; shrinking below a particular token is not a user contract.
        assertFalse("hero numeral ellipsized", numeral.isLineEllipsized(0))
        assertEquals("complete numeral is laid out", "37°".length, numeral.getLineEnd(0, visibleEnd = true))
        listOf("hero_level", "hero_numeral_label", "hero_caption").forEach { tag ->
            val layout = layoutOf(tag)
            assertFalse("$tag clipped: size=${layout.size}, constraints=${layout.layoutInput.constraints}, " +
                "widthOverflow=${layout.didOverflowWidth}, heightOverflow=${layout.didOverflowHeight}, " +
                "paragraph=${layout.multiParagraph.width} x ${layout.multiParagraph.height}, lines=${layout.lineCount}, " +
                "lineRight=${(0 until layout.lineCount).map { layout.getLineRight(it) }}",
                layout.hasVisualOverflow)
        }
        // The park name wraps instead of squeezing the dropdown arrow to nothing.
        compose.onNodeWithContentDescription("Change park", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("hero_level", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("hero_caption", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("heat_hour_list", useUnmergedTree = true).assertExists()
        line("today_line_crew", "Air forecast to reach 35° from ${at(11)} (3 h of your shift)").assertIsDisplayed()
        compose.onNodeWithTag("shift_start").performScrollTo().assertIsDisplayed()
        // The Why sheet at the same 200 % font: WhySheet carries this density into its own window.
        compose.onNodeWithTag("today_line_crew").performScrollTo().performClick()
        compose.onNodeWithTag("why_row_forecast_age").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(whyFooter).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun onShiftTodayShowsThePanelUnderTheHeroAndANextBreakWhyRow() {
        val heat = day(veryHotDay, heavyWork = true)
        val row = heatshield.data.WorkSession(id = 1, ownerId = "local", title = "Digging & planting", site = "Birrarung Marr", date = "9 Jan 2026",
            outdoorMinutes = 0, breakMinutes = 0, status = heatshield.data.ShiftStatus.NONE, startedAt = date.atTime(7, 0).atZone(MelbourneZone).toInstant().toEpochMilli(),
            plannedEndAt = date.atTime(15, 0).atZone(MelbourneZone).toInstant().toEpochMilli(), source = heatshield.data.ShiftSource.TRACKED, heavyWork = true)
        val evaluation = heatshield.domain.ShiftRules.evaluate(heatshield.domain.OpenShift(row, emptyList()), heat.hours,
            heatshield.domain.BreakRule.HEATSHIELD, 570, 720, heatshield.domain.ShiftRules.instant(date.atTime(10, 20)))
        show(heat, shift = heatshield.ui.ShiftUiState.On(row, evaluation))
        compose.onNodeWithTag("shift_start").assertDoesNotExist()
        compose.onNodeWithTag("shift_status").assertIsDisplayed()
        compose.onNodeWithTag("today_preview_row").assertDoesNotExist()
        compose.onNodeWithTag("shift_status").performClick()
        compose.onNodeWithTag("why_row_next_break").assertIsDisplayed()
        // The row merges its texts for TalkBack, so its rule text is a node of the unmerged tree.
        compose.onNode(hasAnyAncestor(hasTestTag("why_row_next_break")) and hasText("not a measure of recovery", substring = true),
            useUnmergedTree = true).assertExists()
    }

    // Union table, adapted by HeatShield: a heat number of 36° or more that is not Extreme stops work outdoors. The line
    // reads the heat itself, like the Extreme line, so it stays under an open answer; its Why row is the adapted rule.
    // The very hot day peaks at 36.5 (37°, Very hot with no personal factors), so no hour is Extreme. Its first hour at
    // 36° is 11 am (35.5 shows 36°); now is 10 am at 34°, so the line is timed from 11 am.
    @Test
    fun unionTableStopLineStaysUnderAnOpenAnswerAndExplainsItself() {
        show(day(veryHotDay), nextStep = NextStep.OpenAnswer(cantStop), breakRule = BreakRule.UNION_VTHC)
        compose.onNodeWithTag("today_extreme").assertDoesNotExist()
        line("today_line_stop", "From ${at(11)}: stop work outdoors. The union table, adapted by HeatShield, stops from 36°.")
        line("today_line_next", "You said: Can't stop yet")
        assertEveryDegreeHasAWhyRow { compose.onNodeWithTag("today_line_stop").performScrollTo().performClick() }
        assertTrue("The tapped line's row comes first",
            top(compose.onNodeWithTag("why_row_next_break")) < top(compose.onNodeWithText("Other numbers on Today")))
        compose.onNodeWithTag("why_row_next_break").assertTextContains("not the union's own rule", substring = true)
        // The adaptation is HeatShield's: VTHC is credited in the text, never on the chip (Global Constraint 11).
        assertEquals(listOf(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.getString(heatshield.ui.sourceLabelResource(SourceLabel.HEATSHIELD_RULE))), sourceLabelsIn("why_row_next_break"))
    }

    // Only the 2 pm hour reaches 36°: at 9 am the union line names 2 pm instead of telling a cool morning to stop, as the
    // Extreme line does, and the break check-in says the same.
    @Test
    fun unionStopLaterInTheDayIsTimedFromItsHour() {
        val heat = day(listOf(26.0, 28.0, 30.0, 31.0, 32.0, 33.0, 34.0, 36.0), nowHour = 9)
        show(heat, breakRule = BreakRule.UNION_VTHC)
        line("today_line_stop", "From ${at(14)}: stop work outdoors. The union table, adapted by HeatShield, stops from 36°.")
        compose.onNodeWithTag("today_extreme").assertDoesNotExist()
    }

    // Under the union table a heat number of 36° at 10 am stops work outdoors from 10 am, though the first Extreme hour
    // (38°) is not until 2 pm. The later Extreme line keeps its own time and never hides the earlier union stop, on Today
    // and on the break check-in, at 9:20 and inside the 10 am hour.
    @Test
    fun aLaterExtremeHourNeverHidesAnEarlierUnionStop() {
        val airs = listOf(30.0, 31.0, 32.0, 36.0, 35.0, 35.0, 34.0, 38.0)
        var heat by mutableStateOf(day(airs, nowHour = 9))
        var now by mutableStateOf(millisAt(9))
        var sheet by mutableStateOf(false)
        compose.setEnglishContent {
            HeatShieldTheme(darkTheme = false) {
                val step = ContextReasoner.nextStep(heat, null, BreakRule.UNION_VTHC)
                if (sheet) Column { heatshield.ui.BreakHeatAdvice(heat, step, breakRule = BreakRule.UNION_VTHC) }
                else TodayContent(heat = heat, sites = sites, refreshing = false, nowMillis = now, onSelectSite = {}, onRefresh = {},
                    onOpenPreview = {}, onOpenPlan = {}, onOpenFirstAid = {}, breakRule = BreakRule.UNION_VTHC, nextStep = step)
            }
        }
        val unionFromTen = "From ${at(10)}: stop work outdoors. The union table, adapted by HeatShield, stops from 36°."
        val extremeFromTwo = "From ${at(14)}: stop heavy work outdoors. Check your crew's hot-day rule."
        line("today_line_stop", unionFromTen)
        line("today_extreme", extremeFromTwo)
        compose.runOnIdle { sheet = true }
        compose.onNodeWithTag("break_union_stop").assertTextEquals(unionFromTen)
        compose.onNodeWithTag("break_extreme").assertTextEquals(extremeFromTwo)
        // 10:20: the union table's stop applies now; the Extreme line still names 2 pm.
        compose.runOnIdle { heat = day(airs, nowHour = 10); now = millisAt(10); sheet = false }
        val unionNow = "Stop work outdoors. The union table, adapted by HeatShield, stops from 36°."
        line("today_line_stop", unionNow)
        line("today_extreme", extremeFromTwo)
        compose.runOnIdle { sheet = true }
        compose.onNodeWithTag("break_union_stop").assertTextEquals(unionNow)
        compose.onNodeWithTag("break_extreme").assertTextEquals(extremeFromTwo)
    }

    // Heavy work (a push-mowing gardener) makes the 35° hour at 10 am Extreme for you, so "stop heavy work" starts at
    // 10 am; the union table's 36° stop comes at 11 am. The Extreme line stops heavy work only, so the union line still
    // shows, after it, on Today and on the break check-in, where the crew line for work that goes ahead follows both.
    @Test
    fun anEarlierExtremeHourForHeavyWorkNeverHidesTheUnionStop() {
        val sheet = showUnionTodayOrSheet(day(listOf(30.0, 31.0, 32.0, 35.0, 36.0, 34.0, 33.0, 32.0), heavyWork = true, nowHour = 9))
        val extremeFromTen = "From ${at(10)}: stop heavy work outdoors. Check your crew's hot-day rule."
        val unionFromEleven = "From ${at(11)}: stop work outdoors. The union table, adapted by HeatShield, stops from 36°."
        assertLinesInOrder("today_extreme" to extremeFromTen, "today_line_stop" to unionFromEleven)
        compose.runOnIdle { sheet.value = true }
        assertLinesInOrder("break_extreme" to extremeFromTen, "break_union_stop" to unionFromEleven, "break_extreme_crew" to keepWorking)
    }

    // The first hour at 36° is already 38° at 11 am, so it is Extreme too and both stops start then. Both still show,
    // since they say different things; on a tie the union line, which stops all work, comes first.
    @Test
    fun aFirstUnionHourThatIsAlreadyExtremeShowsBothStops() {
        val sheet = showUnionTodayOrSheet(day(listOf(30.0, 31.0, 32.0, 34.0, 38.0, 37.0, 36.0, 34.0), nowHour = 9))
        val unionFromEleven = "From ${at(11)}: stop work outdoors. The union table, adapted by HeatShield, stops from 36°."
        val extremeFromEleven = "From ${at(11)}: stop heavy work outdoors. Check your crew's hot-day rule."
        assertLinesInOrder("today_line_stop" to unionFromEleven, "today_extreme" to extremeFromEleven)
        compose.runOnIdle { sheet.value = true }
        assertLinesInOrder("break_union_stop" to unionFromEleven, "break_extreme" to extremeFromEleven, "break_extreme_crew" to keepWorking)
    }

    // Under the union table Today's rhythm is the union row for the peak, the same one the break sheet gives.
    @Test
    fun unionTableCadenceMatchesTheBreakSheet() {
        val heat = day(listOf(28.0, 29.0, 30.0, 31.0, 32.0, 33.0, 32.0, 31.0))   // peak 33°: 30 min work, 30 min break
        var sheet by mutableStateOf(false)
        compose.setEnglishContent {
            HeatShieldTheme(darkTheme = false) {
                if (sheet) Column { heatshield.ui.BreakHeatAdvice(heat, ContextReasoner.nextStep(heat, null, BreakRule.UNION_VTHC), breakRule = BreakRule.UNION_VTHC) }
                else TodayContent(heat = heat, sites = sites, refreshing = false, nowMillis = nowMillis, onSelectSite = {}, onRefresh = {},
                    onOpenPreview = {}, onOpenPlan = {}, onOpenFirstAid = {}, breakRule = BreakRule.UNION_VTHC,
                    nextStep = ContextReasoner.nextStep(heat, null, BreakRule.UNION_VTHC))
            }
        }
        line("today_line_cadence", "Suggested at the peak: 30 min work, 30 min break in shade").performClick()
        compose.onNodeWithTag("why_row_cadence").assertTextContains("HeatShield's adaptation of VTHC's rest table", substring = true)
        assertEquals(listOf(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.getString(heatshield.ui.sourceLabelResource(SourceLabel.HEATSHIELD_RULE))), sourceLabelsIn("why_row_cadence"))
        compose.runOnIdle { sheet = true }
        compose.onNodeWithText("Suggested at the peak: 30 min work, 30 min break in shade").assertExists()
    }

    // On shift, Today is the shift's park: the eyebrow names it and offers no switcher, which would change the usual park
    // and drop a plan while the heat stayed on the shift's park.
    @Test
    fun onShiftTheEyebrowNamesTheShiftsParkWithoutASwitcher() {
        val heat = day(veryHotDay).copy(site = "Royal Park")
        show(heat, shift = onShift(heat, date.atTime(10, 20)))
        compose.onNodeWithTag("today_eyebrow").assertTextEquals("Fri 9 Jan · Royal Park")
        compose.onAllNodes(hasTestTag("park_switcher")).assertCountEquals(0)
        compose.onAllNodes(hasClickAction() and hasText("Royal Park", substring = true)).assertCountEquals(0)
        compose.onNodeWithContentDescription("Change park", useUnmergedTree = true).assertDoesNotExist()
    }

    // The running break's notch ends at the shift's own now, the time the break card counts to, not the wall clock.
    @Test
    fun aRunningBreakIsListedOnTheRibbonUpToTheShiftsNow() {
        val heat = day(veryHotDay)
        val breakEvent = heatshield.data.ShiftEvent(id = 5, shiftId = 1, type = heatshield.data.ShiftEventType.BREAK,
            startedAt = date.atTime(10, 5).atZone(MelbourneZone).toInstant().toEpochMilli())
        val row = shiftRow()
        val evaluation = heatshield.domain.ShiftRules.evaluate(heatshield.domain.OpenShift(row, listOf(breakEvent)), heat.hours,
            BreakRule.HEATSHIELD, 570, 720, heatshield.domain.ShiftRules.instant(date.atTime(10, 20)))
        // The wall clock reads 11:20; the shift's timeline (and the card's clock) reads 10:20.
        show(heat, largeFontNarrowPhone = true, now = millisAt(11), shift = ShiftUiState.OnBreak(row, evaluation, breakEvent))
        compose.onNodeWithTag("break_clock").assertTextEquals("15:00")
        val listed = "Break ${Formats.time(LocalTime.of(10, 5), use24Hour)} – ${Formats.time(LocalTime.of(10, 20), use24Hour)}"
        compose.onNodeWithText(listed).performScrollTo().assertIsDisplayed()
    }

    // Astra M2 #4: at 1:20 am a 22:00–06:00 shift's Today is its own night: the evening's hours, then tomorrow's, the
    // Extreme 1 am hour as now with its stop line, and a missing 2 am after the evening's hours on the ribbon.
    @Test
    fun aNightShiftShowsItsOwnHoursAcrossMidnight() {
        val tomorrow = date.plusDays(1)
        val at0120 = tomorrow.atTime(1, 20).atZone(MelbourneZone).toInstant().toEpochMilli()
        val startedAt = date.atTime(22, 0).atZone(MelbourneZone).toInstant().toEpochMilli()
        val night = shiftRow().copy(startedAt = startedAt, plannedEndAt = tomorrow.atTime(6, 0).atZone(MelbourneZone).toInstant().toEpochMilli())
        val air = (0..23).map { date.atTime(it, 0) } + (0..23).map { tomorrow.atTime(it, 0) }
        val heat = ContextReasoner.todayHeat(
            ForecastSnapshot(site = "Birrarung Marr", fetchedAt = at0120 - 10 * 60_000L, hours = air.filterNot { it == tomorrow.atTime(2, 0) }.map { time ->
                ForecastHour(time = time.toString(), temperature = if (time == tomorrow.atTime(1, 0)) 39.0 else 25.0, humidity = 10.0, uv = 0.0, windSpeed = 8.0)
            }),
            ContextReasoner.effectiveProfile(heatshield.data.UserPreferences(ownerId = "local", site = "Birrarung Marr", setupDone = true), null, tomorrow, night),
            Clock.fixed(Instant.ofEpochMilli(at0120), MelbourneZone))
        val evaluation = heatshield.domain.ShiftRules.evaluate(heatshield.domain.OpenShift(night, emptyList()), heat.hours, BreakRule.HEATSHIELD, 570, 720,
            Instant.ofEpochMilli(at0120))
        show(heat, now = at0120, shift = ShiftUiState.On(night, evaluation))
        line("today_extreme", stopLine)
        compose.onNodeWithTag("shift_status").assertTextEquals("Extreme 39° · stop heavy work outdoors · 3 h 20 min since you started")
        fun left(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.left
        assertTrue("1 am follows 11 pm", left("ribbon_hour_23") < left("ribbon_hour_01"))
        assertTrue("the missing 2 am follows 1 am", left("ribbon_hour_01") < left("ribbon_missing_02"))
    }

    // With no forecast hours the panel still runs under the forecast card, and its status opens no Why sheet.
    @Test
    fun onShiftWithNoForecastHoursThePanelFollowsTheForecastCard() {
        val heat = reasoned((0..18).toList(), profile = profile.copy(shiftStartMinute = 1200, shiftEndMinute = 1380))
        show(heat, shift = onShift(heat, date.atTime(10, 20)))
        compose.onNodeWithTag("today_no_hours").assertExists()
        compose.onNodeWithTag("heat_hero").assertDoesNotExist()
        assertTrue("The shift card follows the forecast card",
            top(compose.onNodeWithTag("shift_card")) > top(compose.onNodeWithTag("today_no_hours")))
        compose.onNodeWithTag("shift_status").assert(hasNoClickAction())
    }

    private fun shiftRow() = heatshield.data.WorkSession(id = 1, ownerId = "local", title = "Digging & planting", site = "Birrarung Marr",
        date = "9 Jan 2026", outdoorMinutes = 0, breakMinutes = 0, status = heatshield.data.ShiftStatus.NONE,
        startedAt = date.atTime(7, 0).atZone(MelbourneZone).toInstant().toEpochMilli(),
        plannedEndAt = date.atTime(15, 0).atZone(MelbourneZone).toInstant().toEpochMilli(), source = heatshield.data.ShiftSource.TRACKED)

    private fun onShift(heat: TodayHeat, now: java.time.LocalDateTime): ShiftUiState {
        val row = shiftRow()
        return ShiftUiState.On(row, heatshield.domain.ShiftRules.evaluate(heatshield.domain.OpenShift(row, emptyList()), heat.hours,
            BreakRule.HEATSHIELD, 570, 720, heatshield.domain.ShiftRules.instant(now)))
    }

    private fun show(
        heat: TodayHeat, largeFontNarrowPhone: Boolean = false, now: Long = nowMillis, savedWindow: String? = null,
        nextStep: NextStep = NextStep.None, shift: ShiftUiState = ShiftUiState.Off, breakRule: BreakRule = BreakRule.HEATSHIELD,
        alertStatus: heatshield.background.AlertStatus? = null
    ) {
        compose.setEnglishContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides if (largeFontNarrowPhone) Density(base.density, fontScale = 2f) else base) {
                HeatShieldTheme(darkTheme = false) {
                    Box(if (largeFontNarrowPhone) Modifier.width(360.dp) else Modifier) {
                        TodayContent(
                            heat = heat, sites = sites, refreshing = refreshing, nowMillis = now,
                            onSelectSite = { selected += it }, onRefresh = { refreshes++ },
                            onOpenPreview = { previews++ }, onOpenPlan = { plans++ },
                            onOpenFirstAid = { firstAid++ }, nextStep = nextStep, savedWindow = savedWindow, shift = shift,
                            breakRule = breakRule, alertStatus = alertStatus,
                            onShiftAction = {
                                if (it == ShiftAction.OpenStart) breaks++
                                if (it == ShiftAction.OpenBreakSheet) breakSheets++
                            }
                        )
                    }
                }
            }
        }
    }

    /**
     * Today under the union table for [heat] at 9:20, with the step the reasoner gives it; once the returned flag is set,
     * the break check-in's heat advice for the same heat instead.
     */
    private fun showUnionTodayOrSheet(heat: TodayHeat): androidx.compose.runtime.MutableState<Boolean> {
        val sheet = mutableStateOf(false)
        compose.setEnglishContent {
            HeatShieldTheme(darkTheme = false) {
                val step = ContextReasoner.nextStep(heat, null, BreakRule.UNION_VTHC)
                if (sheet.value) Column { heatshield.ui.BreakHeatAdvice(heat, step, breakRule = BreakRule.UNION_VTHC) }
                else TodayContent(heat = heat, sites = sites, refreshing = false, nowMillis = millisAt(9), onSelectSite = {}, onRefresh = {},
                    onOpenPreview = {}, onOpenPlan = {}, onOpenFirstAid = {}, breakRule = BreakRule.UNION_VTHC, nextStep = step)
            }
        }
        return sheet
    }

    /** Each tagged line (tag to text) shows its text, and the lines sit top to bottom in the order given. */
    private fun assertLinesInOrder(vararg lines: Pair<String, String>) {
        lines.forEach { (tag, text) -> compose.onNodeWithTag(tag).assertTextEquals(text) }
        // positionInRoot is not clipped to the screen, so lines below the fold still compare.
        val ys = lines.map { compose.onNodeWithTag(it.first).fetchSemanticsNode().positionInRoot.y }
        assertTrue("Top to bottom ${lines.map { it.first }}: $ys", ys.zipWithNext().all { (a, b) -> a < b })
    }

    /** 20 past [hour] on the fixture date, Melbourne time. */
    private fun millisAt(hour: Int): Long = date.atTime(hour, 20).atZone(MelbourneZone).toInstant().toEpochMilli()

    /**
     * One fixture day. By default the heat number equals air, so the expected numerals are easy to read.
     * [feelsAbove] puts feels-like (and so the heat number) that far above air, as on a still, humid day.
     * As in Task 5, `baseLevel` judges the whole degrees of the heat number, and an hour is over the rule when its air,
     * in whole degrees, reaches [triggerC]: both use HeatRules.wholeDegrees, the number Formats.temperature prints.
     * Every shift hour has a forecast, so coverage is complete to the end of the last hour.
     */
    private fun day(
        airs: List<Double>, heavyWork: Boolean = false, triggerC: Double = 35.0, feelsAbove: Double? = null,
        nowHour: Int = 10, freshness: Freshness = Freshness.FRESH, updatedMinutesAgo: Long? = 14,
        refreshedBy: String? = "app", error: String? = null
    ): TodayHeat {
        val factors = PersonalFactors(heavyWork = heavyWork)
        val hours = airs.mapIndexed { index, air ->
            val feels = if (feelsAbove == null) air - 1.5 else air + feelsAbove
            val heat = maxOf(air, feels)
            val base = HeatRules.baseLevel(heat)
            HourHeat(time = date.atTime(7 + index, 0), airC = air, humidityPct = 30.0, windMs = 2.0,
                feelsLikeC = feels, heat = heat, base = base, effective = HeatRules.effectiveLevel(base, factors),
                overTrigger = HeatRules.wholeDegrees(air) >= triggerC, uv = 6.5, uvForProtection = 6.5)
        }
        val peak = hours.maxByOrNull { it.heat }!!
        val over = hours.filter { it.overTrigger }
        // As in Task 5's todayHeat, the cut-off judges heavy work whatever the usual task.
        val heavy = factors.copy(heavyWork = true)
        return TodayHeat(
            date = date, site = "Birrarung Marr", hours = hours,
            coverage = Coverage(complete = true, availableUntil = LocalTime.of(7 + airs.size, 0), missing = emptyList()),
            uvCoverage = Coverage(complete = true, availableUntil = LocalTime.MIDNIGHT, missing = emptyList()),
            now = hours.first { it.time.hour == nowHour },
            peak = peak, dayLevel = peak.effective, bumped = peak.effective != peak.base,
            crewRule = CrewRuleLine(triggerC, over.firstOrNull()?.time?.toLocalTime(), over.size, hours.maxOf { it.airC }),
            heavyJobsBefore = hours.firstOrNull {
                HeatRules.effectiveLevel(it.base, heavy).atLeast(HeatLevel.HOT) || it.overTrigger
            }?.time?.toLocalTime(),
            uvPeak = UvCategory.HIGH, sunProtection = SunProtection(LocalTime.of(10, 0), LocalTime.of(16, 0)),
            suggestedCadence = HeatRules.suggestedCadence(peak.effective), freshness = freshness,
            updatedAtMillis = updatedMinutesAgo?.let { millisAt(nowHour) - it * 60_000L }, refreshedBy = refreshedBy, error = error
        )
    }

    // No forecast at all: as Task 5 reports it, every shift hour is missing and none is covered.
    private fun missing(error: String?) = TodayHeat(
        date = date, site = "Birrarung Marr", hours = emptyList(),
        coverage = Coverage(complete = false, availableUntil = null, missing = (7..14).map { date.atTime(it, 0) }),
        uvCoverage = Coverage(complete = false, availableUntil = null, missing = (0..23).map { date.atTime(it, 0) }),
        now = null, peak = null,
        dayLevel = HeatLevel.NORMAL, bumped = false, crewRule = CrewRuleLine(35.0, null, 0, null),
        heavyJobsBefore = null, uvPeak = null, sunProtection = null, suggestedCadence = null,
        freshness = Freshness.MISSING, updatedAtMillis = null, refreshedBy = null, error = error
    )

    /**
     * Today from the real reasoner: a forecast fetched 10 min ago holding [hours] of [day], read at 10:20.
     * Air is 22 + h/2; UV is 5 from 10 am and 1 before, so sun protection starts at 10 am.
     */
    private fun reasoned(hours: List<Int>, day: LocalDate = date, profile: HeatProfile = this.profile): TodayHeat =
        ContextReasoner.todayHeat(
            ForecastSnapshot(site = "Birrarung Marr", fetchedAt = nowMillis - 10 * 60_000L, hours = hours.map { h ->
                ForecastHour(time = day.atTime(h, 0).toString(), temperature = 22.0 + h / 2.0, humidity = 40.0,
                    uv = if (h >= 10) 5.0 else 1.0, windSpeed = 2.0)
            }),
            profile, Clock.fixed(Instant.ofEpochMilli(nowMillis), MelbourneZone)
        )

    private fun at(hour: Int) = Formats.time(LocalTime.of(hour, 0), use24Hour)

    private fun hero(level: String, numeral: String, caption: String) {
        compose.onNodeWithTag("hero_level", useUnmergedTree = true).assertTextEquals(level)
        compose.onNodeWithTag("hero_numeral", useUnmergedTree = true).assertTextEquals(numeral)
        compose.onNodeWithTag("hero_caption", useUnmergedTree = true).assertTextEquals(caption)
    }

    private fun line(tag: String, text: String): SemanticsNodeInteraction =
        compose.onNodeWithTag(tag).performScrollTo().assertTextEquals(text)

    private fun top(node: SemanticsNodeInteraction): Float = node.fetchSemanticsNode().boundsInRoot.top

    private fun tagged(prefix: String) = SemanticsMatcher("test tag starts with $prefix") {
        it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true
    }

    /**
     * Acceptance (spec §10): every number on Today opens a Why row. Collects each "NN°" shown in the hero and the
     * Today lines, runs [openWhy], then checks each one appears in some Why row's value (its first text).
     */
    private fun assertEveryDegreeHasAWhyRow(openWhy: () -> Unit) {
        val degree = Regex("""-?\d+(\.\d+)?°""")
        fun texts(matcher: SemanticsMatcher, unmerged: Boolean): List<List<String>> =
            compose.onAllNodes(matcher, useUnmergedTree = unmerged).fetchSemanticsNodes()
                .map { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }
        val shown = (texts(hasTestTag("hero_numeral") or hasTestTag("hero_numeral_label") or hasTestTag("hero_caption"), true) +
            texts(tagged("today_line_"), false)).flatten().flatMap { text -> degree.findAll(text).map { it.value }.toList() }
        assertTrue("Today shows no degrees", shown.isNotEmpty())
        openWhy()
        val explained = texts(tagged("why_row_"), false).mapNotNull { it.firstOrNull() }
            .flatMap { value -> degree.findAll(value).map { it.value }.toList() }.toSet()
        assertEquals("Degrees on Today with no Why row", emptyList<String>(), shown.filterNot { it in explained })
    }

    /** Labels shown inside one Why row, whether SourceChip exposes its label as text or as a description. */
    private fun sourceLabelsIn(rowTag: String): List<String> {
        val displays = SourceLabel.entries.map { androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.getString(heatshield.ui.sourceLabelResource(it)) }
        val nodes = compose.onAllNodes(hasAnyAncestor(hasTestTag(rowTag)), useUnmergedTree = true).fetchSemanticsNodes()
        val texts = nodes.flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }
            .filter { it in displays }
        if (texts.isNotEmpty()) return texts
        return nodes.flatMap { node -> node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
            .mapNotNull { description -> displays.firstOrNull { description.contains(it) } }
    }
}
