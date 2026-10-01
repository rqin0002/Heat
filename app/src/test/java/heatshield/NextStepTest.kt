package heatshield

import heatshield.data.ForecastHour
import heatshield.data.ForecastSnapshot
import heatshield.data.Freshness
import heatshield.data.MelbourneZone
import heatshield.domain.BreakRule
import heatshield.domain.Cadence
import heatshield.domain.ContextReasoner
import heatshield.domain.HeatLevel
import heatshield.domain.HeatProfile
import heatshield.domain.NextStep
import heatshield.domain.OpenAnswerKind
import heatshield.domain.PersonalFactors
import heatshield.domain.TodayHeat
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Today's next step, shared by Today and the break check-in (A1). Priority: an answer the gardener gave that is still
 * open, then the stop line at Extreme, then the day's suggested rhythm, then nothing. Pure: a forecast through the real
 * reasoner, the open shift's answer (`ShiftRules.openAnswer`) and a fixed clock.
 */
class NextStepTest {
    private val date = LocalDate.of(2026, 1, 9)
    private val now = date.atTime(10, 20).atZone(MelbourneZone).toInstant().toEpochMilli()
    private val clock = Clock.fixed(Instant.ofEpochMilli(now), MelbourneZone)
    private val profile = HeatProfile("Birrarung Marr", "Irrigation & checks", 420, 900, 35.0, PersonalFactors())
    private val hotRhythm = NextStep.BreakSuggested(Cadence(45, 15))

    // A break after "Can't stop yet" closes the answer (ShiftRules.openAnswer, see ShiftRulesTest); with none open, the rhythm shows.
    @Test
    fun withNoOpenAnswerTheHotDaysRhythmShows() {
        assertEquals(hotRhythm, ContextReasoner.nextStep(heat(), null))
    }

    @Test
    fun bothAnswersAreOpenAnswers() {
        listOf(OpenAnswerKind.CANT_STOP, OpenAnswerKind.NO_SHADE).forEach { kind ->
            assertEquals(kind.name, NextStep.OpenAnswer(kind), ContextReasoner.nextStep(heat(), kind))
        }
    }

    // Hot for light work; heavy work and heavy clothing take the same hour to Extreme.
    @Test
    fun anOpenAnswerComesBeforeTheStopLine() {
        val extreme = heat(factors = PersonalFactors(heavyWork = true, heavyClothing = true))
        assertEquals(HeatLevel.EXTREME, extreme.dayLevel)
        assertEquals(NextStep.StopHeavyWork, ContextReasoner.nextStep(extreme, null))
        assertEquals(NextStep.OpenAnswer(OpenAnswerKind.CANT_STOP), ContextReasoner.nextStep(extreme, OpenAnswerKind.CANT_STOP))
    }

    // HEAD kept an unresolved answer when its sensor feed stopped; this keeps it when the forecast is missing or old.
    @Test
    fun anOpenAnswerOutlivesAMissingOrStaleForecast() {
        val missing = heat(minutesAgo = null)
        assertEquals(Freshness.MISSING, missing.freshness)
        assertEquals(NextStep.OpenAnswer(OpenAnswerKind.NO_SHADE), ContextReasoner.nextStep(missing, OpenAnswerKind.NO_SHADE))
        val stale = heat(minutesAgo = 7 * 60L)
        assertEquals(Freshness.STALE, stale.freshness)
        assertEquals(NextStep.OpenAnswer(OpenAnswerKind.CANT_STOP), ContextReasoner.nextStep(stale, OpenAnswerKind.CANT_STOP))
    }

    // The old effort and clothing replay test, now on the forecast: the same Hot day asks more of heavier work.
    @Test
    fun workAndClothingChangeTheNextStep() {
        assertEquals(hotRhythm, ContextReasoner.nextStep(heat(), null))
        assertEquals(NextStep.BreakSuggested(Cadence(30, 15)),
            ContextReasoner.nextStep(heat(factors = PersonalFactors(heavyWork = true)), null))
        assertEquals(NextStep.StopHeavyWork,
            ContextReasoner.nextStep(heat(factors = PersonalFactors(heavyWork = true, heavyClothing = true)), null))
    }

    // A rhythm needs a current forecast (fresh or aging) and a Warm or hotter day; otherwise nothing is claimed.
    @Test
    fun noRhythmWithoutACurrentForecastOrOnANormalDay() {
        val aging = heat(minutesAgo = 2 * 60L)
        assertEquals(Freshness.AGING, aging.freshness)
        assertEquals(hotRhythm, ContextReasoner.nextStep(aging, null))
        assertEquals(NextStep.None, ContextReasoner.nextStep(heat(minutesAgo = 7 * 60L), null))
        assertEquals(NextStep.None, ContextReasoner.nextStep(heat(minutesAgo = null), null))
        assertEquals(NextStep.None, ContextReasoner.nextStep(heat(airC = 24.0), null))
    }

    // The union table, adapted by HeatShield: its row for the day's peak heat number, and Stop from 36° (an open answer still comes first).
    @Test
    fun theUnionTableGivesItsRowAndStopsWorkFromThirtySix() {
        assertEquals(NextStep.BreakSuggested(Cadence(30, 30)), ContextReasoner.nextStep(heat(), null, BreakRule.UNION_VTHC))
        assertEquals(NextStep.BreakSuggested(Cadence(30, 15)), ContextReasoner.nextStep(heat(airC = 36.0), null))
        assertEquals(NextStep.StopHeavyWork, ContextReasoner.nextStep(heat(airC = 36.0), null, BreakRule.UNION_VTHC))
        assertEquals(NextStep.OpenAnswer(OpenAnswerKind.NO_SHADE), ContextReasoner.nextStep(heat(airC = 36.0), OpenAnswerKind.NO_SHADE, BreakRule.UNION_VTHC))
    }

    // Break timing reads only a current forecast: stale or missing hours are unknown, not cool (spec §5.13). The shift
    // reads every hour of the day from it, not only the usual shift's.
    @Test
    fun onlyACurrentForecastSuppliesHoursToTimeBreaksWith() {
        assertEquals(24, ContextReasoner.shiftTimingHours(forecast(), profile, now).size)
        assertEquals(24, ContextReasoner.shiftTimingHours(forecast(minutesAgo = 2 * 60L), profile, now).size)
        assertTrue(ContextReasoner.shiftTimingHours(forecast(minutesAgo = 7 * 60L), profile, now).isEmpty())
        assertTrue(ContextReasoner.shiftTimingHours(forecast(minutesAgo = null), profile, now).isEmpty())
    }

    // Under the union table Today's cadence line, the check-in and the panel's walk share one rhythm: the row for the peak.
    @Test
    fun theDaysCadenceFollowsTheBreakTableInUse() {
        assertEquals(Cadence(45, 15), ContextReasoner.dayCadence(heat(), BreakRule.HEATSHIELD))
        assertEquals(Cadence(30, 30), ContextReasoner.dayCadence(heat(), BreakRule.UNION_VTHC))
        assertEquals(NextStep.BreakSuggested(Cadence(30, 30)), ContextReasoner.nextStep(heat(), null, BreakRule.UNION_VTHC))
        // No union row below 30° (the HeatShield rule has none at Normal either), and none from 36°, where work stops.
        assertEquals(null, ContextReasoner.dayCadence(heat(airC = 29.0), BreakRule.UNION_VTHC))
        assertEquals(null, ContextReasoner.dayCadence(heat(airC = 36.0), BreakRule.UNION_VTHC))
    }

    /**
     * Air [airC] all day at 20 % humidity and 3 m/s wind, fetched [minutesAgo] before 10:20 (null: no forecast).
     * Feels like stays below air (33.0 gives about 30.2), so the heat number is the air: 33.0 is whole degrees 33, Hot,
     * and 24.0 is Normal. Air never shows 35°, so the crew rule plays no part.
     */
    private fun heat(airC: Double = 33.0, factors: PersonalFactors = PersonalFactors(), minutesAgo: Long? = 10): TodayHeat =
        ContextReasoner.todayHeat(forecast(airC, minutesAgo), profile.copy(factors = factors), clock)

    private fun forecast(airC: Double = 33.0, minutesAgo: Long? = 10): ForecastSnapshot? = minutesAgo?.let { ago ->
        ForecastSnapshot(site = "Birrarung Marr", fetchedAt = now - ago * 60_000L, hours = (0..23).map { h ->
            ForecastHour(time = date.atTime(h, 0).toString(), temperature = airC, humidity = 20.0, uv = 5.0, windSpeed = 3.0)
        })
    }
}
